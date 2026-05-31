package com.github.ttereshchenko.mailkit.conversion.msg;

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
import com.intellij.openapi.vfs.VirtualFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.poi.hsmf.exceptions.ChunkNotFoundException;
import org.jetbrains.annotations.NotNull;

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
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Converting MSG to EML", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Reading " + source.getName());
                var eml = convertOrNotify(project, source);
                if (eml == null) {
                    return;
                }
                indicator.checkCanceled();
                indicator.setText("Writing " + targetName);
                writeAndOpen(project, source, targetName, eml.getBytes(StandardCharsets.US_ASCII));
            }
        });
    }

    static String convertOrNotify(Project project, VirtualFile source) {
        try (var stream = source.getInputStream()) {
            return MsgToEmlConverter.convert(stream);
        } catch (ProcessCanceledException canceled) {
            throw canceled;
        } catch (IOException | ChunkNotFoundException | RuntimeException failure) {
            notifyError(project, source.getName(), describeFailure(failure));
            return null;
        }
    }

    private static String describeFailure(Throwable failure) {
        var message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void writeAndOpen(Project project, VirtualFile source, String targetName, byte[] bytes) {
        try {
            WriteAction.runAndWait(() -> {
                var parent = source.getParent();
                if (parent == null) {
                    notifyError(project, source.getName(), "No parent directory");
                    return;
                }
                var existing = parent.findChild(targetName);
                var target =
                        existing != null ? existing : parent.createChildData(ConvertMsgToEmlAction.class, targetName);
                target.setBinaryContent(bytes);
                ApplicationManager.getApplication()
                        .invokeLater(
                                () -> FileEditorManager.getInstance(project).openFile(target, true));
            });
        } catch (IOException failure) {
            notifyError(project, source.getName(), failure.getMessage());
        }
    }

    private static void notifyError(Project project, String sourceName, String message) {
        ApplicationManager.getApplication()
                .invokeLater(() -> NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification("Could not convert " + sourceName + ": " + message, NotificationType.ERROR)
                        .notify(project));
    }
}
