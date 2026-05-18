package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfile;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.ArrayList;
import java.util.List;

public class SendDialogTest extends BasePlatformTestCase {

    private SmtpProfileService service;
    private List<SmtpProfile> originalProfiles;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        service = SmtpProfileService.getInstance();
        originalProfiles = service.getProfiles();
        service.setProfiles(new ArrayList<>());
        var profile = new SmtpProfile();
        profile.name = "Mailpit-dev";
        profile.host = "localhost";
        profile.port = 1025;
        service.upsert(profile);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            service.setProfiles(originalProfiles);
        } finally {
            super.tearDown();
        }
    }

    public void testSendButtonIsNeverTheDefaultFocusedButton() {
        var dialog = new SendDialog(getProject(), null);
        try {
            assertFalse(
                    "OK (Send) action must NOT be flagged as the default keyboard action",
                    dialog.isOkActionMarkedAsDefault());
        } finally {
            dialog.close(0);
        }
    }

    public void testDialogInstantiatesWithoutAFileWhenNoSourceProvided() {
        // Constructor calling init() must complete without throwing — full rendering only happens
        // on show(), which the headless test framework does not drive.
        var dialog = new SendDialog(getProject(), null);
        try {
            assertEquals("Send EML", dialog.getTitle());
        } finally {
            dialog.close(0);
        }
    }

    public void testDialogInstantiatesWithAnEmlFileSource() throws Exception {
        var emlText = "From: sender@example.com\r\nTo: recipient@example.com\r\nSubject: hello\r\n\r\nbody\r\n";
        var file = myFixture.getTempDirFixture().createFile("envelope.eml", emlText);
        var dialog = new SendDialog(getProject(), file);
        try {
            assertEquals("Send EML", dialog.getTitle());
            assertFalse(
                    "OK (Send) action must NOT be flagged as the default keyboard action",
                    dialog.isOkActionMarkedAsDefault());
        } finally {
            dialog.close(0);
        }
    }
}
