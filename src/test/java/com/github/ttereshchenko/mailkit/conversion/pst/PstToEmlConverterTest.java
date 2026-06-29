package com.github.ttereshchenko.mailkit.conversion.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.conversion.EmlSerializer;
import com.github.ttereshchenko.mailkit.conversion.ICalendarGenerator;
import com.github.ttereshchenko.mailkit.pst.Attachment;
import com.github.ttereshchenko.mailkit.pst.MapiProperties;
import com.github.ttereshchenko.mailkit.pst.Message;
import com.github.ttereshchenko.mailkit.pst.NodeEntry;
import com.github.ttereshchenko.mailkit.pst.PstFile;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PstToEmlConverterTest {

    private static final Path SAMPLE = Paths.get("src/test/resources/samples/pst/tika-testPST.pst");

    private static PstToEmlConverter.Options defaultOptions() {
        return new PstToEmlConverter.Options(
                PstToEmlConverter.DuplicateHandling.OVERWRITE,
                null,
                false,
                true,
                Message.AddressPreference.PREFER_SMTP,
                false,
                false,
                64L * 1024 * 1024);
    }

    // SEC-1: a PST folder named "." or ".." must not escape the target directory.
    @Test
    void testSafeSegmentRejectsTraversal() {
        assertEquals("Folder_5", PstToEmlConverter.safeSegment("..", 5));
        assertEquals("Folder_6", PstToEmlConverter.safeSegment(".", 6));
        assertEquals("Folder_7", PstToEmlConverter.safeSegment("   ", 7));
        assertEquals("Folder_8", PstToEmlConverter.safeSegment(null, 8));
        // Embedded separators are sanitized but the name is otherwise preserved.
        assertEquals("a_b", PstToEmlConverter.safeSegment("a/b", 9));
        assertEquals(".._x", PstToEmlConverter.safeSegment("../x", 10));
        assertEquals("Inbox", PstToEmlConverter.safeSegment("Inbox", 11));
    }

    // FIDEL-1: when the recipient table is empty, To/Cc fall back to the display strings.
    @Test
    void testRecipientFallbackToDisplayStrings() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Subject";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public String getSenderName() {
                    return "Sender";
                }

                @Override
                public String getSenderEmail() {
                    return "sender@example.com";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getTo() {
                    return "Alice; Bob";
                }

                @Override
                public String getDisplayCc() {
                    return "Carol";
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("To:"), "To header should be present from the display-to fallback");
            assertTrue(eml.contains("Alice"), "Alice should appear in the To header");
            assertTrue(eml.contains("Bob"), "Bob should appear in the To header");
            assertTrue(eml.contains("Cc:") && eml.contains("Carol"), "Cc should come from the display-cc fallback");
        }
    }

    // Audit: the display-string fallback is per recipient type, not all-or-nothing — a table that carries
    // a usable To but only a display-string Cc must still emit the Cc (parity with the MSG path). The
    // pre-fix code gated the whole fallback on an *empty* table, so this Cc was silently dropped.
    @Test
    void partialRecipientTableStillFallsBackToDisplayCc() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Subject";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public String getSenderName() {
                    return "Sender";
                }

                @Override
                public String getSenderEmail() {
                    return "sender@example.com";
                }

                @Override
                public List<Recipient> getRecipients() {
                    // A usable To recipient — the structured table is non-empty.
                    return List.of(new Recipient(1, "Alice", "alice@example.com"));
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getDisplayCc() {
                    return "Carol";
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("alice@example.com"), "the structured To recipient must be present: " + eml);
            assertTrue(
                    eml.contains("Cc:") && eml.contains("Carol"),
                    "a display-only Cc must still fall back per type even when the To table is populated: " + eml);
        }
    }

    // PATH-1 (#4): the message filename trims the subject so the full path stays within MAX_PATH,
    // while always keeping the unique "_<nid>.eml" suffix.
    @Test
    void boundedEmlFileNameKeepsPathWithinMaxPathAndPreservesNid() {
        var shallow = Paths.get("out");
        assertEquals("Hello_42.eml", PstToEmlConverter.boundedEmlFileName(shallow, "Hello", 42));

        var deepDir = Paths.get("a".repeat(200));
        var fileName = PstToEmlConverter.boundedEmlFileName(deepDir, "x".repeat(300), 7);
        assertTrue(fileName.endsWith("_7.eml"), "unique nid suffix preserved: " + fileName);
        assertTrue(
                deepDir.toString().length() + 1 + fileName.length() <= 255,
                "full path must fit within MAX_PATH: " + fileName);
        assertTrue(fileName.length() < ("x".repeat(300) + "_7.eml").length(), "subject must be trimmed");
    }

    // DEDUP-1 (#5): repeated folder-name collisions resume from a cached counter (_2, _3, ...) rather
    // than re-probing from "_2" each time.
    @Test
    void uniqueDirectoryResumesFromCachedCounter() throws Exception {
        var parent = java.nio.file.Files.createTempDirectory("pst_dedup");
        try {
            var counters = new java.util.HashMap<Path, Integer>();
            var first = PstToEmlConverter.uniqueDirectory(parent, "Inbox", counters);
            java.nio.file.Files.createDirectory(first);
            var second = PstToEmlConverter.uniqueDirectory(parent, "Inbox", counters);
            java.nio.file.Files.createDirectory(second);
            var third = PstToEmlConverter.uniqueDirectory(parent, "Inbox", counters);

            assertEquals(parent.resolve("Inbox"), first);
            assertEquals(parent.resolve("Inbox_2"), second);
            assertEquals(parent.resolve("Inbox_3"), third);
            assertEquals(3, counters.get(parent.resolve("Inbox")), "counter advanced; no rescan from _2");
        } finally {
            try (var stream = java.nio.file.Files.walk(parent)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    // F8 regression: a subject-less message used to gain a fabricated "Subject: No Subject" header
    // (and the synthesized-headers disclosure claimed it came from the message).
    @Test
    void blankSubjectProducesNoSubjectHeader() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertFalse(eml.startsWith("Subject:") || eml.contains("\r\nSubject:"), "no fabricated subject: " + eml);
            assertFalse(eml.contains("No Subject"), eml);
        }
    }

    // F9: threading and importance metadata stored in the PST surfaces as the standard headers.
    @Test
    void threadingAndImportanceHeadersAreExported() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Threaded";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getStringProperty(int propertyId) {
                    if (propertyId == MapiProperties.PR_IN_REPLY_TO_ID_W) {
                        return "<parent@example.com>";
                    }
                    if (propertyId == MapiProperties.PR_INTERNET_REFERENCES_W) {
                        return "<root@example.com> <parent@example.com>";
                    }
                    return null;
                }

                @Override
                public Object getProperty(int propertyId) {
                    return propertyId == MapiProperties.PR_IMPORTANCE ? 2 : null;
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("In-Reply-To: <parent@example.com>"), eml);
            assertTrue(eml.contains("References: <root@example.com> <parent@example.com>"), eml);
            assertTrue(eml.contains("Importance: High"), eml);
            assertTrue(eml.contains("X-Priority: 1"), eml);
        }
    }

    // F9: a message sent on behalf of someone else maps the author to From: and the actual
    // transmitter to Sender: (RFC 5322 §3.6.2).
    @Test
    void onBehalfOfEmitsFromAuthorAndSenderTransmitter() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "On behalf";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getSenderName() {
                    return "Assistant";
                }

                @Override
                public String getSenderEmail() {
                    return "assistant@example.com";
                }

                @Override
                public String getSentRepresentingName() {
                    return "Boss";
                }

                @Override
                public String getSentRepresentingEmail() {
                    return "boss@example.com";
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("From: \"Boss\" <boss@example.com>"), eml);
            assertTrue(eml.contains("Sender: \"Assistant\" <assistant@example.com>"), eml);
        }
    }

    // P2: when PR_SENDER_EMAIL is blank but the sent-representing author has an address, the author is
    // promoted to From: rather than dropped to the undisclosed placeholder (RFC 5322 §3.6.2). No Sender:
    // is emitted because there is no distinct transmitter address.
    @Test
    void blankSenderEmailPromotesSentRepresentingAuthorToFrom() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Author only";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getSenderName() {
                    return "";
                }

                @Override
                public String getSenderEmail() {
                    return "";
                }

                @Override
                public String getSentRepresentingName() {
                    return "Boss";
                }

                @Override
                public String getSentRepresentingEmail() {
                    return "boss@example.com";
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("From: \"Boss\" <boss@example.com>"), eml);
            assertFalse(eml.contains("Sender:"), "no Sender without a distinct transmitter address: " + eml);
        }
    }

    // P2b: a delegated/draft item whose PR_SENDER_NAME is absent but whose sender address was filled from
    // the sent-representing fallback in Message#resolveSenderEmail (so it equals the author address) must
    // keep the represented author's display name paired with that address (RFC 5322 §3.6.2), not emit an
    // address-only From:. Matches the MSG path, which always pairs From's name and address from one identity.
    @Test
    void blankSenderNameWithBorrowedRepresentingAddressKeepsAuthorDisplayName() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Author only";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getSenderName() {
                    return "";
                }

                @Override
                public String getSenderEmail() {
                    // resolveSenderEmail falls back to PR_SENT_REPRESENTING_* when the sender's own
                    // address is absent, so the resolved sender address equals the author's here.
                    return "boss@example.com";
                }

                @Override
                public String getSentRepresentingName() {
                    return "Boss";
                }

                @Override
                public String getSentRepresentingEmail() {
                    return "boss@example.com";
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("From: \"Boss\" <boss@example.com>"),
                    "the represented author's display name must pair with the borrowed address: " + eml);
            assertFalse(eml.contains("Sender:"), "no Sender without a distinct transmitter address: " + eml);
        }
    }

    // P3: PR_RECIPIENT_TYPE may carry high-order flag bits (e.g. 0x10000001 on a resent/saved item).
    // After masking to the class bits ([MS-OXOMSG] §2.2.3.1) the recipient still classifies as To and
    // keeps its address, instead of matching no class and being silently dropped.
    @Test
    void recipientTypeHighFlagBitsStillClassifyAsTo() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Flagged recipient";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of(new Recipient(0x10000001, "Bob", "bob@example.com"));
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("To: \"Bob\" <bob@example.com>"), "flagged To recipient must survive: " + eml);
        }
    }

    // P4: PR_READ_RECEIPT_REQUESTED surfaces as Disposition-Notification-To addressed to the From author
    // (rfc8098), matching the MSG path.
    @Test
    void readReceiptRequestedEmitsDispositionNotificationTo() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Receipt please";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getSenderName() {
                    return "Alice";
                }

                @Override
                public String getSenderEmail() {
                    return "alice@example.com";
                }

                @Override
                public Object getProperty(int propertyId) {
                    return propertyId == MapiProperties.PR_READ_RECEIPT_REQUESTED ? Boolean.TRUE : null;
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("Disposition-Notification-To: \"Alice\" <alice@example.com>"),
                    "read-receipt request must emit Disposition-Notification-To: " + eml);
        }
    }

    // P4: categories (PidNameKeywords, a string-named PS_PUBLIC_STRINGS property) surface as the Keywords
    // header, matching the MSG path.
    @Test
    void categoriesEmitAsKeywordsHeader() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var psPublicStrings = UUID.fromString("00020329-0000-0000-C000-000000000046");
            var keywordsId = pstFile.namedPropertyId(psPublicStrings, "Keywords");
            org.junit.jupiter.api.Assertions.assertNotNull(
                    keywordsId, "sample PST must register the Keywords named property for this test");
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Categorized";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public Object getProperty(int propertyId) {
                    return propertyId == keywordsId ? List.of("Red", "Blue") : null;
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("Keywords: Red, Blue"), "categories must surface as the Keywords header: " + eml);
        }
    }

    // M4: a REPORT.* class that is neither a delivery report (.NDR/.DR) nor a read/non-read receipt
    // (.IPNRN/.IPNNRN) must not be emitted as a disposition-notification claiming the message was
    // "displayed" (rfc8098 §3.2.6) from the PST path either; it falls back to the generic body.
    @Test
    void unrecognizedReportClassFallsBackToBody() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "REPORT.IPM.Note.Delayed";
                }

                @Override
                public String getSubject() {
                    return "Delivery delayed";
                }

                @Override
                public String getBody() {
                    return "Your message has been delayed.";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertFalse(eml.contains("disposition-notification"), "non-receipt report must not become an MDN: " + eml);
            assertFalse(eml.contains("Disposition:"), "must not fabricate a disposition: " + eml);
            assertTrue(eml.contains("Your message has been delayed."), "falls back to the generic body: " + eml);
        }
    }

    // F18 regression: a genuine RTF body used to be emitted as a text/rtf; charset=UTF-8
    // multipart/alternative sibling no client can render; it is now preserved as an
    // application/rtf attachment carrying the original windows-1252 bytes.
    @Test
    void rtfBodyBecomesApplicationRtfAttachment() throws Exception {
        var rtf = "{\\rtf1 caf\\'e9}";
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Rtf";
                }

                @Override
                public String getBody() {
                    return "Plain";
                }

                @Override
                public String getRtfBody() {
                    return rtf;
                }

                @Override
                public byte[] getRawRtfBytes() {
                    return rtf.getBytes(Charset.forName("windows-1252"));
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertFalse(eml.contains("text/rtf"), "RTF must not be an alternative body: " + eml);
            assertTrue(eml.contains("application/rtf"), eml);
            assertTrue(eml.contains("body.rtf"), eml);

            var matcher = Pattern.compile("(?is)application/rtf.*?\\r\\n\\r\\n(.*?)\\r\\n--")
                    .matcher(eml);
            assertTrue(matcher.find(), eml);
            var decoded = Base64.getMimeDecoder().decode(matcher.group(1));
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    rtf.getBytes(Charset.forName("windows-1252")),
                    decoded,
                    "the attachment must carry the original RTF bytes");
        }
    }

    // F2: the iTIP method follows the message class, downgrading to PUBLISH whenever no attendee
    // is available (REQUEST/CANCEL/REPLY are invalid without one).
    @Test
    void icalMethodFollowsMessageClassAndAttendees() {
        assertEquals("PUBLISH", ICalendarGenerator.method("IPM.Appointment", true));
        assertEquals("PUBLISH", ICalendarGenerator.method("IPM.Appointment", false));
        assertEquals("PUBLISH", ICalendarGenerator.method("IPM.Schedule.Meeting.Request", false));
        assertEquals("REQUEST", ICalendarGenerator.method("IPM.Schedule.Meeting.Request", true));
        assertEquals("CANCEL", ICalendarGenerator.method("IPM.Schedule.Meeting.Canceled", true));
        assertEquals("REPLY", ICalendarGenerator.method("IPM.Schedule.Meeting.Resp.Pos", true));
    }

    // F19: an appointment whose store carries no start time gets no invite at all instead of one
    // fabricated at conversion time.
    @Test
    void appointmentWithoutStartTimeGetsNoInvite() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Appointment";
                }

                @Override
                public String getSubject() {
                    return "Timeless";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public Object getProperty(int propertyId) {
                    return null;
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertFalse(eml.contains("invite.ics"), "no invite may be fabricated without a start time: " + eml);
            assertFalse(eml.contains("text/calendar"), eml);
        }
    }

    // RECOVERY-1 (#7/#8): NBT message nodes the folder walk did not export are classified as
    // soft-deleted (parent folder was visited) vs orphaned (parent outside the walked tree).
    @Test
    void findUnreferencedMessagesClassifiesByParent() {
        var folderNid = 0x122; // a folder the walk "visited"
        var knownMessage = 0x200004; // type 0x04, already exported by the walk
        var dumpsterMessage = 0x200024; // type 0x04, parent is a visited folder, not known -> soft-deleted
        var orphanMessage = 0x200044; // type 0x04, parent is outside the tree -> orphan
        var folderNode = 0x200002; // type 0x02 (a folder) -> never a message candidate

        var nodes = new java.util.HashMap<Integer, NodeEntry>();
        nodes.put(knownMessage, new NodeEntry(knownMessage, 1, 0, folderNid));
        nodes.put(dumpsterMessage, new NodeEntry(dumpsterMessage, 2, 0, folderNid));
        nodes.put(orphanMessage, new NodeEntry(orphanMessage, 3, 0, 0x999999));
        nodes.put(folderNode, new NodeEntry(folderNode, 4, 0, folderNid));

        var candidates = PstToEmlConverter.findUnreferencedMessages(
                nodes, java.util.Set.of(knownMessage), java.util.Set.of(folderNid));

        assertEquals(2, candidates.size(), candidates.toString());
        var byNid = candidates.stream()
                .collect(java.util.stream.Collectors.toMap(
                        PstToEmlConverter.RecoveryCandidate::nid, java.util.function.Function.identity()));
        assertTrue(byNid.get(dumpsterMessage).fromVisitedFolder(), "parent folder was visited -> dumpster");
        assertFalse(byNid.get(orphanMessage).fromVisitedFolder(), "parent outside the tree -> orphan");
    }

    @Test
    void findUnreferencedMessagesReturnsDeterministicNidOrder() {
        var folderNid = 0x122;
        // Insert the message nodes in descending NID order. getAllNodes() hands back a HashMap snapshot
        // whose iteration order is undefined, so recovery must sort by NID — otherwise which messages
        // survive a limit, and the _N suffixes they receive, would vary run-to-run.
        var nodes = new java.util.LinkedHashMap<Integer, NodeEntry>();
        nodes.put(0x600004, new NodeEntry(0x600004, 1, 0, folderNid));
        nodes.put(0x400004, new NodeEntry(0x400004, 2, 0, folderNid));
        nodes.put(0x500004, new NodeEntry(0x500004, 3, 0, folderNid));
        nodes.put(0x300004, new NodeEntry(0x300004, 4, 0, folderNid));

        var candidates =
                PstToEmlConverter.findUnreferencedMessages(nodes, java.util.Set.of(), java.util.Set.of(folderNid));

        var nids = candidates.stream()
                .map(PstToEmlConverter.RecoveryCandidate::nid)
                .toList();
        assertEquals(
                java.util.List.of(0x300004, 0x400004, 0x500004, 0x600004),
                nids,
                "recovery order must be ascending NID");
    }

    /** Collects console output so tests can assert on conversion diagnostics. */
    private static final class RecordingLog implements ConversionLog {
        final List<String> infos = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }
    }

    /** An embedded-message attachment (afEmbeddedMessage) carrying only a display name. */
    private static final class EmbeddedAttachmentStub extends Attachment {
        private final String displayName;

        EmbeddedAttachmentStub(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String getLongFilename() {
            return "";
        }

        @Override
        public String getFilename() {
            return "";
        }

        @Override
        public String getExtension() {
            return "";
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public int getAttachMethod() {
            return 5; // afEmbeddedMessage
        }
    }

    /** A binary attachment whose stored content is absent (optionally with a recorded size). */
    private static final class ContentlessAttachmentStub extends Attachment {
        private final int attachMethod;
        private final Long size;

        ContentlessAttachmentStub(int attachMethod) {
            this(attachMethod, null);
        }

        ContentlessAttachmentStub(int attachMethod, Long size) {
            this.attachMethod = attachMethod;
            this.size = size;
        }

        @Override
        public String getLongFilename() {
            return "report.pdf";
        }

        @Override
        public String getFilename() {
            return "";
        }

        @Override
        public int getAttachMethod() {
            return attachMethod;
        }

        @Override
        public byte[] getData() {
            return null;
        }

        @Override
        public Long getSize() {
            return size;
        }
    }

    /**
     * A bracketed PR_ATTACH_CONTENT_ID must still match the bracket-less {@code cid:} reference in the
     * HTML body and stay in the multipart/related subtree — parity with the MSG driver. Before the fix
     * the raw {@code "<logo@x>"} missed the match in {@code htmlBodyReferences} and the image was
     * demoted to a plain mixed attachment (its inline rendering broken).
     */
    @Test
    void bracketedAttachContentIdStillResolvesInlineImage() throws Exception {
        var inlineImage = new Attachment() {
            @Override
            public String getLongFilename() {
                return "logo.png";
            }

            @Override
            public String getFilename() {
                return "";
            }

            @Override
            public int getAttachMethod() {
                return 1; // afByValue
            }

            @Override
            public byte[] getData() {
                return new byte[] {1, 2, 3};
            }

            @Override
            public String getMimeTag() {
                return "image/png";
            }

            @Override
            public String getContentId() {
                return "<logo@x>"; // stored WITH angle brackets, contrary to [MS-OXCMSG] §2.2.2.5
            }

            @Override
            public String getContentLocation() {
                return null;
            }

            @Override
            public boolean isInline() {
                return true;
            }
        };
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(pstFile, "Inline image, bracketed cid", List.of(inlineImage), null, "") {
                @Override
                public String getHtmlBody() {
                    return "<img src=\"cid:logo@x\">";
                }
            };
            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("Content-ID: <logo@x>"), eml);
            assertTrue(
                    eml.contains("multipart/related"),
                    "a bracketed Content-ID must still match the cid: reference and stay inline: " + eml);
        }
    }

    /**
     * A cid-referenced inline image whose MAPI flags do not mark it inline (no inline disposition, not
     * hidden, no ATT_MHTML_REF/ATT_INVISIBLE_IN_HTML) — a common shape when another MUA produced the
     * MIME — must still get {@code Content-Disposition: inline}, matching the MSG driver (which keys the
     * inline flag off Content-ID presence). Before the fix PstToEmlConverter passed only
     * {@code Attachment.isInline()}, so the part landed in multipart/related but with
     * {@code Content-Disposition: attachment} (clients both render it and list it as a download).
     */
    @Test
    void cidReferencedImageWithoutInlineFlagsStillGetsInlineDisposition() throws Exception {
        var inlineImage = new Attachment() {
            @Override
            public String getLongFilename() {
                return "logo.png";
            }

            @Override
            public String getFilename() {
                return "";
            }

            @Override
            public int getAttachMethod() {
                return 1; // afByValue
            }

            @Override
            public byte[] getData() {
                return new byte[] {1, 2, 3};
            }

            @Override
            public String getMimeTag() {
                return "image/png";
            }

            @Override
            public String getContentId() {
                return "logo@x";
            }

            @Override
            public String getContentLocation() {
                return null;
            }

            @Override
            public boolean isInline() {
                return false; // MAPI flags do not mark it inline; only the cid reference does
            }
        };
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(pstFile, "Inline image, flags unset", List.of(inlineImage), null, "") {
                @Override
                public String getHtmlBody() {
                    return "<img src=\"cid:logo@x\">";
                }
            };
            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("multipart/related"), eml);
            assertTrue(
                    eml.contains("Content-Disposition: inline"),
                    "a cid-referenced inline image must get Content-Disposition: inline like MSG: " + eml);
            assertFalse(
                    eml.contains("Content-Disposition: attachment"),
                    "the referenced inline image must not be demoted to attachment: " + eml);
        }
    }

    /** A by-value attachment with real bytes and an optional Content-Location. */
    private static final class DataAttachmentStub extends Attachment {
        private final String contentLocation;

        DataAttachmentStub(String contentLocation) {
            this.contentLocation = contentLocation;
        }

        @Override
        public String getLongFilename() {
            return "logo.png";
        }

        @Override
        public String getFilename() {
            return "";
        }

        @Override
        public int getAttachMethod() {
            return 1; // afByValue
        }

        @Override
        public byte[] getData() {
            return new byte[] {1, 2, 3};
        }

        @Override
        public String getMimeTag() {
            return "image/png";
        }

        @Override
        public String getContentId() {
            return null;
        }

        @Override
        public String getContentLocation() {
            return contentLocation;
        }

        @Override
        public boolean isInline() {
            return false;
        }
    }

    /**
     * Attachment-loss tripwire: PR_HASATTACH set but an empty attachment table means the
     * attachments were unreadable (e.g. a corrupted table); that must surface as a counted failure
     * instead of a silently attachment-less "successful" export.
     */
    @Test
    void claimedButUnreadableAttachmentsAreCountedAsFailed() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(pstFile, "Claims attachments", List.of(), null, "") {
                @Override
                public boolean hasAttachments() {
                    return true;
                }
            };
            var stats = new PstToEmlConverter.Stats();
            var log = new RecordingLog();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, 0, log, stats);

            assertEquals(1, stats.failedAttachments(), "The unreadable attachments must be counted as failed");
            assertTrue(
                    log.errors.stream().anyMatch(error -> error.contains("PR_HASATTACH")),
                    "The loss must be reported: " + log.errors);
        }
    }

    /** F2: an OLE attachment (method 6) exports its raw object bytes instead of being dropped. */
    @Test
    void oleAttachmentExportsItsRawObjectBytes() throws Exception {
        var oleBytes = "ole-storage-payload".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var oleAttachment = new Attachment() {
            @Override
            public String getLongFilename() {
                return "Worksheet";
            }

            @Override
            public String getFilename() {
                return "";
            }

            @Override
            public String getMimeTag() {
                return "";
            }

            @Override
            public int getAttachMethod() {
                return 6; // afStorage
            }

            @Override
            public byte[] getData() {
                return null;
            }

            @Override
            public byte[] getObjectData() {
                return oleBytes;
            }

            @Override
            public String getContentId() {
                return null;
            }

            @Override
            public String getContentLocation() {
                return null;
            }

            @Override
            public boolean isInline() {
                return false;
            }
        };
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(pstFile, "Has OLE object", List.of(oleAttachment), null, "");
            var stats = new PstToEmlConverter.Stats();
            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, 0, ConversionLog.NOOP, stats)
                    .writeTo(writer);

            var eml = writer.toString();
            assertEquals(0, stats.failedAttachments(), "An exported OLE object is not a failure");
            assertTrue(eml.contains("name=\"Worksheet.ole\""), "The part must carry an .ole-suffixed name");
            assertTrue(eml.contains("application/octet-stream"), "OLE storage is opaque binary");
            assertTrue(
                    eml.contains(java.util.Base64.getEncoder().encodeToString(oleBytes)),
                    "The raw object bytes must round-trip");
        }
    }

    /** F4: a task exports a VTODO calendar part alongside its text body. */
    @Test
    void taskExportsAVTodoPart() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var task = new StubMessage(pstFile, "File the report", List.of(), null, "") {
                @Override
                public String getMessageClass() {
                    return "IPM.Task";
                }
            };
            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(
                            task, defaultOptions(), pstFile, 0, ConversionLog.NOOP, new PstToEmlConverter.Stats())
                    .writeTo(writer);

            var eml = writer.toString();
            assertTrue(eml.contains("name=\"task.ics\""), "The task must carry a task.ics part");
            var icsMatcher = java.util.regex.Pattern.compile(
                            "(?s)name=\"task\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                    .matcher(eml);
            assertTrue(icsMatcher.find(), "The task.ics part must be base64-encoded");
            var ics = new String(
                    java.util.Base64.getMimeDecoder().decode(icsMatcher.group(1)),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(ics.contains("BEGIN:VTODO"), "The calendar part must be a VTODO: " + ics);
            assertTrue(ics.contains("SUMMARY:File the report"), "The task subject must be the VTODO summary");
        }
    }

    /**
     * A message double for serializer-level tests: fixed subject/class/attachments and a canned
     * result for {@link Message#readEmbeddedMessage}, so nesting scenarios that no public PST
     * fixture provides (journal reports, depth ≥ 2) stay testable. The real resolution path behind
     * readEmbeddedMessage is covered against submessage.pst in the pst-parser module's tests.
     */
    private static class StubMessage extends Message {
        private final String subject;
        private final List<Attachment> attachments;
        private final Message embedded;
        private final String transportHeaders;

        StubMessage(
                PstFile pstFile,
                String subject,
                List<Attachment> attachments,
                Message embedded,
                String transportHeaders) {
            super(pstFile, 0x122);
            this.subject = subject;
            this.attachments = attachments;
            this.embedded = embedded;
            this.transportHeaders = transportHeaders;
        }

        @Override
        public String getMessageClass() {
            return "IPM.Note";
        }

        @Override
        public String getSubject() {
            return subject;
        }

        @Override
        public String getBody() {
            return "Body of " + subject;
        }

        @Override
        public String getSenderName() {
            return "Sender";
        }

        @Override
        public String getSenderEmail() {
            return "sender@example.com";
        }

        @Override
        public List<Recipient> getRecipients() {
            return List.of();
        }

        @Override
        public List<Attachment> getAttachments() {
            return attachments;
        }

        @Override
        public String getTransportHeaders() {
            return transportHeaders;
        }

        @Override
        public Message readEmbeddedMessage(Attachment attachment) {
            return embedded;
        }
    }

    /**
     * N2: embedded messages nested two levels deep serialize recursively — each level becomes a
     * message/rfc822 part inside its parent, and (N3) each part is named after the attachment's
     * display name instead of the old "attachment.dat.eml".
     */
    @Test
    void deeplyNestedEmbeddedMessagesSerializeRecursively() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var level3 = new StubMessage(pstFile, "Level 3 innermost", List.of(), null, "");
            var level2 = new StubMessage(
                    pstFile, "Level 2", List.of(new EmbeddedAttachmentStub("Level 3 innermost")), level3, "");
            var level1 = new StubMessage(
                    pstFile, "Level 1 outer", List.of(new EmbeddedAttachmentStub("Level 2")), level2, "");

            var serializer = PstToEmlConverter.createSerializer(level1, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertEquals(
                    2,
                    countOccurrences(eml, "Content-Type: message/rfc822"),
                    () -> "Expected two nested message/rfc822 parts in:\n" + eml);
            assertTrue(eml.contains("Subject: Level 1 outer"), "Outer subject");
            assertTrue(eml.contains("Subject: Level 2"), "Mid subject");
            assertTrue(eml.contains("Subject: Level 3 innermost"), "Innermost subject");
            assertTrue(eml.contains("name=\"Level 2.eml\""), "N3: the part is named from PR_DISPLAY_NAME");
            assertFalse(eml.contains("attachment.dat.eml"), "N3: the generic default name must be gone");
        }
    }

    /**
     * Round 11 parity: two embedded message siblings that resolve to the same name (the common case —
     * embedded messages carry no filename, only PR_DISPLAY_NAME) must get distinct filename= params so
     * a client does not silently overwrite one on extract. The MSG driver already dedups via
     * uniqueEmbeddedName; the PST driver emitted byte-identical names before the fix.
     */
    @Test
    void siblingEmbeddedMessagesGetDistinctFilenames() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var inner = new StubMessage(pstFile, "Forwarded note", List.of(), null, "");
            var host = new StubMessage(
                    pstFile,
                    "Host",
                    List.of(new EmbeddedAttachmentStub("Forwarded note"), new EmbeddedAttachmentStub("Forwarded note")),
                    inner,
                    "");

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(host, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertEquals(
                    2,
                    countOccurrences(eml, "Content-Type: message/rfc822"),
                    () -> "expected two nested message/rfc822 parts:\n" + eml);
            assertTrue(eml.contains("name=\"Forwarded note.eml\""), "first sibling keeps the base name: " + eml);
            assertTrue(
                    eml.contains("name=\"Forwarded note (2).eml\""),
                    "second sibling must be deduplicated, not a byte-identical filename: " + eml);
        }
    }

    /**
     * N1 regression: an embedded message that fails to resolve used to vanish silently; it must be
     * reported on the console and counted in the conversion stats.
     */
    @Test
    void unresolvableEmbeddedMessageIsLoggedAndCounted() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(
                    pstFile, "Damaged host", List.of(new EmbeddedAttachmentStub("Lost original")), null, "");
            var log = new RecordingLog();
            var stats = new PstToEmlConverter.Stats();

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, 0, log, stats);
            var writer = new StringWriter();
            serializer.writeTo(writer);

            assertEquals(1, stats.failedAttachments(), "The dropped embedded message must be counted");
            assertTrue(
                    log.errors.stream()
                            .anyMatch(error -> error.contains("Failed to resolve embedded message")
                                    && error.contains("Lost original")),
                    () -> "Expected a console error naming the lost attachment, got: " + log.errors);
        }
    }

    /**
     * N1 regression: a by-value attachment with no stored bytes used to be dropped silently; it is
     * an error worth counting. A by-reference attachment (methods 2–4) has no bytes by design and
     * stays informational.
     */
    @Test
    void attachmentWithoutContentIsLoggedAndCounted() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var log = new RecordingLog();
            var stats = new PstToEmlConverter.Stats();
            var byValue =
                    new StubMessage(pstFile, "Host", List.of(new ContentlessAttachmentStub(1)), null, ""); // afByValue
            var serializer = PstToEmlConverter.createSerializer(byValue, defaultOptions(), pstFile, 0, log, stats);
            serializer.writeTo(new StringWriter());
            assertEquals(1, stats.failedAttachments());
            assertTrue(
                    log.errors.stream().anyMatch(error -> error.contains("report.pdf")),
                    () -> "Expected an error naming the contentless attachment, got: " + log.errors);

            var referenceLog = new RecordingLog();
            var referenceStats = new PstToEmlConverter.Stats();
            var byReference = new StubMessage(
                    pstFile, "Host", List.of(new ContentlessAttachmentStub(2)), null, ""); // afByReference
            PstToEmlConverter.createSerializer(byReference, defaultOptions(), pstFile, 0, referenceLog, referenceStats)
                    .writeTo(new StringWriter());
            assertEquals(0, referenceStats.failedAttachments(), "By-reference attachments are not failures");
            assertTrue(
                    referenceLog.infos.stream().anyMatch(info -> info.contains("reference")),
                    () -> "Expected an informational note for the by-reference attachment, got: " + referenceLog.infos);
        }
    }

    private static final String JOURNAL_TRANSPORT_HEADERS = "Received: from journaling.example.com\r\n"
            + "Date: Sat, 10 Jun 2017 08:51:30 +0000\r\n"
            + "Message-ID: <journal@journal.report.generator>\r\n"
            + "X-MS-Journal-Report: \r\n"
            + "Content-Type: multipart/mixed; boundary=\"original\"\r\n"
            + "MIME-Version: 1.0\r\n";

    /**
     * J1 regression: the X-MS-Journal-Report marker has no MAPI substitute, so it must survive the
     * conversion even when the user opts out of the original transport headers — and stay
     * un-duplicated when the headers are passed through. The full envelope shape this models (and a
     * manually openable export) is samples/eml/journaled/journal_report_pst_export.eml.
     */
    @Test
    void journalReportMarkerSurvivesIndependentOfHeaderOption() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var original = new StubMessage(pstFile, "Original message", List.of(), null, "");
            var journalReport = new StubMessage(
                    pstFile,
                    "Journal report",
                    List.of(new EmbeddedAttachmentStub("Original message")),
                    original,
                    JOURNAL_TRANSPORT_HEADERS);

            var withoutOriginalHeaders =
                    PstToEmlConverter.createSerializer(journalReport, defaultOptions(), pstFile, ConversionLog.NOOP);
            var withoutWriter = new StringWriter();
            withoutOriginalHeaders.writeTo(withoutWriter);
            assertEquals(
                    1,
                    countOccurrences(withoutWriter.toString(), "X-MS-Journal-Report:"),
                    () -> "The journal marker must survive without original headers:\n" + withoutWriter);
            assertTrue(
                    withoutWriter.toString().contains("Content-Type: message/rfc822"),
                    "The journaled original must remain an embedded message part");

            var keepHeadersOptions = new PstToEmlConverter.Options(
                    PstToEmlConverter.DuplicateHandling.OVERWRITE,
                    null,
                    true,
                    true,
                    Message.AddressPreference.PREFER_SMTP,
                    false,
                    false,
                    64L * 1024 * 1024);
            var withOriginalHeaders =
                    PstToEmlConverter.createSerializer(journalReport, keepHeadersOptions, pstFile, ConversionLog.NOOP);
            var withWriter = new StringWriter();
            withOriginalHeaders.writeTo(withWriter);
            assertEquals(
                    1,
                    countOccurrences(withWriter.toString(), "X-MS-Journal-Report:"),
                    () -> "The passthrough must not duplicate the journal marker:\n" + withWriter);
        }
    }

    /**
     * The journal-report sample (manual-verification companion of
     * {@link #journalReportMarkerSurvivesIndependentOfHeaderOption}) mirrors the converter's export
     * shape: a bare X-MS-Journal-Report marker plus the original as a message/rfc822 part.
     */
    @Test
    void journalReportSampleMirrorsExportShape() throws Exception {
        var sample = java.nio.file.Files.readString(
                Paths.get("src/test/resources/samples/eml/journaled/journal_report_pst_export.eml"));
        assertTrue(sample.contains("X-MS-Journal-Report:\r\n"), "The sample carries the bare journal marker");
        assertTrue(sample.contains("Content-Type: message/rfc822"), "The sample embeds the original message");
        assertEquals(1, countOccurrences(sample, "X-MS-Journal-Report:"));
    }

    /** J1: the marker extractor distinguishes "absent" (null) from "present but empty" (""). */
    @Test
    void journalReportMarkerValueParsesTransportHeaders() {
        assertNull(PstToEmlConverter.journalReportMarkerValue(null));
        assertNull(PstToEmlConverter.journalReportMarkerValue(""));
        assertNull(PstToEmlConverter.journalReportMarkerValue("Subject: hi\r\nDate: now\r\n"));
        assertEquals("", PstToEmlConverter.journalReportMarkerValue(JOURNAL_TRANSPORT_HEADERS));
        assertEquals(
                "v2",
                PstToEmlConverter.journalReportMarkerValue("x-ms-journal-report: v2\r\nSubject: s\r\n"),
                "Matching is case-insensitive and keeps a non-empty value");
    }

    /** G5: converting an S/MIME message logs that the re-encoded EML cannot keep its envelope verifiable. */
    @Test
    void smimeMessagesGetAStructureLossNote() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(pstFile, "Signed", List.of(), null, "") {
                @Override
                public String getMessageClass() {
                    return "IPM.Note.SMIME.MultipartSigned";
                }
            };
            var log = new RecordingLog();
            PstToEmlConverter.createSerializer(
                            message, defaultOptions(), pstFile, 0, log, new PstToEmlConverter.Stats())
                    .writeTo(new StringWriter());
            assertTrue(
                    log.infos.stream().anyMatch(info -> info.contains("S/MIME")),
                    () -> "Expected an S/MIME structure-loss note, got: " + log.infos);
        }
    }

    /**
     * Review finding #1 regression: a message whose properties fail to load used to export as a
     * blank "No Subject" EML counted as a success; it must be reported and counted as failed.
     */
    @Test
    void messageThatFailsToLoadIsCountedAsFailedNotConverted() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var broken = new Message(pstFile, 0x7FFFF04); // nonexistent NID (type 0x04)
            assertFalse(broken.isLoaded(), "Sanity: the fixture must not contain this node");

            var stats = new PstToEmlConverter.Stats();
            var log = new RecordingLog();
            assertTrue(PstToEmlConverter.failedToLoad(broken, "Inbox", stats, log), "Must tell the caller to skip");
            assertEquals(1, stats.failedMessages(), "The unloadable message counts as failed");
            assertTrue(
                    log.errors.stream()
                            .anyMatch(error -> error.contains("Failed to convert message") && error.contains("Inbox")),
                    () -> "Expected a console error naming the folder, got: " + log.errors);

            var healthy = new StubMessage(pstFile, "fine", List.of(), null, "");
            assertFalse(PstToEmlConverter.failedToLoad(healthy, "Inbox", stats, log));
            assertEquals(1, stats.failedMessages(), "A loaded message must not be miscounted");
        }
    }

    /**
     * Review finding #1 regression (embedded variant): an embedded message whose node resolves but
     * whose properties fail to load used to serialize as an empty .eml part silently.
     */
    @Test
    void embeddedMessageThatFailsToLoadIsCountedAndSkipped() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var unloadable = new Message(pstFile, 0x7FFFF04);
            assertFalse(unloadable.isLoaded());
            var host = new StubMessage(
                    pstFile, "Host", List.of(new EmbeddedAttachmentStub("Lost original")), unloadable, "");
            var log = new RecordingLog();
            var stats = new PstToEmlConverter.Stats();

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(host, defaultOptions(), pstFile, 0, log, stats)
                    .writeTo(writer);

            assertEquals(1, stats.failedAttachments(), "The unloadable embedded message must be counted");
            assertFalse(
                    writer.toString().contains("message/rfc822"), "No empty rfc822 part may replace the lost original");
            assertTrue(
                    log.errors.stream().anyMatch(error -> error.contains("Failed to load embedded message")),
                    () -> "Expected a console error for the unloadable embed, got: " + log.errors);
        }
    }

    /** Review nit: the depth-cap placeholder replaces real content, so it must show up in the stats. */
    @Test
    void depthCapPlaceholderIsCountedAsFailedAttachment() throws Exception {
        var log = new RecordingLog();
        var stats = new PstToEmlConverter.Stats();
        var stub = PstToEmlConverter.createSerializer(null, defaultOptions(), null, 11, log, stats);
        var writer = new StringWriter();
        stub.writeTo(writer);

        assertEquals(1, stats.failedAttachments(), "The truncated nested message must be counted");
        assertTrue(writer.toString().contains("Nested Message Limit Exceeded"));
        assertTrue(
                log.errors.stream().anyMatch(error -> error.contains("Maximum nested message depth")),
                () -> "Expected a console error for the depth cap, got: " + log.errors);
    }

    /**
     * Review finding #4 regression: an attachment whose content exceeds the configured single-node
     * cap used to be misreported as having "no stored content"; the error must name the actual
     * cause and the dialog option that raises the cap.
     */
    @Test
    void oversizedAttachmentErrorNamesTheDialogOption() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var log = new RecordingLog();
            var stats = new PstToEmlConverter.Stats();
            var oversize = new StubMessage(
                    pstFile, "Host", List.of(new ContentlessAttachmentStub(1, 200L * 1024 * 1024)), null, "");

            PstToEmlConverter.createSerializer(oversize, defaultOptions(), pstFile, 0, log, stats)
                    .writeTo(new StringWriter());

            assertEquals(1, stats.failedAttachments());
            assertTrue(
                    log.errors.stream()
                            .anyMatch(error -> error.contains("Max single attachment size")
                                    && error.contains("exceeds the configured limit")),
                    () -> "Expected the oversize diagnosis naming the dialog option, got: " + log.errors);
            assertFalse(
                    log.errors.stream().anyMatch(error -> error.contains("no stored content")),
                    "The old misdiagnosis must be gone");
        }
    }

    /**
     * Review fidelity gaps: PR_SENSITIVITY, PR_CONVERSATION_TOPIC, PR_CONVERSATION_INDEX and
     * PR_REPLY_RECIPIENT_ENTRIES now export as their RFC header equivalents. The manually openable
     * companion sample is samples/eml/edge/pst_export_fidelity_headers.eml.
     */
    @Test
    void sensitivityThreadingAndReplyToAreExported() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(pstFile, "Quarterly budget", List.of(), null, "") {
                @Override
                public Object getProperty(int tag) {
                    if (tag == MapiProperties.PR_SENSITIVITY) {
                        return 2; // private
                    }
                    if (tag == MapiProperties.PR_CONVERSATION_INDEX) {
                        return new byte[] {1, 2, 3, 4, 5};
                    }
                    return null;
                }

                @Override
                public String getStringProperty(int tag) {
                    return tag == MapiProperties.PR_CONVERSATION_TOPIC_W ? "Quarterly budget" : null;
                }

                @Override
                public List<Recipient> getReplyTo() {
                    return List.of(new Recipient(1, "Replies Mailbox", "replies@example.com"));
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("Sensitivity: Private"), () -> "Sensitivity must be exported:\n" + eml);
            assertTrue(eml.contains("Thread-Topic: Quarterly budget"), () -> "Thread-Topic must be exported:\n" + eml);
            assertTrue(
                    eml.contains("Thread-Index: " + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4, 5})),
                    () -> "Thread-Index must be exported base64-encoded:\n" + eml);
            assertTrue(
                    eml.contains("Reply-To: \"Replies Mailbox\" <replies@example.com>"),
                    () -> "Reply-To must be exported:\n" + eml);
        }
    }

    /** Review fidelity gap: PR_ATTACH_CONTENT_LOCATION now reaches the part's Content-Location header. */
    @Test
    void attachmentContentLocationSurvivesExport() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(
                    pstFile, "Web archive", List.of(new DataAttachmentStub("http://example.com/logo.png")), null, "");

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);

            assertTrue(
                    writer.toString().contains("Content-Location: http://example.com/logo.png"),
                    () -> "Content-Location must survive:\n" + writer);
        }
    }

    /**
     * Review nit regression: \fromtext RTF duplicates the plain-text body and must not export as a
     * body.rtf attachment — except as a last resort when the message has no other body at all.
     */
    @Test
    void encapsulationRtfExportsOnlyAsLastResort() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var fromTextRtf = "{\\rtf1\\ansi\\fromtext \\uc1 plain body}";

            var withBodies = new StubMessage(pstFile, "Has bodies", List.of(), null, "") {
                @Override
                public String getRawRtfBody() {
                    return fromTextRtf;
                }
            };
            var withBodiesWriter = new StringWriter();
            PstToEmlConverter.createSerializer(withBodies, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(withBodiesWriter);
            assertFalse(
                    withBodiesWriter.toString().contains("body.rtf"),
                    "\\fromtext RTF duplicates the text body and must not become an attachment");

            var rtfOnly = new StubMessage(pstFile, "RTF only", List.of(), null, "") {
                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getHtmlBody() {
                    return "";
                }

                @Override
                public String getRawRtfBody() {
                    return fromTextRtf;
                }
            };
            var rtfOnlyWriter = new StringWriter();
            PstToEmlConverter.createSerializer(rtfOnly, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(rtfOnlyWriter);
            // F3: the encapsulated plain text is extracted as the text body, so nothing is left for
            // a body.rtf attachment to preserve.
            assertTrue(
                    rtfOnlyWriter.toString().contains("plain body"),
                    "A \\fromtext-only message must export its encapsulated text as the body");
            assertFalse(
                    rtfOnlyWriter.toString().contains("body.rtf"),
                    "Once the encapsulated text is extracted the RTF is redundant");

            // Only when the encapsulated text decodes to nothing does the raw RTF remain the last
            // resort worth keeping.
            var undecodable = new StubMessage(pstFile, "RTF only, empty text", List.of(), null, "") {
                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getHtmlBody() {
                    return "";
                }

                @Override
                public String getRawRtfBody() {
                    return "{\\rtf1\\ansi\\fromtext}";
                }
            };
            var undecodableWriter = new StringWriter();
            PstToEmlConverter.createSerializer(undecodable, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(undecodableWriter);
            assertTrue(
                    undecodableWriter.toString().contains("body.rtf"),
                    "With nothing extractable the raw RTF is the only content left and must be kept");
        }
    }

    /**
     * A genuine (non-encapsulated) RTF-only message — no PR_BODY and no HTML — must not export an empty
     * body. Mirroring the MSG path, the RTF is stripped to a plain-text fallback and the rich text is
     * still preserved verbatim as a body.rtf attachment.
     */
    @Test
    void genuineRtfOnlyBodyIsStrippedToPlainText() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var genuineRtf = "{\\rtf1\\ansi rich only body}";
            var rtfOnly = new StubMessage(pstFile, "Genuine RTF only", List.of(), null, "") {
                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getHtmlBody() {
                    return "";
                }

                @Override
                public String getRawRtfBody() {
                    return genuineRtf;
                }

                @Override
                public String getRtfBody() {
                    return genuineRtf;
                }

                @Override
                public byte[] getRawRtfBytes() {
                    return genuineRtf.getBytes(Charset.forName("windows-1252"));
                }
            };
            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(rtfOnly, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("rich only body"), "the stripped RTF text must become the body: " + eml);
            assertTrue(eml.contains("body.rtf"), "the original RTF must be kept as an attachment: " + eml);
        }
    }

    /**
     * The fidelity sample (manual-verification companion of
     * {@link #sensitivityThreadingAndReplyToAreExported}) mirrors the converter's export shape:
     * Sensitivity, Thread-Topic, base64 Thread-Index, Reply-To and a Content-Location part.
     */
    @Test
    void fidelityHeadersSampleMirrorsExportShape() throws Exception {
        var sample = java.nio.file.Files.readString(
                Paths.get("src/test/resources/samples/eml/edge/pst_export_fidelity_headers.eml"));
        assertTrue(sample.contains("Sensitivity: Private"));
        assertTrue(sample.contains("Thread-Topic: "));
        assertTrue(sample.contains("Thread-Index: AQIDBAU="));
        assertTrue(sample.contains("Reply-To: "));
        assertTrue(sample.contains("Content-Location: http://example.com/logo.png"));
        // "=3D" is the quoted-printable escape of '=': the rewritten meta reads <meta charset="utf-8">.
        assertTrue(sample.contains("charset=3D\"utf-8\""), "The HTML body's meta charset is rewritten to UTF-8");
    }

    /**
     * F5 manual-verification companion (real export of dist-list.pst's recurring appointment):
     * samples/eml/edge/pst_export_recurring_invite.eml mirrors the export shape — a TZID-anchored
     * series with VTIMEZONE, RRULE and the deleted occurrence as an EXDATE.
     */
    @Test
    void recurringInviteSampleMirrorsExportShape() throws Exception {
        var sample = java.nio.file.Files.readString(
                Paths.get("src/test/resources/samples/eml/edge/pst_export_recurring_invite.eml"));
        assertTrue(sample.contains("name=\"invite.ics\""), "The sample carries the invite part");
        var ics = new String(
                java.util.Base64.getMimeDecoder()
                        .decode(java.util.regex.Pattern.compile(
                                        "(?s)name=\"invite\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                                .matcher(sample)
                                .results()
                                .findFirst()
                                .orElseThrow()
                                .group(1)),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(ics.contains("BEGIN:VTIMEZONE"), ics);
        assertTrue(ics.contains("DTSTART;TZID=MailKit/UTC-0800_DST-0700_0302-1101:20160802T080000"), ics);
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;INTERVAL=1;WKST=SU;BYDAY=TU"), ics);
        assertTrue(ics.contains("EXDATE;TZID=MailKit/UTC-0800_DST-0700_0302-1101:20160809T080000"), ics);
    }

    /**
     * F4 manual-verification companion (real export of aspose-contacts.pst):
     * samples/eml/edge/pst_export_contact_vcard.eml mirrors the contact export shape — an EML named
     * by the contact carrying a text/vcard part with the resolved Email1 named property.
     */
    @Test
    void contactVCardSampleMirrorsExportShape() throws Exception {
        var sample = java.nio.file.Files.readString(
                Paths.get("src/test/resources/samples/eml/edge/pst_export_contact_vcard.eml"));
        assertTrue(sample.contains("Subject: Sebastian Wright"));
        assertTrue(sample.contains("Content-Type: text/vcard; charset=UTF-8; name=\"contact.vcf\""));
        var vcard = new String(
                java.util.Base64.getMimeDecoder()
                        .decode(java.util.regex.Pattern.compile(
                                        "(?s)name=\"contact\\.vcf\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                                .matcher(sample)
                                .results()
                                .findFirst()
                                .orElseThrow()
                                .group(1)),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(vcard.contains("FN:Sebastian Wright"), vcard);
        assertTrue(vcard.contains("EMAIL;TYPE=internet:SebastianWright@dayrep.com"), vcard);
    }

    // -----------------------------------------------------------------------
    // REPORT.*  →  RFC 6522 multipart/report
    // -----------------------------------------------------------------------

    /**
     * REPORT.IPM.Note.NDR must emit a top-level {@code multipart/report;
     * report-type=delivery-status} containing a {@code message/delivery-status} part with
     * {@code Action: failed}, a {@code Status:} field, a {@code Final-Recipient:} field, a
     * {@code Reporting-MTA:} field, and the human-readable report text.
     */
    @Test
    void ndrReportEmitsMultipartDeliveryStatus() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "REPORT.IPM.Note.NDR";
                }

                @Override
                public String getSubject() {
                    return "Undeliverable: Hello";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getStringProperty(int propertyId) {
                    return switch (propertyId) {
                        case 0x1001 -> "Your message could not be delivered."; // PidTagReportText
                        case 0x6820 -> "mail.relay.example.com"; // PidTagReportingMessageTransferAgent
                        case MapiProperties.PR_DISPLAY_TO_W -> "bob@example.com"; // final recipient
                        case 0x0C1B -> "5.1.1"; // PidTagSupplementaryInfo (status)
                        default -> null;
                    };
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("Content-Type: multipart/report"), "top-level must be multipart/report: " + eml);
            assertTrue(eml.contains("report-type=delivery-status"), "report-type must be delivery-status: " + eml);
            assertTrue(
                    eml.contains("Content-Type: message/delivery-status"),
                    "must contain a delivery-status part: " + eml);
            assertTrue(eml.contains("Action: failed"), "Action must be 'failed' for NDR: " + eml);
            assertTrue(eml.contains("Status: 5.1.1"), "Status field must survive: " + eml);
            assertTrue(
                    eml.contains("Final-Recipient: rfc822; bob@example.com"),
                    "Final-Recipient must be present: " + eml);
            assertTrue(
                    eml.contains("Reporting-MTA: dns; mail.relay.example.com"),
                    "Reporting-MTA must be present: " + eml);
            assertTrue(
                    eml.contains("Your message could not be delivered."),
                    "human-readable text must appear in the first part: " + eml);
        }
    }

    /**
     * REPORT.IPM.Note.IPNRN (read receipt) must emit {@code report-type=disposition-notification},
     * a {@code message/disposition-notification} part, a {@code Disposition: ... displayed} line,
     * and the {@code Original-Message-ID}.
     */
    @Test
    void readReceiptReportEmitsDispositionNotification() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "REPORT.IPM.Note.IPNRN";
                }

                @Override
                public String getSubject() {
                    return "Read: Hello";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getStringProperty(int propertyId) {
                    return switch (propertyId) {
                        case 0x1001 -> "This is a read receipt."; // PidTagReportText
                        case MapiProperties.PR_DISPLAY_TO_W -> "alice@example.com"; // final recipient
                        case 0x1046 -> "<original-msg-id@example.com>"; // PidTagOriginalMessageId
                        default -> null;
                    };
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("report-type=disposition-notification"),
                    "report-type must be disposition-notification: " + eml);
            assertTrue(
                    eml.contains("Content-Type: message/disposition-notification"),
                    "must contain a disposition-notification part: " + eml);
            assertTrue(eml.contains("displayed"), "Disposition must carry 'displayed' for IPNRN: " + eml);
            assertTrue(
                    eml.contains("Original-Message-ID: <original-msg-id@example.com>"),
                    "Original-Message-ID must be present: " + eml);
        }
    }

    /**
     * REPORT.IPM.Note.IPNNRN (non-read receipt / deleted) must carry {@code Disposition: ...
     * deleted} instead of displayed.
     */
    @Test
    void nonReadReceiptReportEmitsDeletedDisposition() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "REPORT.IPM.Note.IPNNRN";
                }

                @Override
                public String getSubject() {
                    return "Not Read: Hello";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getStringProperty(int propertyId) {
                    return switch (propertyId) {
                        case MapiProperties.PR_DISPLAY_TO_W -> "alice@example.com";
                        case 0x1046 -> "<original-msg-id@example.com>";
                        default -> null;
                    };
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("report-type=disposition-notification"),
                    "report-type must be disposition-notification: " + eml);
            assertTrue(eml.contains("deleted"), "Disposition must carry 'deleted' for IPNNRN: " + eml);
        }
    }

    // -----------------------------------------------------------------------
    // S/MIME hoist
    // -----------------------------------------------------------------------

    /**
     * A clear-signed message ({@code IPM.Note.SMIME.MultipartSigned}) with a single attachment
     * whose bytes are a full MIME entity starting with {@code Content-Type: multipart/signed} is
     * hoisted verbatim: the top-level Content-Type of the exported EML must be that
     * {@code multipart/signed} value.
     */
    @Test
    void clearSignedSmimeIsHoistedFromSingleAttachment() throws Exception {
        var mimeEntity = "Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\";"
                + " micalg=sha-256; boundary=\"sig\"\r\n"
                + "Content-Transfer-Encoding: 7bit\r\n"
                + "\r\n"
                + "signed body text\r\n"
                + "--sig--\r\n";
        var entityBytes = mimeEntity.getBytes(StandardCharsets.ISO_8859_1);

        var smimeAttachment = new Attachment() {
            @Override
            public String getLongFilename() {
                return "smime.p7m";
            }

            @Override
            public String getFilename() {
                return "";
            }

            @Override
            public String getMimeTag() {
                return "multipart/signed";
            }

            @Override
            public int getAttachMethod() {
                return 1; // afByValue
            }

            @Override
            public byte[] getData() {
                return entityBytes;
            }

            @Override
            public String getContentId() {
                return null;
            }

            @Override
            public String getContentLocation() {
                return null;
            }

            @Override
            public boolean isInline() {
                return false;
            }
        };

        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note.SMIME.MultipartSigned";
                }

                @Override
                public String getSubject() {
                    return "Signed message";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of(smimeAttachment);
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("Content-Type: multipart/signed"),
                    "top-level Content-Type must be the hoisted multipart/signed: " + eml);
            assertTrue(
                    eml.contains("protocol=\"application/pkcs7-signature\""),
                    "protocol parameter must survive: " + eml);
            assertTrue(eml.contains("signed body text"), "hoisted body must be present in the output: " + eml);
        }
    }

    /**
     * An opaque S/MIME message ({@code IPM.Note.Secure}) with a single attachment whose bytes are
     * not parseable as MIME headers is exported as a {@code application/pkcs7-mime} base64
     * top-level entity with the appropriate Content-Disposition.
     */
    @Test
    void opaqueSmimeIsExportedAsBase64PkcsEntity() throws Exception {
        var opaqueBytes = new byte[] {0x30, 0x45, 0x02, 0x01, 0x00, 0x09, 0x10, 0x20};

        var smimeAttachment = new Attachment() {
            @Override
            public String getLongFilename() {
                return "smime.p7m";
            }

            @Override
            public String getFilename() {
                return "";
            }

            @Override
            public String getMimeTag() {
                return "application/pkcs7-mime";
            }

            @Override
            public int getAttachMethod() {
                return 1; // afByValue
            }

            @Override
            public byte[] getData() {
                return opaqueBytes;
            }

            @Override
            public String getContentId() {
                return null;
            }

            @Override
            public String getContentLocation() {
                return null;
            }

            @Override
            public boolean isInline() {
                return false;
            }
        };

        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note.Secure";
                }

                @Override
                public String getSubject() {
                    return "Encrypted message";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of(smimeAttachment);
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("Content-Type: application/pkcs7-mime"),
                    "top-level must be application/pkcs7-mime: " + eml);
            assertTrue(eml.contains("name=\"smime.p7m\""), "name parameter must be present: " + eml);
            assertTrue(eml.contains("Content-Transfer-Encoding: base64"), "opaque blob must be base64: " + eml);
            assertTrue(
                    eml.contains("Content-Disposition: attachment; filename=\"smime.p7m\""),
                    "Content-Disposition must name the file: " + eml);
            assertTrue(
                    eml.contains(Base64.getEncoder().encodeToString(opaqueBytes)),
                    "base64 of the blob must be present: " + eml);
        }
    }

    /**
     * When an S/MIME message class has TWO attachments the hoist cannot determine which is the
     * envelope, so it falls back to normal re-encoding: the output contains a regular text/plain
     * body and the log records that the entity could not be hoisted.
     */
    @Test
    void smimeWithTwoAttachmentsFallsBackToNormalReencodeAndLogs() throws Exception {
        var twoAttachments = List.<Attachment>of(
                new Attachment() {
                    @Override
                    public String getLongFilename() {
                        return "part1.dat";
                    }

                    @Override
                    public String getFilename() {
                        return "";
                    }

                    @Override
                    public String getMimeTag() {
                        return "application/octet-stream";
                    }

                    @Override
                    public int getAttachMethod() {
                        return 1;
                    }

                    @Override
                    public byte[] getData() {
                        return new byte[] {1, 2, 3};
                    }

                    @Override
                    public String getContentId() {
                        return null;
                    }

                    @Override
                    public String getContentLocation() {
                        return null;
                    }

                    @Override
                    public boolean isInline() {
                        return false;
                    }
                },
                new Attachment() {
                    @Override
                    public String getLongFilename() {
                        return "part2.dat";
                    }

                    @Override
                    public String getFilename() {
                        return "";
                    }

                    @Override
                    public String getMimeTag() {
                        return "application/octet-stream";
                    }

                    @Override
                    public int getAttachMethod() {
                        return 1;
                    }

                    @Override
                    public byte[] getData() {
                        return new byte[] {4, 5, 6};
                    }

                    @Override
                    public String getContentId() {
                        return null;
                    }

                    @Override
                    public String getContentLocation() {
                        return null;
                    }

                    @Override
                    public boolean isInline() {
                        return false;
                    }
                });

        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(pstFile, "Two-attachment signed", twoAttachments, null, "") {
                @Override
                public String getMessageClass() {
                    return "IPM.Note.SMIME.MultipartSigned";
                }
            };

            var log = new RecordingLog();
            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(
                            message, defaultOptions(), pstFile, 0, log, new PstToEmlConverter.Stats())
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("text/plain"), "fallback must produce a normal text/plain body: " + eml);
            assertFalse(
                    eml.contains("Content-Type: multipart/signed"), "must not be hoisted with two attachments: " + eml);
            assertTrue(
                    log.infos.stream()
                            .anyMatch(info -> info.contains("could not be hoisted")
                                    || info.contains("S/MIME") && info.contains("not be hoisted")),
                    () -> "Expected a 'could not be hoisted' log entry, got: " + log.infos);
        }
    }

    // -----------------------------------------------------------------------
    // Meeting-response PARTSTAT
    // -----------------------------------------------------------------------

    /**
     * An {@code IPM.Schedule.Meeting.Resp.Pos} (accepted) message must emit an invite.ics with
     * {@code METHOD:REPLY}, the organizer carrying the recipient email (role-swapped), and the
     * responding attendee with {@code PARTSTAT=ACCEPTED}.
     */
    @Test
    void meetingResponseAcceptedCarriesPartstatAccepted() throws Exception {
        var distListPst = Paths.get("src/test/resources/samples/pst/dist-list.pst");
        try (var pstFile = new PstFile(distListPst)) {
            var startId = pstFile.namedPropertyId(UUID.fromString("00062002-0000-0000-C000-000000000046"), 0x820D);
            org.junit.jupiter.api.Assertions.assertNotNull(
                    startId, "dist-list.pst must define the appointment start named property");

            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Schedule.Meeting.Resp.Pos";
                }

                @Override
                public String getSubject() {
                    return "Accepted: Sync";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getSenderName() {
                    return "Bob";
                }

                @Override
                public String getSenderEmail() {
                    return "bob@example.com";
                }

                @Override
                public List<Recipient> getRecipients() {
                    // The organizer is the (single) recipient on a meeting response.
                    return List.of(new Recipient(1, "Alice", "alice@example.com"));
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public Object getProperty(int propertyId) {
                    if (propertyId == startId) {
                        return Instant.parse("2026-07-01T15:00:00Z");
                    }
                    return null;
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("name=\"invite.ics\""), "invite.ics must be present: " + eml);
            var icsMatcher = Pattern.compile("(?s)name=\"invite\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                    .matcher(eml);
            assertTrue(icsMatcher.find(), "invite.ics must be base64-encoded: " + eml);
            var ics = new String(Base64.getMimeDecoder().decode(icsMatcher.group(1)), StandardCharsets.UTF_8);

            assertTrue(ics.contains("METHOD:REPLY"), "method must be REPLY: " + ics);
            assertTrue(
                    ics.contains("alice@example.com"), "organizer (role-swapped from recipient) must be alice: " + ics);
            assertTrue(ics.contains("bob@example.com"), "responding attendee must be bob: " + ics);
            assertTrue(ics.contains("PARTSTAT=ACCEPTED"), "PARTSTAT must be ACCEPTED for Resp.Pos: " + ics);
        }
    }

    /**
     * round-4 audit: a meeting's iCal UID MUST be its stable identity (PidLidCleanGlobalObjectId,
     * PSETID_Meeting LID 0x0023), not a random UUID, so a REQUEST/REPLY/CANCEL of the same meeting
     * share one UID (rfc5545 §3.8.4.7, rfc5546 §3.2; [MS-OXCICAL] §2.1.3.1.1.20.26 maps the bytes to
     * UID as uppercase hex). dist-list.pst already defines that named property, so the path is driven
     * end-to-end through the converter rather than only the generator.
     */
    @Test
    void meetingInviteUidIsUppercaseHexOfCleanGlobalObjectId() throws Exception {
        var distListPst = Paths.get("src/test/resources/samples/pst/dist-list.pst");
        try (var pstFile = new PstFile(distListPst)) {
            var startId = pstFile.namedPropertyId(UUID.fromString("00062002-0000-0000-C000-000000000046"), 0x820D);
            var cleanGoidId = pstFile.namedPropertyId(UUID.fromString("6ED8DA90-450B-101B-98DA-00AA003F1305"), 0x0023);
            org.junit.jupiter.api.Assertions.assertNotNull(
                    cleanGoidId, "dist-list.pst must define the PSETID_Meeting CleanGlobalObjectId named property");
            var cleanGoid = new byte[] {
                0x04, 0x00, 0x00, 0x00, (byte) 0x82, 0x00, (byte) 0xE0, 0x00,
                0x74, (byte) 0xC5, (byte) 0xB7, 0x10, 0x1A, (byte) 0x82, (byte) 0xE0, 0x08,
                0x00, 0x00, 0x00, 0x00, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x01
            };

            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Schedule.Meeting.Request";
                }

                @Override
                public String getSubject() {
                    return "Project kickoff";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getSenderName() {
                    return "Chair";
                }

                @Override
                public String getSenderEmail() {
                    return "chair@example.com";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of(new Recipient(1, "Attendee", "attendee@example.com"));
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public Object getProperty(int propertyId) {
                    if (propertyId == startId) {
                        return Instant.parse("2026-07-01T15:00:00Z");
                    }
                    if (propertyId == cleanGoidId) {
                        return cleanGoid;
                    }
                    return null;
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            var icsMatcher = Pattern.compile("(?s)name=\"invite\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                    .matcher(eml);
            org.junit.jupiter.api.Assertions.assertTrue(icsMatcher.find(), "invite.ics must be base64-encoded: " + eml);
            var ics = new String(Base64.getMimeDecoder().decode(icsMatcher.group(1)), StandardCharsets.UTF_8)
                    .replace("\r\n ", "")
                    .replace("\r\n\t", "");

            var expectedUid = java.util.HexFormat.of().withUpperCase().formatHex(cleanGoid);
            assertTrue(
                    ics.contains("UID:" + expectedUid + "\r\n"),
                    "the VEVENT UID must be the uppercase hex of PidLidCleanGlobalObjectId: " + ics);
        }
    }

    /**
     * An {@code IPM.Schedule.Meeting.Resp.Neg} (declined) message must carry {@code PARTSTAT=DECLINED}.
     */
    @Test
    void meetingResponseDeclinedCarriesPartstatDeclined() throws Exception {
        var distListPst = Paths.get("src/test/resources/samples/pst/dist-list.pst");
        try (var pstFile = new PstFile(distListPst)) {
            var startId = pstFile.namedPropertyId(UUID.fromString("00062002-0000-0000-C000-000000000046"), 0x820D);
            assertNotNull(startId);

            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Schedule.Meeting.Resp.Neg";
                }

                @Override
                public String getSubject() {
                    return "Declined: Sync";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getSenderName() {
                    return "Bob";
                }

                @Override
                public String getSenderEmail() {
                    return "bob@example.com";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of(new Recipient(1, "Alice", "alice@example.com"));
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public Object getProperty(int propertyId) {
                    if (propertyId == startId) {
                        return Instant.parse("2026-07-01T15:00:00Z");
                    }
                    return null;
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            var icsMatcher = Pattern.compile("(?s)name=\"invite\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                    .matcher(eml);
            assertTrue(icsMatcher.find(), "invite.ics must be present");
            var ics = new String(Base64.getMimeDecoder().decode(icsMatcher.group(1)), StandardCharsets.UTF_8);

            assertTrue(ics.contains("METHOD:REPLY"), "method must be REPLY: " + ics);
            assertTrue(ics.contains("PARTSTAT=DECLINED"), "PARTSTAT must be DECLINED for Resp.Neg: " + ics);
        }
    }

    /**
     * Release-readiness regression (appointment REPLY organizer): on a meeting-response REPLY the
     * ORGANIZER is the original meeting organizer — the response's To recipient (RFC 5546 §3.2.3) —
     * not blindly the first recipient row. A response CC'd to another attendee that precedes the
     * organizer in the recipient table must still name the To recipient as ORGANIZER, and the CC'd
     * attendee must not be promoted to organizer (which would also drop the real organizer).
     */
    @Test
    void meetingResponseOrganizerIsToRecipientNotFirstRecipient() throws Exception {
        var distListPst = Paths.get("src/test/resources/samples/pst/dist-list.pst");
        try (var pstFile = new PstFile(distListPst)) {
            var startId = pstFile.namedPropertyId(UUID.fromString("00062002-0000-0000-C000-000000000046"), 0x820D);
            assertNotNull(startId);

            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Schedule.Meeting.Resp.Pos";
                }

                @Override
                public String getSubject() {
                    return "Accepted: Sync";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getSenderName() {
                    return "Bob";
                }

                @Override
                public String getSenderEmail() {
                    return "bob@example.com";
                }

                @Override
                public List<Recipient> getRecipients() {
                    // PR_RECIPIENT_TYPE: 1 = To, 2 = Cc. A CC'd attendee (Carol) deliberately precedes
                    // the To recipient (Alice, the real organizer) in the table; the old code picked
                    // recipients.get(0) -> Carol.
                    return List.of(
                            new Recipient(2, "Carol", "carol@example.com"),
                            new Recipient(1, "Alice", "alice@example.com"));
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public Object getProperty(int propertyId) {
                    if (propertyId == startId) {
                        return Instant.parse("2026-07-01T15:00:00Z");
                    }
                    return null;
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            var icsMatcher = Pattern.compile("(?s)name=\"invite\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                    .matcher(eml);
            assertTrue(icsMatcher.find(), "invite.ics must be present: " + eml);
            var ics = new String(Base64.getMimeDecoder().decode(icsMatcher.group(1)), StandardCharsets.UTF_8);

            assertTrue(ics.contains("METHOD:REPLY"), "method must be REPLY: " + ics);

            var organizerLine = ics.lines()
                    .filter(line -> line.startsWith("ORGANIZER"))
                    .findFirst()
                    .orElse("");
            assertTrue(
                    organizerLine.contains("alice@example.com"),
                    "ORGANIZER must be the To recipient (the meeting organizer), got: " + organizerLine);
            assertFalse(
                    organizerLine.contains("carol@example.com"),
                    "ORGANIZER must not be the CC'd attendee Carol: " + organizerLine);

            // The responding attendee (the sender) must survive with its PARTSTAT, and Carol must not
            // have been silently dropped or promoted into the organizer role.
            var attendeeLines =
                    ics.lines().filter(line -> line.startsWith("ATTENDEE")).toList();
            assertTrue(
                    attendeeLines.stream().anyMatch(line -> line.contains("bob@example.com")),
                    "responding attendee Bob must be present: " + ics);
            assertTrue(
                    attendeeLines.stream().anyMatch(line -> line.contains("PARTSTAT=ACCEPTED")),
                    "responding attendee must carry PARTSTAT=ACCEPTED: " + ics);
        }
    }

    /**
     * Companion to the role-swap regression: with only the organizer in the To field (the common
     * single-recipient response shape) the organizer is still the To recipient and the responder is
     * the attendee — the prior {@code attendees.get(0)} path and the To-recipient path must agree here.
     */
    @Test
    void meetingResponseSingleToRecipientStillNamesOrganizer() throws Exception {
        var distListPst = Paths.get("src/test/resources/samples/pst/dist-list.pst");
        try (var pstFile = new PstFile(distListPst)) {
            var startId = pstFile.namedPropertyId(UUID.fromString("00062002-0000-0000-C000-000000000046"), 0x820D);
            assertNotNull(startId);

            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Schedule.Meeting.Resp.Tent";
                }

                @Override
                public String getSubject() {
                    return "Tentative: Sync";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getSenderName() {
                    return "Bob";
                }

                @Override
                public String getSenderEmail() {
                    return "bob@example.com";
                }

                @Override
                public List<Recipient> getRecipients() {
                    // PR_RECIPIENT_TYPE 1 = To (the meeting organizer on a response).
                    return List.of(new Recipient(1, "Alice", "alice@example.com"));
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public Object getProperty(int propertyId) {
                    if (propertyId == startId) {
                        return Instant.parse("2026-07-01T15:00:00Z");
                    }
                    return null;
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var ics = new String(
                    Base64.getMimeDecoder()
                            .decode(Pattern.compile("(?s)name=\"invite\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                                    .matcher(writer.toString())
                                    .results()
                                    .findFirst()
                                    .orElseThrow()
                                    .group(1)),
                    StandardCharsets.UTF_8);

            assertTrue(ics.contains("METHOD:REPLY"), "method must be REPLY: " + ics);
            var organizerLine = ics.lines()
                    .filter(line -> line.startsWith("ORGANIZER"))
                    .findFirst()
                    .orElse("");
            assertTrue(organizerLine.contains("alice@example.com"), "ORGANIZER must be Alice: " + organizerLine);
            assertTrue(ics.contains("PARTSTAT=TENTATIVE"), "PARTSTAT must be TENTATIVE for Resp.Tent: " + ics);
        }
    }

    // -----------------------------------------------------------------------
    // TaskRequest METHOD
    // -----------------------------------------------------------------------

    /**
     * An {@code IPM.TaskRequest} must produce a {@code task.ics} with {@code METHOD:REQUEST} in
     * both the part header ({@code text/calendar; charset=UTF-8; method=REQUEST}) and the iCal body.
     */
    @Test
    void taskRequestCarriesMethodRequest() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var task = new StubMessage(pstFile, "Review Q2 budget", List.of(), null, "") {
                @Override
                public String getMessageClass() {
                    return "IPM.TaskRequest";
                }

                @Override
                public List<Recipient> getRecipients() {
                    // The assignee — a task REQUEST's ATTENDEE (RFC 5546 §3.4); the sender is the ORGANIZER.
                    return List.of(new Recipient(1, "Assignee", "assignee@example.com"));
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(
                            task, defaultOptions(), pstFile, 0, ConversionLog.NOOP, new PstToEmlConverter.Stats())
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("text/calendar; charset=UTF-8; method=REQUEST"),
                    "part content-type must carry method=REQUEST: " + eml);
            var icsMatcher = Pattern.compile("(?s)name=\"task\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                    .matcher(eml);
            assertTrue(icsMatcher.find(), "task.ics must be present");
            var ics = new String(Base64.getMimeDecoder().decode(icsMatcher.group(1)), StandardCharsets.UTF_8);
            assertTrue(ics.contains("METHOD:REQUEST"), "iCal body must contain METHOD:REQUEST: " + ics);
        }
    }

    /**
     * An {@code IPM.TaskRequest.Accept} (task accepted) must carry {@code METHOD:REPLY}.
     */
    @Test
    void taskRequestAcceptCarriesMethodReply() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var task = new StubMessage(pstFile, "RE: Review Q2 budget", List.of(), null, "") {
                @Override
                public String getMessageClass() {
                    return "IPM.TaskRequest.Accept";
                }

                @Override
                public List<Recipient> getRecipients() {
                    // The original assigner — a task REPLY's ORGANIZER (RFC 5546 §3.4); the responding
                    // sender is the ATTENDEE.
                    return List.of(new Recipient(1, "Assigner", "assigner@example.com"));
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(
                            task, defaultOptions(), pstFile, 0, ConversionLog.NOOP, new PstToEmlConverter.Stats())
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("method=REPLY"), "part content-type must carry method=REPLY: " + eml);
            var icsMatcher = Pattern.compile("(?s)name=\"task\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                    .matcher(eml);
            assertTrue(icsMatcher.find(), "task.ics must be present");
            var ics = new String(Base64.getMimeDecoder().decode(icsMatcher.group(1)), StandardCharsets.UTF_8);
            assertTrue(ics.contains("METHOD:REPLY"), "iCal body must contain METHOD:REPLY: " + ics);
        }
    }

    /**
     * A plain {@code IPM.Task} (not a request/response) must carry {@code METHOD:PUBLISH}
     * (regression guard — the gate-bug would have made startsWith("IPM.Task") swallow TaskRequest).
     */
    @Test
    void plainTaskCarriesMethodPublish() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var task = new StubMessage(pstFile, "File the report", List.of(), null, "") {
                @Override
                public String getMessageClass() {
                    return "IPM.Task";
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(
                            task, defaultOptions(), pstFile, 0, ConversionLog.NOOP, new PstToEmlConverter.Stats())
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("method=PUBLISH"), "part content-type must carry method=PUBLISH for IPM.Task: " + eml);
            var icsMatcher = Pattern.compile("(?s)name=\"task\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                    .matcher(eml);
            assertTrue(icsMatcher.find(), "task.ics must be present");
            var ics = new String(Base64.getMimeDecoder().decode(icsMatcher.group(1)), StandardCharsets.UTF_8);
            assertTrue(ics.contains("METHOD:PUBLISH"), "iCal body must contain METHOD:PUBLISH: " + ics);
        }
    }

    // -----------------------------------------------------------------------
    // Allow-list gate (isAllowedMessageClass)
    // -----------------------------------------------------------------------

    /**
     * The allow-list gate must accept all expanded mail-like classes regardless of exportNonMailItems.
     */
    @Test
    void allowListAcceptsMsgLikeClasses() {
        for (var cls : List.of(
                "IPM.Document",
                "IPM.Report",
                "IPM.Recall.Report",
                "IPM.Outlook.Recall",
                "IPM.Remote",
                "IPM.Resend",
                "IPM.OLE.Class",
                "IPM",
                "IPM.Note")) {
            assertTrue(
                    PstToEmlConverter.isAllowedMessageClass(cls, false),
                    "must be allowed without exportNonMailItems: " + cls);
        }
    }

    /**
     * Non-mail item classes (IPM.Contact, IPM.Activity) are blocked by default and allowed only
     * when exportNonMailItems is true. An entirely unknown class is always blocked.
     */
    @Test
    void allowListBlocksNonMailClassesUnlessExportNonMailEnabled() {
        assertFalse(
                PstToEmlConverter.isAllowedMessageClass("IPM.Contact", false),
                "IPM.Contact must be blocked by default");
        assertFalse(
                PstToEmlConverter.isAllowedMessageClass("IPM.Activity", false),
                "IPM.Activity must be blocked by default");
        assertFalse(
                PstToEmlConverter.isAllowedMessageClass("IPM.UnknownGarbage", false), "unknown class must be blocked");
        assertFalse(
                PstToEmlConverter.isAllowedMessageClass("IPM.UnknownGarbage", true),
                "unknown class must be blocked even with exportNonMailItems");

        assertTrue(
                PstToEmlConverter.isAllowedMessageClass("IPM.Contact", true),
                "IPM.Contact must be allowed when exportNonMailItems is true");
        assertTrue(
                PstToEmlConverter.isAllowedMessageClass("IPM.Activity", true),
                "IPM.Activity must be allowed when exportNonMailItems is true");
    }

    // -----------------------------------------------------------------------
    // Downgrade log
    // -----------------------------------------------------------------------

    /**
     * A message of class {@code IPM.Document} (allowed but has no specialized handler) must log
     * "No specialized handler for message class IPM.Document" when passed through createSerializer.
     */
    @Test
    void genericMessageClassLogsDowngradeNote() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(pstFile, "Some document", List.of(), null, "") {
                @Override
                public String getMessageClass() {
                    return "IPM.Document";
                }
            };

            var log = new RecordingLog();
            PstToEmlConverter.createSerializer(
                            message, defaultOptions(), pstFile, 0, log, new PstToEmlConverter.Stats())
                    .writeTo(new StringWriter());

            assertTrue(
                    log.infos.stream()
                            .anyMatch(info -> info.contains("No specialized handler for message class IPM.Document")),
                    () -> "Expected a downgrade note for IPM.Document, got: " + log.infos);
        }
    }

    // -----------------------------------------------------------------------
    // Cancellation propagation (release blocker)
    // -----------------------------------------------------------------------

    /**
     * Release-readiness regression: the progress indicator's {@code checkCanceled()} throws a
     * {@link ProcessCanceledException} (a {@code RuntimeException} via {@code CancellationException}).
     * The per-message and per-folder loops wrap each item in a generic {@code catch (Exception)}, so
     * before the fix the cancellation was swallowed — counted as a failed message and logged as a
     * spurious failure — and the conversion kept running, never reaching the action layer that
     * handles cancellation gracefully. Driving the package-private {@code processFolder} entry point
     * (the same loop {@code convert} walks), the {@code ProcessCanceledException} must propagate out
     * unchanged, the walk must stop early (fewer than all messages converted), and no canceled item
     * may be counted as a failure.
     */
    @Test
    void cancellationPropagatesOutOfConvertAndIsNotCountedAsFailure() throws Exception {
        var multiMessagePst = Paths.get("src/test/resources/samples/pst/testPST_variousBodyTypes.pst");
        var tempDir = Files.createTempDirectory("pst_cancel");
        try (var pstFile = new PstFile(multiMessagePst)) {
            // Arms cancellation after the first message: the loop calls checkCanceled() then setText()
            // per message, so setText fires once for message #1 (converting it), arms cancel, and the
            // final checkCanceled() guarding message #2 then throws — exercising mid-walk cancellation.
            var indicator = new ProgressIndicatorBase() {
                @Override
                public void setText(String text) {
                    super.setText(text);
                    cancel();
                }
            };
            var stats = new PstToEmlConverter.Stats();

            var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    ProcessCanceledException.class,
                    () -> PstToEmlConverter.processFolder(
                            pstFile, 0x122, tempDir, defaultOptions(), stats, indicator, "", ConversionLog.NOOP),
                    "checkCanceled()'s ProcessCanceledException must propagate, not be swallowed");
            org.junit.jupiter.api.Assertions.assertNotNull(thrown);

            assertEquals(
                    0,
                    stats.failedMessages(),
                    "A cancellation must not be miscounted as a failed message: " + stats.failedMessages());
            assertTrue(
                    stats.converted() < 4,
                    "The walk must stop early on cancellation rather than convert all four messages, got "
                            + stats.converted());
        }
    }

    /**
     * Audit 03-M4: the recovery pass has its own checkCanceled() / ProcessCanceledException re-throw,
     * separate from the folder walk. An empty knownMessages set makes every message node an unreferenced
     * (orphan) candidate, so recovery actually processes items and reaches its cancel checkpoint;
     * cancelling there must propagate the exception and never miscount it as a failed message.
     */
    @Test
    void recoveryCancellationPropagatesAndIsNotCountedAsFailure() throws Exception {
        var multiMessagePst = Paths.get("src/test/resources/samples/pst/testPST_variousBodyTypes.pst");
        var tempDir = Files.createTempDirectory("pst_recover_cancel");
        try (var pstFile = new PstFile(multiMessagePst)) {
            // recoverUnreferencedMessages calls checkCanceled() then setText() per candidate, so setText
            // fires for candidate #1 and arms cancellation; the checkCanceled() guarding candidate #2 then
            // throws. isCancelable() is overridden to true so the (final) checkCanceled() throws via the
            // cancel() flag alone, without the default isCancelable() path that calls
            // ProgressManager.getInstance() — which needs an IntelliJ Application this plain unit test
            // does not start.
            var indicator = new ProgressIndicatorBase() {
                @Override
                public void setText(String text) {
                    super.setText(text);
                    cancel();
                }

                @Override
                protected boolean isCancelable() {
                    return true;
                }
            };
            var stats = new PstToEmlConverter.Stats();

            var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    ProcessCanceledException.class,
                    () -> PstToEmlConverter.recoverUnreferencedMessages(
                            pstFile,
                            tempDir,
                            recoveryOptions(null),
                            stats,
                            indicator,
                            ConversionLog.NOOP,
                            new HashSet<>(),
                            new HashSet<>(),
                            new HashMap<>()),
                    "checkCanceled()'s ProcessCanceledException must propagate out of the recovery pass");
            org.junit.jupiter.api.Assertions.assertNotNull(thrown);

            assertEquals(
                    0,
                    stats.failedMessages(),
                    "A cancellation during recovery must not be counted as a failed message: "
                            + stats.failedMessages());
            assertTrue(
                    stats.converted() < 4,
                    "Recovery must stop early on cancellation rather than recover every message, got "
                            + stats.converted());
        }
    }

    /** Audit 03-M4: the recovery pass honors the message-count limit independently of the folder walk. */
    @Test
    void recoveryHonorsMessageCountLimit() throws Exception {
        var multiMessagePst = Paths.get("src/test/resources/samples/pst/testPST_variousBodyTypes.pst");
        var tempDir = Files.createTempDirectory("pst_recover_limit");
        try (var pstFile = new PstFile(multiMessagePst)) {
            var stats = new PstToEmlConverter.Stats();

            PstToEmlConverter.recoverUnreferencedMessages(
                    pstFile,
                    tempDir,
                    recoveryOptions(1),
                    stats,
                    new ProgressIndicatorBase(),
                    ConversionLog.NOOP,
                    new HashSet<>(),
                    new HashSet<>(),
                    new HashMap<>());

            assertEquals(1, stats.converted(), "Recovery must stop at the message-count limit");
        }
    }

    /**
     * Audit 03-M4: a corrupt/hostile hierarchy can reference a folder as its own ancestor. The cycle
     * guard (a visited-NID set) must skip a folder already on the path instead of recursing forever;
     * the root NID is pre-seeded as visited to stand in for that self-ancestry without a crafted cyclic
     * store.
     */
    @Test
    void folderCycleGuardSkipsAnAlreadyVisitedFolder() throws Exception {
        var tempDir = Files.createTempDirectory("pst_cycle");
        try (var pstFile = new PstFile(SAMPLE)) {
            var stats = new PstToEmlConverter.Stats();
            var log = new RecordingLog();
            var visited = new HashSet<Integer>();
            visited.add(0x122); // the root folder is already on the path

            PstToEmlConverter.processFolder(
                    pstFile,
                    0x122,
                    tempDir,
                    defaultOptions(),
                    stats,
                    new ProgressIndicatorBase(),
                    "",
                    log,
                    visited,
                    new HashSet<>(),
                    new HashMap<>(),
                    0);

            assertEquals(0, stats.converted(), "an already-visited folder must not be processed again");
            assertTrue(
                    log.infos.stream().anyMatch(info -> info.contains("cycle guard")),
                    "the cycle guard must log when it skips an already-visited folder: " + log.infos);
        }
    }

    /**
     * Audit 03-M4: a deep linear (acyclic) folder chain slips past the cycle guard, so a depth cap stops
     * it before a StackOverflowError. Driving the recursion past MAX_FOLDER_DEPTH must log, count a
     * failed folder and return rather than descend.
     */
    @Test
    void folderDepthGuardStopsBeyondMaxDepth() throws Exception {
        var tempDir = Files.createTempDirectory("pst_depth");
        try (var pstFile = new PstFile(SAMPLE)) {
            var stats = new PstToEmlConverter.Stats();
            var log = new RecordingLog();

            PstToEmlConverter.processFolder(
                    pstFile,
                    0x122,
                    tempDir,
                    defaultOptions(),
                    stats,
                    new ProgressIndicatorBase(),
                    "",
                    log,
                    new HashSet<>(),
                    new HashSet<>(),
                    new HashMap<>(),
                    PstToEmlConverter.MAX_FOLDER_DEPTH + 1);

            assertEquals(0, stats.converted(), "no folder may be processed past the depth cap");
            assertEquals(1, stats.failedFolders(), "the over-deep folder is counted as a failed folder");
            assertTrue(
                    log.errors.stream().anyMatch(error -> error.contains("exceeded depth")),
                    "the depth guard must log when it stops: " + log.errors);
        }
    }

    private static PstToEmlConverter.Options recoveryOptions(Integer limit) {
        return new PstToEmlConverter.Options(
                PstToEmlConverter.DuplicateHandling.OVERWRITE,
                limit,
                false,
                true,
                Message.AddressPreference.PREFER_SMTP,
                true, // recoverDeletedItems
                true, // scanOrphans
                64L * 1024 * 1024);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }

    // F2 (audit follow-up): MAPI stores In-Reply-To/References unbracketed; the PST path must normalize
    // them to RFC 5322 §3.6.4 angle-bracketed msg-ids (dropping @-less tokens) like the MSG path does,
    // rather than emit the raw stored value.
    @Test
    void bareThreadingHeaderValuesAreAngleBracketedAndAtlessTokensDropped() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Threaded";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getStringProperty(int propertyId) {
                    if (propertyId == MapiProperties.PR_IN_REPLY_TO_ID_W) {
                        return "parent@example.com"; // bare, unbracketed
                    }
                    if (propertyId == MapiProperties.PR_INTERNET_REFERENCES_W) {
                        return "root@example.com freetext parent@example.com"; // ids + an @-less token
                    }
                    return null;
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("In-Reply-To: <parent@example.com>"), eml);
            assertTrue(eml.contains("References: <root@example.com> <parent@example.com>"), eml);
            assertFalse(eml.contains("freetext"), "an @-less token is not a msg-id and must be dropped: " + eml);
        }
    }

    // F3 (audit follow-up): on a delivery report a To-recipient row flag-stamped with high
    // PR_RECIPIENT_TYPE bits must still be recognized (masked) as the DSN Final-Recipient instead of
    // falling through to a non-To address.
    @Test
    void ndrFinalRecipientPrefersFlaggedToRowOverOtherRows() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "REPORT.IPM.Note.NDR";
                }

                @Override
                public String getSubject() {
                    return "Undeliverable: Hello";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public List<Recipient> getRecipients() {
                    // A Cc row precedes a flag-stamped To row (0x10000001). The unmasked compare missed the
                    // To row and reported the Cc address; masking recovers the real failed To recipient.
                    return List.of(
                            new Recipient(2, "Cc Person", "cc@example.com"),
                            new Recipient(0x10000001, "To Person", "to@example.com"));
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getStringProperty(int propertyId) {
                    return switch (propertyId) {
                        case 0x1001 -> "Your message could not be delivered.";
                        case 0x6820 -> "mail.relay.example.com";
                        case 0x0C1B -> "5.1.1";
                        default -> null;
                    };
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("Final-Recipient: rfc822; to@example.com"),
                    "the flagged To row must be the Final-Recipient: " + eml);
            assertFalse(
                    eml.contains("Final-Recipient: rfc822; cc@example.com"),
                    "the Cc row must not be reported as the failed recipient: " + eml);
        }
    }

    // F4 (audit follow-up): the meeting-response REPLY organizer is the To recipient even when that row
    // carries high PR_RECIPIENT_TYPE flag bits — the compare must mask first, matching the MSG path.
    @Test
    void meetingResponseOrganizerSurvivesFlaggedToRecipientType() throws Exception {
        var distListPst = Paths.get("src/test/resources/samples/pst/dist-list.pst");
        try (var pstFile = new PstFile(distListPst)) {
            var startId = pstFile.namedPropertyId(UUID.fromString("00062002-0000-0000-C000-000000000046"), 0x820D);
            assertNotNull(startId);

            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Schedule.Meeting.Resp.Pos";
                }

                @Override
                public String getSubject() {
                    return "Accepted: Sync";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getSenderName() {
                    return "Bob";
                }

                @Override
                public String getSenderEmail() {
                    return "bob@example.com";
                }

                @Override
                public List<Recipient> getRecipients() {
                    // Carol (Cc) precedes Alice (the organizer), and Alice's To row is flag-stamped
                    // (0x10000001). The unmasked filter missed Alice and promoted Carol; masking fixes it.
                    return List.of(
                            new Recipient(2, "Carol", "carol@example.com"),
                            new Recipient(0x10000001, "Alice", "alice@example.com"));
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public Object getProperty(int propertyId) {
                    if (propertyId == startId) {
                        return Instant.parse("2026-07-01T15:00:00Z");
                    }
                    return null;
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var ics = new String(
                    Base64.getMimeDecoder()
                            .decode(Pattern.compile("(?s)name=\"invite\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                                    .matcher(writer.toString())
                                    .results()
                                    .findFirst()
                                    .orElseThrow()
                                    .group(1)),
                    StandardCharsets.UTF_8);

            var organizerLine = ics.lines()
                    .filter(line -> line.startsWith("ORGANIZER"))
                    .findFirst()
                    .orElse("");
            assertTrue(organizerLine.contains("alice@example.com"), "ORGANIZER must be the flagged To row: " + ics);
            assertFalse(organizerLine.contains("carol@example.com"), "Cc must not be promoted to organizer: " + ics);
        }
    }

    // Round-15 fix: the meeting-REPLY organizer fallback streamed ALL recipients with no BCC mask, so a
    // BCC-class recipient could be promoted to iCal ORGANIZER. [MS-OXOMSG] §2.2.3.1 defines the class
    // bits; RFC 5546 §3.2.3 requires the ORGANIZER to be the original meeting organizer. MSG excludes
    // BCC via visibleAttendees; PST's secondary filter must match with the same guard.
    //
    // Scenario: BCC recipient (bcc@example.com) appears FIRST in the table, CC recipient
    // (cc@example.com) appears second, no To-class recipient carries an address. The primary
    // To-filter finds nothing, so the fallback runs. Old behavior promotes bcc@example.com; new
    // behavior skips the BCC row (type & 0x0FFFFFFF == RECIPIENT_TYPE_BCC) and selects cc@example.com.
    @Test
    void meetingResponseReplyOrganizerSkipsBccAndFallsBackToCC() throws Exception {
        var distListPst = Paths.get("src/test/resources/samples/pst/dist-list.pst");
        try (var pstFile = new PstFile(distListPst)) {
            var startId = pstFile.namedPropertyId(UUID.fromString("00062002-0000-0000-C000-000000000046"), 0x820D);
            assertNotNull(startId);

            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Schedule.Meeting.Resp.Pos";
                }

                @Override
                public String getSubject() {
                    return "Accepted: Team Sync";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getSenderName() {
                    return "Attendee";
                }

                @Override
                public String getSenderEmail() {
                    return "attendee@example.com";
                }

                @Override
                public List<Recipient> getRecipients() {
                    // BCC recipient appears first; CC recipient appears second; no To recipient has
                    // an address. The primary To-filter finds nothing, the fallback fires. Old code
                    // would pick the first address-bearing row (BCC); new code must skip
                    // RECIPIENT_TYPE_BCC (3) and select the CC row (2) instead.
                    return List.of(
                            new Recipient(EmlSerializer.RECIPIENT_TYPE_BCC, "Blind Copy", "bcc@example.com"),
                            new Recipient(EmlSerializer.RECIPIENT_TYPE_CC, "Meeting Chair", "cc@example.com"));
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public Object getProperty(int propertyId) {
                    if (propertyId == startId) {
                        return Instant.parse("2026-08-01T10:00:00Z");
                    }
                    return null;
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var ics = new String(
                    Base64.getMimeDecoder()
                            .decode(Pattern.compile("(?s)name=\"invite\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                                    .matcher(writer.toString())
                                    .results()
                                    .findFirst()
                                    .orElseThrow()
                                    .group(1)),
                    StandardCharsets.UTF_8);

            assertTrue(ics.contains("METHOD:REPLY"), "method must be REPLY: " + ics);
            var organizerLine = ics.lines()
                    .filter(line -> line.startsWith("ORGANIZER"))
                    .findFirst()
                    .orElse("");
            // New behavior: BCC is skipped, CC is selected as the fallback organizer.
            assertTrue(
                    organizerLine.contains("cc@example.com"),
                    "ORGANIZER fallback must skip BCC and select the CC recipient: " + ics);
            // Old (buggy) behavior: BCC was promoted to ORGANIZER because the filter had no BCC mask.
            assertFalse(
                    organizerLine.contains("bcc@example.com"),
                    "A BCC-class recipient must never be promoted to ORGANIZER: " + ics);
        }
    }

    // F5 (audit follow-up): a meeting REQUEST must not list a Bcc-class recipient as an iCal ATTENDEE
    // (RFC 5546) — a blind copy would otherwise leak into a property every invitee can read.
    @Test
    void meetingRequestExcludesBccRecipientsFromAttendees() throws Exception {
        var distListPst = Paths.get("src/test/resources/samples/pst/dist-list.pst");
        try (var pstFile = new PstFile(distListPst)) {
            var startId = pstFile.namedPropertyId(UUID.fromString("00062002-0000-0000-C000-000000000046"), 0x820D);
            assertNotNull(startId);

            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Schedule.Meeting.Request";
                }

                @Override
                public String getSubject() {
                    return "Sync";
                }

                @Override
                public String getBody() {
                    return "";
                }

                @Override
                public String getSenderName() {
                    return "Organizer";
                }

                @Override
                public String getSenderEmail() {
                    return "org@example.com";
                }

                @Override
                public List<Recipient> getRecipients() {
                    // 1 = To, 3 = Bcc. The blind copy (Dave) must not surface as an ATTENDEE.
                    return List.of(
                            new Recipient(1, "Alice", "alice@example.com"),
                            new Recipient(3, "Dave", "dave@example.com"));
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public Object getProperty(int propertyId) {
                    if (propertyId == startId) {
                        return Instant.parse("2026-07-01T15:00:00Z");
                    }
                    return null;
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var ics = new String(
                    Base64.getMimeDecoder()
                            .decode(Pattern.compile("(?s)name=\"invite\\.ics\".*?base64\r\n.*?\r\n\r\n(.*?)\r\n--")
                                    .matcher(writer.toString())
                                    .results()
                                    .findFirst()
                                    .orElseThrow()
                                    .group(1)),
                    StandardCharsets.UTF_8);

            assertTrue(ics.contains("METHOD:REQUEST"), "method must be REQUEST: " + ics);
            assertTrue(
                    ics.lines().anyMatch(line -> line.startsWith("ATTENDEE") && line.contains("alice@example.com")),
                    "the To recipient must be an attendee: " + ics);
            assertFalse(ics.contains("dave@example.com"), "the Bcc recipient must not leak into the invite: " + ics);
        }
    }

    // F7 (audit follow-up): when the recipient table is empty and a display string holds a bare SMTP
    // address, it belongs in the address slot (not the display-name slot), matching the MSG path.
    @Test
    void displayStringFallbackPutsBareAddressInTheAddressSlot() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "IPM.Note";
                }

                @Override
                public String getSubject() {
                    return "Subject";
                }

                @Override
                public String getBody() {
                    return "Body";
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getTo() {
                    return "bare@example.com; Plain Name";
                }
            };

            var serializer = PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP);
            var writer = new StringWriter();
            serializer.writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("<bare@example.com>"),
                    "a bare SMTP address must be emitted as an address, not a quoted name: " + eml);
            assertFalse(
                    eml.contains("\"bare@example.com\""),
                    "the address must not be placed in the display-name slot: " + eml);
        }
    }

    /**
     * Round-20 fix 2 (PST) — REPORT human-readable body fallback.
     *
     * <p>When PidTagReportText (0x1001) is absent or blank, {@code PstToEmlConverter.emitReport}
     * must use PR_BODY (via {@code message.getBody()}) as the human-readable first part of the
     * multipart/report. Pre-fix, a blank PidTagReportText left part 1 as ReportGenerator's terse
     * stub "This is a delivery status notification…". With the fix the actual body text is used.
     */
    @Test
    void pstNdrReportUsesPlainBodyWhenReportTextAbsent() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var failureText = "Delivery has failed to these recipients:\n  carol@example.com";
            var message = new Message(pstFile, 0x122) {
                @Override
                public String getMessageClass() {
                    return "REPORT.IPM.Note.NDR";
                }

                @Override
                public String getSubject() {
                    return "Undeliverable: Hello";
                }

                @Override
                public String getBody() {
                    return failureText;
                }

                @Override
                public List<Recipient> getRecipients() {
                    return List.of();
                }

                @Override
                public List<Attachment> getAttachments() {
                    return List.of();
                }

                @Override
                public String getStringProperty(int propertyId) {
                    // PidTagReportText (0x1001) is absent — returns null so the body fallback fires.
                    return null;
                }
            };

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(eml.contains("multipart/report"), "NDR must produce multipart/report: " + eml);
            // PR_BODY must feed part 1 when PidTagReportText is absent.
            assertTrue(
                    eml.contains("Delivery has failed to these recipients"),
                    "PR_BODY must appear as the human-readable part when reportText is absent: " + eml);
            // Pre-fix: the stub sentence appeared when 0x1001 returned null.
            assertFalse(
                    eml.contains("This is a delivery status notification"),
                    "The ReportGenerator stub must not appear when a real body text is available: " + eml);
        }
    }

    /**
     * Round-20 fix 4 — PST embedded-message part named from inner subject when display name is blank.
     *
     * <p>When the attached embedded message's {@link EmbeddedAttachmentStub#getDisplayName()} is
     * blank (resolves to the literal "message"), the fix falls back to
     * {@code embedMessage.getSubject()} so a meaningful filename is emitted — parity with the MSG
     * round-19 fix. Pre-fix, the part was always named "message.eml" when the display name was
     * blank or missing.
     */
    @Test
    void pstEmbeddedMessagePartNamedFromInnerSubjectWhenDisplayNameBlank() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            var innerSubject = "Quarterly Report";
            var inner = new StubMessage(pstFile, innerSubject, List.of(), null, "");
            // EmbeddedAttachmentStub returns "" for both getLongFilename() and getFilename();
            // getDisplayName() also returns "" so attachName resolves to the literal "message"
            // before the fix's inner-subject fallback.
            var blankNameAttach = new EmbeddedAttachmentStub("");
            var host = new StubMessage(pstFile, "Host message", List.of(blankNameAttach), inner, "");

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(host, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            // With the fix the inner subject is used; pre-fix it would be "message.eml".
            assertTrue(
                    eml.contains("name=\"Quarterly Report.eml\""),
                    "embedded message with blank display name must be named from inner subject: " + eml);
            assertFalse(
                    eml.contains("name=\"message.eml\""),
                    "the generic 'message' fallback must not appear when the inner subject is non-blank: " + eml);
        }
    }

    // Round-21 — Fix #6: uniqueEmbeddedName now runs the base through EmlSerializer.sanitizeFilename
    // so a subject containing ':' or '/' does not leak into the name=/filename= parameter as a
    // literal colon or slash (which is a path-separator on many filesystems). Pre-fix, the display
    // name was used verbatim and "RE: Quarterly results" became name="RE: Quarterly results.eml".

    @Test
    void embeddedMessageWithColonInSubjectGetsSanitizedFilename() throws Exception {
        try (var pstFile = new PstFile(SAMPLE)) {
            // Display name contains ':' — should be replaced by '_' by sanitizeFilename.
            var inner = new StubMessage(pstFile, "RE: Quarterly results", List.of(), null, "");
            var colonyAttach = new EmbeddedAttachmentStub("RE: Quarterly results");
            var host = new StubMessage(pstFile, "Host", List.of(colonyAttach), inner, "");

            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(host, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            // Colon must be replaced; the sanitized form uses '_'.
            assertTrue(
                    eml.contains("name=\"RE_ Quarterly results.eml\""),
                    "colon in display name must be sanitized to underscore: " + eml);
            assertFalse(
                    eml.contains("name=\"RE: Quarterly results.eml\""),
                    "raw colon must not appear in the name= parameter: " + eml);
        }
    }

    // -----------------------------------------------------------------------
    // Round-22 audit tests
    // -----------------------------------------------------------------------

    // Fix NPCLASS-1 — isAllowedMessageClass uses case-insensitive matching so non-canonical
    // casings ("ipm.note", "IPM.NOTE") are accepted the same as the canonical form.

    @Test
    void allowedMessageClassMatchingIsCaseInsensitive() {
        // Canonical form — always accepted.
        assertTrue(PstToEmlConverter.isAllowedMessageClass("IPM.Note", false), "canonical IPM.Note must be accepted");
        // All-lowercase variant.
        assertTrue(
                PstToEmlConverter.isAllowedMessageClass("ipm.note", false), "all-lowercase ipm.note must be accepted");
        // All-uppercase variant.
        assertTrue(
                PstToEmlConverter.isAllowedMessageClass("IPM.NOTE", false), "all-uppercase IPM.NOTE must be accepted");
        // Mixed-case subclass (Report.* prefix is allowed).
        assertTrue(
                PstToEmlConverter.isAllowedMessageClass("Report.Ipm.Note.NDR", false),
                "mixed-case Report.Ipm.Note.NDR must be accepted");
        // A genuinely unknown class must still be blocked regardless of case.
        assertFalse(
                PstToEmlConverter.isAllowedMessageClass("IPM.UnknownGarbage", false),
                "unknown class must remain blocked");
    }

    // Fix ATT-3 — when PR_ATTACH_LONG_FILENAME and PR_ATTACH_FILENAME are both empty but
    // PR_ATTACH_EXTENSION is present, the fallback filename is "attachment.<ext>" not "attachment.dat".

    @Test
    void attachmentWithExtensionButNoFilenameUsesExtensionInFallbackName() throws Exception {
        var pdfAttach = new Attachment() {
            @Override
            public String getLongFilename() {
                return "";
            }

            @Override
            public String getFilename() {
                return "";
            }

            @Override
            public String getExtension() {
                return ".pdf";
            }

            @Override
            public int getAttachMethod() {
                return 1; // afByValue
            }

            @Override
            public byte[] getData() {
                return new byte[] {1, 2, 3};
            }

            @Override
            public String getMimeTag() {
                return "application/pdf";
            }

            @Override
            public String getContentId() {
                return null;
            }

            @Override
            public String getContentLocation() {
                return null;
            }

            @Override
            public boolean isInline() {
                return false;
            }
        };
        try (var pstFile = new PstFile(SAMPLE)) {
            var message = new StubMessage(pstFile, "Extension fallback", List.of(pdfAttach), null, "");
            var writer = new StringWriter();
            PstToEmlConverter.createSerializer(message, defaultOptions(), pstFile, ConversionLog.NOOP)
                    .writeTo(writer);
            var eml = writer.toString();

            assertTrue(
                    eml.contains("attachment.pdf"),
                    "PR_ATTACH_EXTENSION '.pdf' must produce filename 'attachment.pdf': " + eml);
            assertFalse(
                    eml.contains("attachment.dat"),
                    "generic fallback 'attachment.dat' must not be used when extension is known: " + eml);
        }
    }
}
