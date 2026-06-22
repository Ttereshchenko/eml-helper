package com.github.ttereshchenko.mailkit.comparison;

import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Guards the editor floating-toolbar dropdown group that stacks the MailKit per-file actions: it
 * appears only for an EML editor with at least one toolbar action enabled, and stays hidden
 * otherwise so non-EML editors (or both toggles off) keep an uncluttered toolbar.
 */
public class EmlEditorToolbarGroupTest extends BasePlatformTestCase {

    private static final String EML = "From: a@b.c\r\n\r\nbody\r\n";

    private EmlHeaderSettings settings;
    private SmtpProfileService smtp;
    private boolean originalCompareToolbar;
    private boolean originalEgress;
    private boolean originalSendToolbar;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        settings = EmlHeaderSettings.getInstance();
        smtp = SmtpProfileService.getInstance();
        originalCompareToolbar = settings.isShowCompareEditorToolbarButton();
        originalEgress = smtp.isEgressEnabled();
        originalSendToolbar = smtp.isShowEditorToolbarButton();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            settings.setShowCompareEditorToolbarButton(originalCompareToolbar);
            smtp.setEgressEnabled(originalEgress);
            smtp.setShowEditorToolbarButton(originalSendToolbar);
        } finally {
            super.tearDown();
        }
    }

    public void testVisibleForEmlWhenCompareEnabled() throws Exception {
        settings.setShowCompareEditorToolbarButton(true);
        var eml = myFixture.getTempDirFixture().createFile("group.eml", EML);
        var event = event(eml);

        new EmlEditorToolbarGroup().update(event);

        assertTrue(event.getPresentation().isVisible());
    }

    public void testHiddenForNonEml() throws Exception {
        settings.setShowCompareEditorToolbarButton(true);
        smtp.setEgressEnabled(true);
        smtp.setShowEditorToolbarButton(true);
        var txt = myFixture.getTempDirFixture().createFile("group.txt", "plain");
        var event = event(txt);

        new EmlEditorToolbarGroup().update(event);

        assertFalse(event.getPresentation().isVisible());
    }

    public void testHiddenWhenAllToolbarButtonsOff() throws Exception {
        settings.setShowCompareEditorToolbarButton(false);
        smtp.setEgressEnabled(false);
        smtp.setShowEditorToolbarButton(false);
        var eml = myFixture.getTempDirFixture().createFile("group-off.eml", EML);
        var event = event(eml);

        new EmlEditorToolbarGroup().update(event);

        assertFalse(event.getPresentation().isVisible());
    }

    private AnActionEvent event(VirtualFile file) {
        var context = SimpleDataContext.builder()
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .build();
        return AnActionEvent.createEvent(
                context, new Presentation(), "EditorContextBarMenu", ActionUiKind.TOOLBAR, null);
    }
}
