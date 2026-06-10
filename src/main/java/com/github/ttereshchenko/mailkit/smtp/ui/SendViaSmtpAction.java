package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Editor / project popup entry point for "Send via SMTP…". Hidden when the global egress toggle
 * is off. Enabled for any selection that consists solely of {@code .eml} files — one or many.
 * Opens {@link SendDialog}, which collects the shared envelope and runs the batch itself with
 * in-dialog progress (see {@link BatchSendController}).
 */
public final class SendViaSmtpAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var enabled = SmtpProfileService.getInstance().isEgressEnabled();
        var files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        var allEml = files != null && files.length > 0 && allEmlFiles(files);
        var place = event.getPlace();
        var isToolbar =
                event.isFromActionToolbar() || "EditorContextBarMenu".equals(place) || "ContextToolbar".equals(place);

        if (!SmtpProfileService.getInstance().isShowEditorToolbarButton() && isToolbar) {
            event.getPresentation().setEnabledAndVisible(false);
            return;
        }
        event.getPresentation().setEnabledAndVisible(enabled && allEml);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) {
            return;
        }
        var files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        var sourceFiles = files == null ? List.<VirtualFile>of() : List.of(files);
        ApplicationManager.getApplication().invokeLater(() -> new SendDialog(project, sourceFiles).show());
    }

    private static boolean allEmlFiles(VirtualFile[] files) {
        for (var file : files) {
            if (!"eml".equalsIgnoreCase(file.getExtension())) {
                return false;
            }
        }
        return true;
    }
}
