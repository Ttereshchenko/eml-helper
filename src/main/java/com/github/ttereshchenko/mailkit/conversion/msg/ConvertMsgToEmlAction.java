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
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
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
        if (!confirmOverwrite(project, source, targetName)) {
            return;
        }

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
                // Each converter log call doubles as a cancellation checkpoint, so a long conversion
                // reacts to Cancel at attachment/embedded-message boundaries instead of only at the end.
                var cancellableLog = withCancellationCheck(log, indicator);

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
                                MsgToEmlConverter.convert(sourcePath, out, cancellableLog);
                            }
                            indicator.checkCanceled();
                            try {
                                Files.move(
                                        tempPath,
                                        targetPath,
                                        StandardCopyOption.ATOMIC_MOVE,
                                        StandardCopyOption.REPLACE_EXISTING);
                            } catch (AtomicMoveNotSupportedException ignored) {
                                // Filesystem without atomic rename (e.g. some network mounts): a plain
                                // replace is still better than failing the finished conversion.
                                Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                            }
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
                            MsgToEmlConverter.convert(stream, out, cancellableLog);
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
                    // The converter wraps every RuntimeException — including the indicator's
                    // ProcessCanceledException thrown from a log checkpoint — in ConversionException,
                    // so cancellation must be unwrapped before treating the failure as an error.
                    var canceled = findCancellation(failure);
                    if (canceled != null) {
                        console.info(ConversionConsoleService.Tab.MSG, "Conversion canceled.");
                        throw canceled;
                    }
                    console.error(ConversionConsoleService.Tab.MSG, "Failure: " + describeFailure(failure));
                    notifyError(project, source.getName(), describeFailure(failure));
                }
            }
        });
    }

    /**
     * Asks before clobbering an existing sibling {@code .eml}: the target may be a hand-edited file
     * rather than a previous conversion's output, and the converter cannot tell the difference.
     */
    private static boolean confirmOverwrite(Project project, VirtualFile source, String targetName) {
        var parent = source.getParent();
        var exists = parent != null && parent.findChild(targetName) != null;
        if (!exists) {
            try {
                var sourceDirectory = source.toNioPath().getParent();
                exists = sourceDirectory != null && Files.exists(sourceDirectory.resolve(targetName));
            } catch (UnsupportedOperationException ignored) {
                // TempFileSystem in tests doesn't support NIO Path
            }
        }
        if (!exists) {
            return true;
        }
        var answer = Messages.showYesNoDialog(
                project,
                targetName + " already exists next to " + source.getName() + ". Overwrite it?",
                "Convert MSG to EML",
                Messages.getWarningIcon());
        return answer == Messages.YES;
    }

    /** Wraps the log so every converter progress call first honors a pending cancel request. */
    private static ConversionLog withCancellationCheck(ConversionLog log, ProgressIndicator indicator) {
        return new ConversionLog() {
            @Override
            public void info(String message) {
                indicator.checkCanceled();
                log.info(message);
            }

            @Override
            public void error(String message) {
                indicator.checkCanceled();
                log.error(message);
            }
        };
    }

    /** The {@link ProcessCanceledException} buried in a wrapped failure's cause chain, or {@code null}. */
    static ProcessCanceledException findCancellation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProcessCanceledException canceledException) {
                return canceledException;
            }
        }
        return null;
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
