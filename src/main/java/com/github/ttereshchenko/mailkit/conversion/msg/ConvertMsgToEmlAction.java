package com.github.ttereshchenko.mailkit.conversion.msg;

import com.github.ttereshchenko.mailkit.conversion.ConversionConsoleService;
import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.jetbrains.annotations.NotNull;

/**
 * Project-view context action that converts the selected Outlook {@code .msg} file into a sibling
 * {@code .eml} file via {@link MsgToEmlConverter}, reporting progress to the MailKit tool-window
 * console and opening the result in the editor. Runs in a background task; on physical filesystems
 * the result is written to a temp file and atomically moved into place.
 */
public final class ConvertMsgToEmlAction extends AnAction {

    private static final String NOTIFICATION_GROUP_ID = "MailKit";

    public ConvertMsgToEmlAction() {
        super("Convert to EML", "Convert the selected Outlook MSG file to a standards EML file", null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        var visible = file != null && !file.isDirectory() && "msg".equalsIgnoreCase(file.getExtension());
        event.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        var source = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || source == null) {
            return;
        }
        runConversion(project, source);
    }

    static void runConversion(Project project, VirtualFile source) {
        var targetName = source.getNameWithoutExtension() + ".eml";

        ConversionConsoleService console = ConversionConsoleService.getInstance(project);
        console.clear(ConversionConsoleService.Tab.MSG);
        console.activateToolWindow(ConversionConsoleService.Tab.MSG);
        console.info(ConversionConsoleService.Tab.MSG, "Starting conversion of " + source.getName() + "...");
        ConversionLog log = console.asLog(ConversionConsoleService.Tab.MSG);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Converting MSG to EML", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Converting " + source.getName());

                try {
                    boolean useNio = false;
                    Path sourcePath = null;
                    Path targetPath = null;
                    try {
                        sourcePath = source.toNioPath();
                        targetPath = sourcePath.getParent().resolve(targetName);
                        useNio = true;
                    } catch (UnsupportedOperationException exception) {
                        // TempFileSystem in tests doesn't support NIO Path
                    }

                    if (useNio) {
                        // Convert into a sibling temp file and atomically move it into place so a
                        // mid-conversion failure never leaves a truncated .eml at the target path.
                        var tempPath = Files.createTempFile(
                                targetPath.getParent(), source.getNameWithoutExtension(), ".eml.part");
                        try {
                            // File-backed overload: reads OLE blocks on demand instead of buffering
                            // the whole .msg in heap (matters for messages with huge attachments).
                            try (var out = Files.newOutputStream(tempPath)) {
                                MsgToEmlConverter.convert(sourcePath, out, log);
                            }
                            indicator.checkCanceled();
                            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                            console.info(ConversionConsoleService.Tab.MSG, "Converted successfully.");
                        } catch (Exception failure) {
                            try {
                                Files.deleteIfExists(tempPath);
                            } catch (IOException cleanupFailure) {
                                failure.addSuppressed(cleanupFailure);
                            }
                            throw failure;
                        }

                        var finalPath = targetPath;
                        ApplicationManager.getApplication().invokeLater(() -> {
                            var targetVirtual = VfsUtil.findFileByIoFile(finalPath.toFile(), true);
                            if (targetVirtual != null) {
                                FileEditorManager.getInstance(project).openFile(targetVirtual, true);
                            }
                        });
                    } else {
                        var out = new ByteArrayOutputStream();
                        try (var stream = source.getInputStream()) {
                            MsgToEmlConverter.convert(stream, out, log);
                        }

                        indicator.checkCanceled();
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                WriteAction.runAndWait(() -> {
                                    try {
                                        var parent = source.getParent();
                                        if (parent == null) throw new IOException("No parent directory");
                                        var target = parent.findChild(targetName);
                                        if (target == null)
                                            target = parent.createChildData(ConvertMsgToEmlAction.class, targetName);
                                        target.setBinaryContent(out.toByteArray());
                                        console.info(ConversionConsoleService.Tab.MSG, "Converted successfully.");
                                        FileEditorManager.getInstance(project).openFile(target, true);
                                    } catch (IOException exception) {
                                        console.error(
                                                ConversionConsoleService.Tab.MSG,
                                                "Failed: " + describeFailure(exception));
                                        notifyError(project, source.getName(), describeFailure(exception));
                                    }
                                });
                            } catch (Exception exception) {
                                console.error(
                                        ConversionConsoleService.Tab.MSG, "Failed: " + describeFailure(exception));
                                notifyError(project, source.getName(), describeFailure(exception));
                            }
                        });
                    }
                } catch (ProcessCanceledException canceled) {
                    console.info(ConversionConsoleService.Tab.MSG, "Conversion canceled.");
                    throw canceled;
                } catch (Exception failure) {
                    console.error(ConversionConsoleService.Tab.MSG, "Failure: " + describeFailure(failure));
                    notifyError(project, source.getName(), describeFailure(failure));
                }
            }
        });
    }

    private static String describeFailure(Throwable failure) {
        var message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void notifyError(Project project, String sourceName, String message) {
        ApplicationManager.getApplication()
                .invokeLater(() -> NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification("Could not convert " + sourceName + ": " + message, NotificationType.ERROR)
                        .notify(project));
    }
}
