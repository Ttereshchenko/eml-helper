package com.github.ttereshchenko.mailkit.smtp.ui;

import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfile;
import com.github.ttereshchenko.mailkit.smtp.profile.SmtpProfileService;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.charset.StandardCharsets;
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

    public void testSendDialogIsNonModalSoTheSendDoesNotFreezeTheIde() {
        // The dialog runs the send on a pooled thread and shows live progress; a modal dialog would
        // freeze the whole IDE while it stays open and suppress the result notification balloon until
        // the dialog is closed (the reported "window frozen, notification only after close" defect).
        var dialog = new SendDialog(getProject(), (VirtualFile) null);
        try {
            assertFalse("the Send EML dialog must be non-modal", dialog.isModal());
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

    public void testDisplayNameAndBareAddressesAreReducedToBareAddrSpecsInTheEnvelope() throws Exception {
        // Manual-verification sample: a display-name From and a To list whose first entry has a
        // quoted comma. rfc5321 §4.1.2 — the envelope wants the bare addr-spec, not the rfc5322
        // mailbox with a display name. Old behavior passed "Alice <alice@…>" straight to
        // SmtpEnvelope.of, whose requireSafeAddress rejected '<', '>', and spaces.
        var fixture = "src/test/resources/samples/eml/edge/display_name_envelope.eml";
        var from = headerValueOf(fixture, "From");
        var toHeader = headerValueOf(fixture, "To");
        assertEquals("Alice Example <alice@example.com>", from);
        assertEquals("\"Doe, John\" <john@example.com>, bob@example.com", toHeader);

        var dialog = new SendDialog(getProject(), (VirtualFile) null);
        try {
            dialog.setEnvelopeForTest(from, toHeader);

            var request = dialog.buildSendRequest();

            assertEquals(
                    "from is reduced to its bare addr-spec",
                    "alice@example.com",
                    request.envelope().mailFrom());
            // rfc5322 §3.4 — the quoted comma must NOT split the address list, so there are exactly
            // two recipients, each a bare addr-spec.
            assertEquals(List.of("john@example.com", "bob@example.com"), request.recipients());
        } finally {
            dialog.close(0);
        }
    }

    public void testGroupSyntaxAndCommentsReduceToBareAddrSpecs() throws Exception {
        // rfc5322 §3.4 group syntax ("Team: a, b;") and §3.2.2 CFWS comments are legitimate address
        // forms. The old extractor passed "Team: alice@…" and "dave@… (work)" to the envelope, whose
        // requireSafeAddress rejected the space/colon — so a valid To header failed to send.
        var dialog = new SendDialog(getProject(), (VirtualFile) null);
        try {
            dialog.setEnvelopeForTest(
                    "carol@example.com (Carol)", "Team: alice@example.com, bob@example.com;, dave@example.com (work)");

            var request = dialog.buildSendRequest();

            assertEquals("carol@example.com", request.envelope().mailFrom());
            assertEquals(List.of("alice@example.com", "bob@example.com", "dave@example.com"), request.recipients());
        } finally {
            dialog.close(0);
        }
    }

    public void testMultipleDisplayNameRecipientsEachYieldOneBareAddrSpec() throws Exception {
        // Several "Name <addr>" entries — the classic thing pasted from an EML To header.
        var dialog = new SendDialog(getProject(), (VirtualFile) null);
        try {
            dialog.setEnvelopeForTest(
                    "Carol <carol@example.com>", "Alice <alice@example.com>, Bob B. <bob@example.com>");

            var request = dialog.buildSendRequest();

            assertEquals("carol@example.com", request.envelope().mailFrom());
            assertEquals(List.of("alice@example.com", "bob@example.com"), request.recipients());
        } finally {
            dialog.close(0);
        }
    }

    public void testBccHeaderIsRecognizedFoldedAndCaseInsensitivelyAndStrippedFromData() throws Exception {
        // Manual-verification sample: a folded, mixed-case (BCC:) Bcc field. rfc5322 §3.6.3 / §5.3 —
        // the Bcc field must not be transmitted; rfc5322 §1.2.2 — field names are case-insensitive;
        // rfc5322 §2.2.3 — a continuation line begins with SP/HTAB, so the folded value goes too.
        var bytes = Files.readAllBytes(Path.of("src/test/resources/samples/eml/edge/bcc_disclosure.eml"));

        var result = SendDialog.stripBccHeader(bytes);

        assertTrue("a Bcc field must be detected (this is what triggers the user warning)", result.changed());
        // The folded field is unfolded and parsed into bare addr-specs (no trailing comma, one entry
        // per address) so the warning lists real addresses rather than raw line fragments.
        assertEquals(List.of("secret-one@example.com", "secret-two@example.com"), result.removedAddresses());
        var data = new String(result.data(), StandardCharsets.UTF_8);
        assertFalse("the Bcc field name must be gone from DATA", data.contains("BCC:"));
        assertFalse(data.contains("secret-one@example.com"));
        assertFalse(data.contains("secret-two@example.com"));
        assertTrue("the visible header survives", data.contains("To: visible@example.com"));
        assertTrue("the From header survives", data.contains("From: sender@example.com"));
        // Dropping the field (and its folded continuation) must not leave a stray blank line.
        assertFalse("no blank line where the Bcc field was", data.contains("\r\n\r\n\r\n"));
    }

    public void testStripBccHeaderLeavesABodyLineThatLooksLikeBccUntouched() {
        // Only the header section is scanned (rfc5322 §2.1) — a body line that merely starts with
        // "Bcc:" must be left intact.
        var message = "From: a@example.com\r\nTo: b@example.com\r\n\r\nBcc: not-a-header@example.com\r\n";

        var result = SendDialog.stripBccHeader(message.getBytes(StandardCharsets.UTF_8));

        assertFalse("a body line is not a header — nothing to strip", result.changed());
        assertEquals(message, new String(result.data(), StandardCharsets.UTF_8));
    }

    /** Reads one top-level (un-folded) header value from a sample EML on disk. */
    private static String headerValueOf(String fixturePath, String fieldName) throws Exception {
        for (var line : Files.readString(Path.of(fixturePath)).split("\r?\n")) {
            var colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(fieldName)) {
                return line.substring(colon + 1).trim();
            }
        }
        throw new AssertionError("no " + fieldName + " header in " + fixturePath);
    }

    private List<VirtualFile> createBatchSampleFiles() throws Exception {
        var sampleOne = Files.readString(Path.of("src/test/resources/samples/eml/smtp/batch_send_message_one.eml"));
        var sampleTwo = Files.readString(Path.of("src/test/resources/samples/eml/smtp/batch_send_message_two.eml"));
        return List.of(
                myFixture.getTempDirFixture().createFile("batch_send_message_one.eml", sampleOne),
                myFixture.getTempDirFixture().createFile("batch_send_message_two.eml", sampleTwo));
    }
}
