package com.github.ttereshchenko.mailkit.conversion.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
