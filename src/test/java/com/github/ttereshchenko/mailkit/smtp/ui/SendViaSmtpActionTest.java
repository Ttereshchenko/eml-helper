package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.Nullable;

public class SendViaSmtpActionTest extends BasePlatformTestCase {

    private SmtpProfileService service;
    private boolean originalEgress;
    private boolean originalShowToolbar;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        service = SmtpProfileService.getInstance();
        originalEgress = service.isEgressEnabled();
        originalShowToolbar = service.isShowEditorToolbarButton();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            service.setEgressEnabled(originalEgress);
            service.setShowEditorToolbarButton(originalShowToolbar);
        } finally {
            super.tearDown();
        }
    }

    public void testActionHiddenWhenEgressDisabled() throws Exception {
        service.setEgressEnabled(false);
        var emlFile = myFixture.getTempDirFixture().createFile("ping.eml", "From: a@b.c\r\n\r\nbody\r\n");
        var event = buildEvent(emlFile);

        new SendViaSmtpAction().update(event);

        assertFalse(event.getPresentation().isEnabledAndVisible());
    }

    public void testActionHiddenForNonEmlFiles() throws Exception {
        service.setEgressEnabled(true);
        var txtFile = myFixture.getTempDirFixture().createFile("notes.txt", "plain text");
        var event = buildEvent(txtFile);

        new SendViaSmtpAction().update(event);

        assertFalse(event.getPresentation().isEnabledAndVisible());
    }

    public void testActionVisibleForEmlFileWithEgressEnabled() throws Exception {
        service.setEgressEnabled(true);
        var emlFile = myFixture.getTempDirFixture().createFile("send-me.eml", "From: a@b.c\r\n\r\nbody\r\n");
        var event = buildEvent(emlFile);

        new SendViaSmtpAction().update(event);

        assertTrue(event.getPresentation().isEnabledAndVisible());
    }

    public void testActionHiddenWhenToolbarButtonDisabled() throws Exception {
        service.setShowEditorToolbarButton(false);
        var emlFile = myFixture.getTempDirFixture().createFile("ping.eml", "From: a@b.c\r\n\r\nbody\r\n");
        var event =
                buildEventWithPlace(emlFile, "ContextToolbar", com.intellij.openapi.actionSystem.ActionUiKind.TOOLBAR);

        new SendViaSmtpAction().update(event);

        assertFalse(event.getPresentation().isEnabledAndVisible());
    }

    public void testActionVisibleWhenToolbarButtonDisabledButPlaceIsDifferent() throws Exception {
        service.setShowEditorToolbarButton(false);
        service.setEgressEnabled(true);
        var emlFile = myFixture.getTempDirFixture().createFile("ping.eml", "From: a@b.c\r\n\r\nbody\r\n");
        var event = buildEventWithPlace(emlFile, "MainMenu");

        new SendViaSmtpAction().update(event);

        assertTrue(event.getPresentation().isEnabledAndVisible());
    }

    private AnActionEvent buildEvent(@Nullable VirtualFile file) {
        return buildEventWithPlace(file, "test", com.intellij.openapi.actionSystem.ActionUiKind.NONE);
    }

    private AnActionEvent buildEventWithPlace(@Nullable VirtualFile file, String place) {
        return buildEventWithPlace(file, place, com.intellij.openapi.actionSystem.ActionUiKind.NONE);
    }

    private AnActionEvent buildEventWithPlace(
            @Nullable VirtualFile file, String place, com.intellij.openapi.actionSystem.ActionUiKind uiKind) {
        var context = file == null
                ? DataContext.EMPTY_CONTEXT
                : SimpleDataContext.builder()
                        .add(CommonDataKeys.VIRTUAL_FILE, file)
                        .build();
        var presentation = new Presentation();
        return AnActionEvent.createEvent(context, presentation, place, uiKind, null);
    }
}
