package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the library's primary use case — open a real PST/OST file and walk it from
 * the root folder down to individual {@link Message}s — across every supported format (ANSI,
 * UNICODE, UNICODE_2013/OST), plus header-resilience cases (truncation, unknown encryption byte)
 * and password-protected stores. Complements the unit tests that exercise the NDB/HN/PC/TC
 * internals in isolation.
 */
class PstFileTest {

    private static final int NID_ROOT_FOLDER = 0x122;

    private static Path fixture(String name) throws Exception {
        var url = PstFileTest.class.getResource("/samples/pst/" + name);
        assertNotNull(url, "Missing test fixture: " + name);
        return Path.of(url.toURI());
    }

    @Test
    void opensHeaderAndReportsFormat() throws Exception {
        try (var pst = new PstFile(fixture("dist-list.pst"))) {
            assertEquals(PstFile.Format.UNICODE, pst.format());
            assertNotNull(pst.encryptionType());
            assertNotNull(pst.nodeDatabase());
            assertFalse(pst.isPasswordProtected(), "dist-list.pst carries no Outlook password");
        }
    }

    @Test
    void walksFoldersAndReadsAMessage() throws Exception {
        try (var pst = new PstFile(fixture("dist-list.pst"))) {
            var root = new Folder(pst, NID_ROOT_FOLDER);
            assertTrue(root.isLoaded(), "The root folder of a healthy store must load");
            assertNotNull(root.getDisplayName());

            var messageNids = collectMessageNids(root);
            assertFalse(messageNids.isEmpty(), "Expected to discover at least one message while walking the store");

            var message = new Message(pst, messageNids.get(0));
            assertTrue(message.isLoaded(), "A message of a healthy store must load");
            // The message must be readable end-to-end without throwing; exact content varies by item.
            assertDoesNotThrow(message::getSubject);
            assertNotNull(message.getMessageClass());
        }
    }

    @Test
    void readsGenuineAnsiStore() throws Exception {
        try (var pst = new PstFile(fixture("ansi-test.pst"))) {
            assertEquals(PstFile.Format.ANSI, pst.format());
            var root = new Folder(pst, NID_ROOT_FOLDER);
            var messageNids = collectMessageNids(root);
            assertFalse(messageNids.isEmpty(), "Expected messages in the ANSI sample store");
            var message = new Message(pst, messageNids.get(0));
            assertNotNull(message.getSubject());
            assertNotNull(message.getBody());
            assertDoesNotThrow(message::getRecipients);
            assertDoesNotThrow(message::getAttachments);
        }
    }

    @Test
    void opensMinimalAnsiStore() throws Exception {
        try (var pst = new PstFile(fixture("dummy_ansi.pst"))) {
            assertEquals(PstFile.Format.ANSI, pst.format());
            var root = new Folder(pst, NID_ROOT_FOLDER);
            assertDoesNotThrow(root::getSubFolders);
            assertDoesNotThrow(root::getMessages);
        }
    }

    @Test
    void readsUnicode2013OstWithCompressedBlocks() throws Exception {
        try (var pst = new PstFile(fixture("example-2013.ost"))) {
            assertEquals(PstFile.Format.UNICODE_2013, pst.format());
            var root = new Folder(pst, NID_ROOT_FOLDER);
            var messageNids = collectMessageNids(root);
            assertEquals(
                    3,
                    messageNids.size(),
                    "Expected exactly 3 messages to be extracted from the 2013 OST (ZLIB decompression)");
            var message = new Message(pst, messageNids.get(0));
            assertNotNull(message.getSubject());
        }
    }

    @Test
    void readsPasswordProtectedStoreAndReportsTheFlag() throws Exception {
        // The Outlook "password" is only a CRC on the message store object; content is not encrypted
        // with it, so the store reads normally and the flag is surfaced for callers that want to warn.
        try (var pst = new PstFile(fixture("passworded.pst"))) {
            assertTrue(pst.isPasswordProtected(), "passworded.pst must report its password CRC");
            var root = new Folder(pst, NID_ROOT_FOLDER);
            assertDoesNotThrow(root::getSubFolders);
        }
    }

    @Test
    void resolvesEmbeddedMessageAttachments() throws Exception {
        // tika-testPST.pst's attachment is an embedded message (afEmbeddedMessage); binary-content
        // attachments are covered against a synthetic store in TableContextTest, since no sample
        // archive in the repository carries one.
        try (var pst = new PstFile(fixture("tika-testPST.pst"))) {
            var attachments = collectAttachments(pst, new Folder(pst, NID_ROOT_FOLDER));
            assertFalse(attachments.isEmpty(), "Expected at least one attachment in tika-testPST.pst");
            boolean sawEmbedded = false;
            for (var attachment : attachments) {
                assertNotNull(attachment.getLongFilename());
                assertNotNull(attachment.getMimeTag());
                if (attachment.getAttachMethod() != 5) { // afEmbeddedMessage
                    continue;
                }
                sawEmbedded = true;
                Integer embeddedNid = attachment.getEmbeddedMessageNodeId();
                assertNotNull(embeddedNid, "An embedded-message attachment must expose its node id");
                var embeddedEntry = pst.readSubnodeEntry(attachment.getNode().subBid(), embeddedNid);
                assertNotNull(embeddedEntry, "The embedded message must resolve in the sub-node tree");
                var embedded = new Message(pst, embeddedEntry);
                assertTrue(embedded.isLoaded(), "The embedded message must be readable");
                assertNotNull(embedded.getSubject());
            }
            assertTrue(sawEmbedded, "Expected the embedded-message attachment");
        }
    }

