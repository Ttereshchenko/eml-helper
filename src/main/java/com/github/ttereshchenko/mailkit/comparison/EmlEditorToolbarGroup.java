package com.github.ttereshchenko.mailkit.comparison;

import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import org.jetbrains.annotations.NotNull;

/**
 * Popup group shown as a single MailKit button in the editor's floating toolbar; opening it stacks
 * the per-file MailKit quick actions (Send EML…, Compare EML With…) vertically in a dropdown. The
 * button only appears for an EML editor and only while at least one of its actions is enabled for the
 * toolbar, so a non-EML file — or having both toolbar toggles off — leaves the toolbar uncluttered
 * instead of showing an empty dropdown.
 */
public final class EmlEditorToolbarGroup extends DefaultActionGroup {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        var file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        var smtp = SmtpProfileService.getInstance();
        var sendInToolbar = smtp.isEgressEnabled() && smtp.isShowEditorToolbarButton();
        var compareInToolbar = EmlHeaderSettings.getInstance().isShowCompareEditorToolbarButton();
        event.getPresentation().setVisible(CompareEmlAction.isEml(file) && (sendInToolbar || compareInToolbar));
    }
}
