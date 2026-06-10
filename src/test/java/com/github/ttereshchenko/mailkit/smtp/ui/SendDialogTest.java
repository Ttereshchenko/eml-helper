package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfile;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.openapi.vfs.VirtualFile;
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
        var dialog = new SendDialog(getProject(), (VirtualFile) null);
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
        var dialog = new SendDialog(getProject(), (VirtualFile) null);
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

        var dialog = new SendDialog(getProject(), (VirtualFile) null);
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
            assertTrue("a single-file dialog keeps the From/Subject preview", dialog.hasHeaderPreviewRows());
            assertEquals(1, dialog.statusTableModel().getRowCount());
        } finally {
            dialog.close(0);
        }
    }

    public void testMultiFileDialogShowsAPendingRowPerFileAndSkipsHeaderPreview() throws Exception {
        var dialog = new SendDialog(getProject(), createBatchSampleFiles());
        try {
            assertEquals("Send 2 EML Files", dialog.getTitle());
            assertFalse("multi-file dialogs have no per-message header preview", dialog.hasHeaderPreviewRows());
            var model = dialog.statusTableModel();
            assertEquals(2, model.getRowCount());
            assertEquals(BatchSendController.FileStatus.PENDING, model.statusAt(0));
            assertEquals(BatchSendController.FileStatus.PENDING, model.statusAt(1));
        } finally {
            dialog.close(0);
        }
    }

    public void testFailurePolicyDefaultsToContinueAndIsCarriedIntoTheRequest() throws Exception {
        var files = createBatchSampleFiles();
        var dialog = new SendDialog(getProject(), files);
        try {
            assertEquals(SendDialog.FailurePolicy.CONTINUE_ON_FAILURE, dialog.selectedFailurePolicy());
            dialog.setEnvelopeForTest("from@example.com", "to@example.com");

            var request = dialog.buildSendRequest();

            assertEquals(files, request.sourceFiles());
            assertEquals(SendDialog.FailurePolicy.CONTINUE_ON_FAILURE, request.failurePolicy());
        } finally {
            dialog.close(0);
        }
    }

    public void testUpdateProfileCheckboxIsHiddenWhileFieldsMatchTheProfile() {
        var dialog = new SendDialog(getProject(), (VirtualFile) null);
        try {
            var checkbox = dialog.updateProfileBoxForTest();
            assertFalse("checkbox must stay hidden while fields match the profile", checkbox.isVisible());
            assertFalse("checkbox must start unchecked", checkbox.isSelected());
        } finally {
            dialog.close(0);
        }
    }

    public void testUpdateProfileCheckboxAppearsOnOverrideAndHidesWhenValuesAreRestored() {
        var dialog = new SendDialog(getProject(), (VirtualFile) null);
        try {
            var checkbox = dialog.updateProfileBoxForTest();
            dialog.setHostPortForTest("smtp.new-env.local", 2525);
            assertTrue("checkbox must appear once host/port diverge from the profile", checkbox.isVisible());
            assertEquals("Update profile \"Mailpit-dev\" with these values", checkbox.getText());

            checkbox.setSelected(true);
            dialog.setHostPortForTest("localhost", 1025);
            assertFalse("checkbox must hide again when the fields match the profile", checkbox.isVisible());
            assertFalse("checkbox must uncheck when it hides, so a stale opt-in cannot linger", checkbox.isSelected());
        } finally {
            dialog.close(0);
        }
    }

    public void testCheckedUpdateBoxPersistsOverriddenValuesToTheSameProfile() throws Exception {
        // Manual-verification sample for this feature.
        var sample = Files.readString(Path.of("src/test/resources/samples/eml/smtp/save_overrides_to_profile.eml"));
        var file = myFixture.getTempDirFixture().createFile("save_overrides_to_profile.eml", sample);
        var originalIdentifier = service.getProfiles().get(0).identifier;

        var dialog = new SendDialog(getProject(), file);
        try {
            dialog.setHostPortForTest("smtp.new-env.local", 2525);
            dialog.setEnvelopeForTest("ci@new-env.local", "qa@new-env.local");
            dialog.updateProfileBoxForTest().setSelected(true);
            // Mirror the production order: validation first, then the opt-in save.
            dialog.buildSendRequest();

            dialog.persistOverridesForTest();

            var saved = service.findById(originalIdentifier).orElseThrow();
            assertEquals("smtp.new-env.local", saved.host);
            assertEquals(2525, saved.port);
            assertEquals("ci@new-env.local", saved.findDefaultHeaderValue("From"));
            assertEquals("qa@new-env.local", saved.findDefaultHeaderValue("To"));
            assertEquals("Mailpit-dev", saved.name);
            assertTrue("the default flag must survive the update", saved.isDefault);
            assertEquals(
                    "the update must not duplicate the profile",
                    1,
                    service.getProfiles().size());
            assertFalse(
                    "after saving, the fields match the profile again so the checkbox hides",
                    dialog.updateProfileBoxForTest().isVisible());
        } finally {
            dialog.close(0);
        }
    }

    public void testUncheckedUpdateBoxLeavesTheProfileUntouched() throws Exception {
        var originalIdentifier = service.getProfiles().get(0).identifier;

        var dialog = new SendDialog(getProject(), (VirtualFile) null);
        try {
            dialog.setHostPortForTest("smtp.new-env.local", 2525);
            dialog.setEnvelopeForTest("ci@new-env.local", "qa@new-env.local");
            assertTrue(dialog.updateProfileBoxForTest().isVisible());

            dialog.persistOverridesForTest();

            var untouched = service.findById(originalIdentifier).orElseThrow();
            assertEquals("localhost", untouched.host);
            assertEquals(1025, untouched.port);
            assertEquals("", untouched.findDefaultHeaderValue("From"));
            assertEquals("", untouched.findDefaultHeaderValue("To"));
        } finally {
            dialog.close(0);
        }
    }

    private List<VirtualFile> createBatchSampleFiles() throws Exception {
        var sampleOne = Files.readString(Path.of("src/test/resources/samples/eml/smtp/batch_send_message_one.eml"));
        var sampleTwo = Files.readString(Path.of("src/test/resources/samples/eml/smtp/batch_send_message_two.eml"));
        return List.of(
                myFixture.getTempDirFixture().createFile("batch_send_message_one.eml", sampleOne),
                myFixture.getTempDirFixture().createFile("batch_send_message_two.eml", sampleTwo));
    }
}
