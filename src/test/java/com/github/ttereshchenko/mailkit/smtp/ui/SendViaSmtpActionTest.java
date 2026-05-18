package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.Nullable;

public class SendViaSmtpActionTest extends BasePlatformTestCase {

    private SmtpProfileService service;
    private boolean originalEgress;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        service = SmtpProfileService.getInstance();
        originalEgress = service.isEgressEnabled();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            service.setEgressEnabled(originalEgress);
        } finally {
            super.tearDown();
        }
    }

    public void testActionHiddenWhenEgressDisabled() throws Exception {
        service.setEgressEnabled(false);
        var emlFile = myFixture.getTempDirFixture().createFile("ping.eml", "From: a@b.c\r\n\r\nbody\r\n");
        var event = buildEvent(dataId -> CommonDataKeys.VIRTUAL_FILE.is(dataId) ? emlFile : null);

        new SendViaSmtpAction().update(event);

        assertFalse(event.getPresentation().isEnabledAndVisible());
    }

    public void testActionHiddenForNonEmlFiles() throws Exception {
        service.setEgressEnabled(true);
        var txtFile = myFixture.getTempDirFixture().createFile("notes.txt", "plain text");
        var event = buildEvent(dataId -> CommonDataKeys.VIRTUAL_FILE.is(dataId) ? txtFile : null);

        new SendViaSmtpAction().update(event);

        assertFalse(event.getPresentation().isEnabledAndVisible());
    }

    public void testActionVisibleForEmlFileWithEgressEnabled() throws Exception {
        service.setEgressEnabled(true);
        var emlFile = myFixture.getTempDirFixture().createFile("send-me.eml", "From: a@b.c\r\n\r\nbody\r\n");
        var event = buildEvent(dataId -> CommonDataKeys.VIRTUAL_FILE.is(dataId) ? emlFile : null);

        new SendViaSmtpAction().update(event);

        assertTrue(event.getPresentation().isEnabledAndVisible());
    }

    private AnActionEvent buildEvent(@Nullable DataContextLambda lambda) {
        DataContext context = dataId -> lambda == null ? null : lambda.get(dataId);
        var presentation = new Presentation();
        return AnActionEvent.createEvent(
                context, presentation, "test", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null);
    }

    @FunctionalInterface
    private interface DataContextLambda {
        Object get(String dataId);
    }
}
