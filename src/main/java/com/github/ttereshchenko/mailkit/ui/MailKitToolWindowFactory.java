package com.github.ttereshchenko.mailkit.ui;

import com.github.ttereshchenko.mailkit.conversion.ConversionConsoleService;
import com.github.ttereshchenko.mailkit.smtp.ui.SmtpConsoleService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * Wires the "MailKit" tool window content.
 */
public final class MailKitToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var contentManager = toolWindow.getContentManager();
        var contentFactory = ContentFactory.getInstance();

        // SMTP Transcript
        var smtpConsole = SmtpConsoleService.getInstance(project).getOrCreateConsole();
        var smtpContent = contentFactory.createContent(smtpConsole.getComponent(), "Send transcript", false);
        smtpContent.setCloseable(false);
        contentManager.addContent(smtpContent);

        // MSG Conversion
        var msgConsole =
                ConversionConsoleService.getInstance(project).getOrCreateConsole(ConversionConsoleService.Tab.MSG);
        var msgContent =
                contentFactory.createContent(msgConsole.getComponent(), ConversionConsoleService.Tab.MSG.title, false);
        msgContent.setCloseable(false);
        contentManager.addContent(msgContent);

        // PST/OST Conversion
        var pstConsole =
                ConversionConsoleService.getInstance(project).getOrCreateConsole(ConversionConsoleService.Tab.PST);
        var pstContent =
                contentFactory.createContent(pstConsole.getComponent(), ConversionConsoleService.Tab.PST.title, false);
        pstContent.setCloseable(false);
        contentManager.addContent(pstContent);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return true;
    }
}