    /**
     * C3: none of the bundled fixtures records a store-wide code page on the message store object,
     * so the accessor must report that as {@code null} (repeatedly — the result is cached) instead
     * of failing; the charset chain then ends at windows-1252.
     */
    @Test
    void storeCodePageIsNullWhenTheStoreRecordsNone() throws Exception {
        try (var pst = new PstFile(fixture("dist-list.pst"))) {
            assertNull(pst.storeCodePage(), "dist-list.pst's store object carries no code page");
            assertNull(pst.storeCodePage(), "the cached second read must agree");
        }
    }

    /**
     * The {@link Message#readEmbeddedMessage} seam resolves submessage.pst's embedded message
     * (fixture from the pstsdk test corpus, Apache-2.0) — including the PR_DISPLAY_NAME that names
     * the exported .eml when no filename properties exist.
     */
    @Test
    void readsEmbeddedMessageThroughTheMessageSeam() throws Exception {
        try (var pst = new PstFile(fixture("submessage.pst"))) {
            var root = new Folder(pst, NID_ROOT_FOLDER);
            boolean sawEmbedded = false;
            for (var entry : collectMessagesWithAttachments(pst, root)) {
                for (var attachment : entry.getAttachments()) {
                    if (attachment.getAttachMethod() != 5) { // afEmbeddedMessage
                        continue;
                    }
                    sawEmbedded = true;
                    var embedded = entry.readEmbeddedMessage(attachment);
                    assertNotNull(embedded, "The embedded message must resolve through the seam");
                    assertEquals("This is an embedded message", embedded.getSubject());
                    assertTrue(
                            embedded.getBody().contains("This is the body of an embedded message"),
                            "The embedded body must be readable");
                    assertEquals(
                            "This is an embedded message",
                            attachment.getDisplayName(),
                            "Embedded attachments carry their name in PR_DISPLAY_NAME");
                }
            }
            assertTrue(sawEmbedded, "Expected submessage.pst to contain an embedded-message attachment");
        }
    }

    private static List<Message> collectMessagesWithAttachments(PstFile pst, Folder folder) throws Exception {
        var messages = new ArrayList<Message>();
        for (int nid : folder.getMessages()) {
            var message = new Message(pst, nid);
            if (message.hasAttachments() || !message.getAttachments().isEmpty()) {
                messages.add(message);
            }
        }
        for (var child : folder.getSubFolders()) {
            messages.addAll(collectMessagesWithAttachments(pst, child));
        }
        return messages;
    }

    @Test
    void truncatedHeaderFailsWithPstException() throws Exception {
        byte[] full = Files.readAllBytes(fixture("dist-list.pst"));
        Path twoBytes = Files.createTempFile("truncated_magic", ".pst");
        Path headerOnly = Files.createTempFile("truncated_after_header", ".pst");
        try {
            Files.write(twoBytes, Arrays.copyOf(full, 2));
            assertThrows(PstException.class, () -> new PstFile(twoBytes).close());

            // A valid header whose b-tree roots point past the truncated tail must also fail cleanly
            // at open (the root pages are validated eagerly).
            Files.write(headerOnly, Arrays.copyOf(full, 600));
            assertThrows(PstException.class, () -> new PstFile(headerOnly).close());
        } finally {
            Files.deleteIfExists(twoBytes);
            Files.deleteIfExists(headerOnly);
        }
    }

    @Test
    void unknownEncryptionByteFailsAtOpen() throws Exception {
        // bCryptMethod may only be 0, 1 or 2 ([MS-PST] §2.2.2.6); decoding blocks with an unknown
        // scheme would silently produce garbage, so a corrupted byte must fail at open.
        byte[] full = Files.readAllBytes(fixture("dist-list.pst"));
        full[513] = 0x07; // Unicode bCryptMethod offset
        Path patched = Files.createTempFile("bad_crypt", ".pst");
        try {
            Files.write(patched, full);
            var failure = assertThrows(PstException.class, () -> new PstFile(patched).close());
            assertTrue(failure.getMessage().contains("encryption"), "Expected the crypt-byte diagnostic");
        } finally {
            Files.deleteIfExists(patched);
        }
    }

    @Test
    void rejectsImplausibleMaxNodeSize() throws Exception {
        var path = fixture("dist-list.pst");
        assertThrows(IllegalArgumentException.class, () -> new PstFile(path, 0).close());
        assertThrows(IllegalArgumentException.class, () -> new PstFile(path, -1).close());
    }

    private static List<Integer> collectMessageNids(Folder folder) throws PstException {
        var nids = new ArrayList<>(folder.getMessages());
        for (var child : folder.getSubFolders()) {
            nids.addAll(collectMessageNids(child));
        }
        return nids;
    }

    private static List<Attachment> collectAttachments(PstFile pst, Folder folder) throws PstException {
        var attachments = new ArrayList<Attachment>();
        for (var nid : folder.getMessages()) {
            attachments.addAll(new Message(pst, nid).getAttachments());
        }
        for (var child : folder.getSubFolders()) {
            attachments.addAll(collectAttachments(pst, child));
        }
        return attachments;
    }
}
