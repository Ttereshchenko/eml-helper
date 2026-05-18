package com.github.ttereshchenko.mailkit.smtp.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Wires the "MailKit SMTP" tool window content. The factory just embeds the
 * {@link SmtpConsoleService}'s console — all real streaming happens through the service.
 */
public final class SmtpConsoleToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var console = SmtpConsoleService.getInstance(project).getOrCreateConsole();
        var content = ContentFactory.getInstance().createContent(console.getComponent(), "Send transcript", false);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
