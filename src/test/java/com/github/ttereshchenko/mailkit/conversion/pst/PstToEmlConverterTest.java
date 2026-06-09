package com.github.ttereshchenko.mailkit.conversion.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.pst.Attachment;
import com.github.ttereshchenko.mailkit.pst.Message;
import com.github.ttereshchenko.mailkit.pst.NodeEntry;
import com.github.ttereshchenko.mailkit.pst.PstFile;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
