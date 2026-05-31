package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfile;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public void testOneTimePasswordIsUsedTransientlyAndNeverPersisted() throws Exception {
        // A profile that actually authenticates, so the one-time password has somewhere to flow.
        var profile = service.getProfiles().get(0);
        profile.authMechanism = SmtpProfile.AuthMechanismChoice.PLAIN;
        profile.username = "smtp-user";
        service.upsert(profile);
        var profileId = profile.identifier;

        var dialog = new SendDialog(getProject(), null);
        try {
            dialog.setEnvelopeForTest("from@example.com", "to@example.com");
            dialog.setOneTimePasswordForTest("one-time-pw");

            var request = dialog.buildSendRequest();

            // Transient: the typed password reaches THIS send's auth config…
            assertEquals(
                    "one-time-pw",
                    new String(request.config().auth().credentials().password().get()));
            // …but is never written back to PasswordSafe under the profile id (F11 regression).
            assertEquals(
                    "a one-time password must not be persisted to the credential store",
                    0,
                    dialog.credentialStore().passwordSupplier(profileId).get().length);
        } finally {
            dialog.close(0);
        }
    }

    public void testHeaderPreviewIsParsedFromBoundedPrefixOfALargeFile() throws Exception {
        // Manual-verification sample, ~192 KiB so its body runs well past the 64 KiB header-scan cap.
        var content = Files.readString(Path.of("src/test/resources/samples/eml/edge/large_body_header_scan.eml"));
        var file = myFixture.getTempDirFixture().createFile("large_body_header_scan.eml", content);

        var dialog = new SendDialog(getProject(), file);
        try {
            int retries = 50;
            while (retries-- > 0 && dialog.messageFromLabelText().isEmpty()) {
                Thread.sleep(100);
                com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
            }
            // The From/Subject preview must still be correct even though only a bounded prefix is read
            // on the EDT (F12 regression — the whole multi-MB body must not be pulled into memory).
            assertEquals("big-sender@example.com", dialog.messageFromLabelText());
            assertEquals("large body header scan", dialog.messageSubjectLabelText());
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
