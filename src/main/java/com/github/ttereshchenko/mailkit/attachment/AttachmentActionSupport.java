package com.github.ttereshchenko.mailkit.attachment;

import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.io.File;
import java.util.Locale;
import java.util.Optional;

final class AttachmentActionSupport {

    static final String NOTIFICATION_GROUP_ID = "MailKit";

    private AttachmentActionSupport() {}

    static ActionUpdateThread updateThread() {
        return ActionUpdateThread.BGT;
    }

    static Optional<AttachmentPartInfo> resolve(AnActionEvent event) {
        if (!EmlHeaderSettings.getInstance().isShowAttachmentActions()) {
            return Optional.empty();
        }
        var psiFile = event.getData(CommonDataKeys.PSI_FILE);
        var editor = event.getData(CommonDataKeys.EDITOR);
        if (psiFile == null || editor == null) {
            return Optional.empty();
        }
        var offset = editor.getCaretModel().getOffset();
        var element = psiFile.findElementAt(offset);
        if (element == null) {
            return Optional.empty();
        }
        var part = PsiTreeUtil.getParentOfType(element, EmlMimePart.class, false);
        if (part == null) {
            return Optional.empty();
        }
        return AttachmentDetector.detect(part);
    }

    static Optional<AttachmentPartInfo> resolveOnPart(PsiFile psiFile, int offset) {
        if (psiFile == null) {
            return Optional.empty();
        }
        var element = psiFile.findElementAt(offset);
        if (element == null) {
            return Optional.empty();
        }
        var part = PsiTreeUtil.getParentOfType(element, EmlMimePart.class, false);
        if (part == null) {
            return Optional.empty();
        }
        return AttachmentDetector.detect(part);
    }

    static void notifySuccess(Project project, File file, long sizeBytes) {
        ApplicationManager.getApplication().invokeLater(() -> {
            var notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP_ID)
                    .createNotification(
                            "Saved " + file.getName() + " (" + formatSize(sizeBytes) + ")",
                            NotificationType.INFORMATION);
            notification.addAction(NotificationAction.createSimpleExpiring(
                    RevealFileAction.getActionName(), () -> RevealFileAction.openFile(file)));
            notification.notify(project);
        });
    }

    static void notifyError(Project project, String message) {
        ApplicationManager.getApplication()
                .invokeLater(() -> NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP_ID)
                        .createNotification(message, NotificationType.ERROR)
                        .notify(project));
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        var kib = bytes / 1024.0;
        if (kib < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kib);
        }
        var mib = kib / 1024.0;
        return String.format(Locale.ROOT, "%.1f MB", mib);
    }
}
