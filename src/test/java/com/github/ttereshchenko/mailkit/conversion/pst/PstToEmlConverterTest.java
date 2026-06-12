package com.github.ttereshchenko.mailkit.conversion.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.pst.Attachment;
import com.github.ttereshchenko.mailkit.pst.MapiProperties;
import com.github.ttereshchenko.mailkit.pst.Message;
import com.github.ttereshchenko.mailkit.pst.NodeEntry;
import com.github.ttereshchenko.mailkit.pst.PstFile;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
        assertEquals("PUBLISH", PstToEmlConverter.icalMethod("IPM.Appointment", true));
        assertEquals("PUBLISH", PstToEmlConverter.icalMethod("IPM.Appointment", false));
        assertEquals("PUBLISH", PstToEmlConverter.icalMethod("IPM.Schedule.Meeting.Request", false));
        assertEquals("REQUEST", PstToEmlConverter.icalMethod("IPM.Schedule.Meeting.Request", true));
        assertEquals("CANCEL", PstToEmlConverter.icalMethod("IPM.Schedule.Meeting.Canceled", true));
        assertEquals("REPLY", PstToEmlConverter.icalMethod("IPM.Schedule.Meeting.Resp.Pos", true));
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
        assertTrue(ics.contains("DTSTART;TZID=MailKit-Local:20160802T080000"), ics);
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;INTERVAL=1;WKST=SU;BYDAY=TU"), ics);
        assertTrue(ics.contains("EXDATE;TZID=MailKit-Local:20160809T080000"), ics);
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
