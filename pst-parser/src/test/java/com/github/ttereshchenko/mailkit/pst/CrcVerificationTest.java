package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;

/**
 * F7 coverage for opt-in CRC verification ([MS-PST] §5.3): real Unicode and ANSI stores must walk
 * fully with verification on (which pins the CRC algorithm and the trailer offsets against
 * Outlook-written data), and corrupting a single byte of a block or of a b-tree page must turn the
 * previously silent bit rot into a {@link PstException}.
 */
class CrcVerificationTest {

    @Test
    void realStoresWalkFullyWithVerificationEnabled() throws Exception {
        for (var fixtureName : new String[] {"tika-testPST.pst", "ansi-test.pst"}) {
            try (var pst = new PstFile(fixture(fixtureName), PstFile.DEFAULT_MAX_NODE_SIZE, true)) {
                var messagesRead = walk(pst, new Folder(pst, 0x122));
                assertTrue(messagesRead > 0, fixtureName + " must yield messages with CRC verification on");
            }
        }
    }

    @Test
    void corruptedBlockFailsWithCrcMismatch() throws Exception {
        var corrupted = Files.createTempFile("crc_block_corrupt", ".pst");
        try {
            Files.copy(fixture("tika-testPST.pst"), corrupted, StandardCopyOption.REPLACE_EXISTING);

            // Locate the message-store node's data block in the intact store, then flip one byte
            // inside its data area in the copy.
            long blockOffset;
            try (var pst = new PstFile(corrupted)) {
                var storeNode = pst.getNode(0x21);
                blockOffset = pst.nodeDatabase().getBlock(storeNode.dataBid()).offset();
            }
            try (var channel = FileChannel.open(corrupted, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                var oneByte = ByteBuffer.allocate(1);
                channel.read(oneByte, blockOffset + 5);
                oneByte.put(0, (byte) (oneByte.get(0) ^ 0xFF));
                oneByte.rewind();
                channel.write(oneByte, blockOffset + 5);
            }

            // Without verification the corruption passes silently (here: a property value changes or
            // decodes to garbage); with verification reading the node fails loudly.
            try (var pst = new PstFile(corrupted)) {
                pst.isPasswordProtected(); // reads the corrupted block without complaint
            }
            try (var pst = new PstFile(corrupted, PstFile.DEFAULT_MAX_NODE_SIZE, true)) {
                var exception = assertThrows(PstException.class, pst::isPasswordProtected);
                assertTrue(
                        exception.getMessage().contains("CRC mismatch"),
                        "The failure must name the CRC mismatch: " + exception.getMessage());
            }
        } finally {
            Files.deleteIfExists(corrupted);
        }
    }

    @Test
    void corruptedBtreePageFailsAtOpen() throws Exception {
        var corrupted = Files.createTempFile("crc_page_corrupt", ".pst");
        try {
            Files.copy(fixture("tika-testPST.pst"), corrupted, StandardCopyOption.REPLACE_EXISTING);

            // The Unicode BBT root page offset lives at header offset 240 (BREFBBT.ib).
            long bbtRootOffset;
            try (var channel = FileChannel.open(corrupted, StandardOpenOption.READ)) {
                var bref = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                channel.read(bref, 240);
                bbtRootOffset = bref.getLong(0);
            }
            try (var channel = FileChannel.open(corrupted, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                var oneByte = ByteBuffer.allocate(1);
                channel.read(oneByte, bbtRootOffset + 3);
                oneByte.put(0, (byte) (oneByte.get(0) ^ 0xFF));
                oneByte.rewind();
                channel.write(oneByte, bbtRootOffset + 3);
            }

            // The root pages are validated eagerly, so with verification the open itself fails.
            var exception = assertThrows(
                    PstException.class, () -> new PstFile(corrupted, PstFile.DEFAULT_MAX_NODE_SIZE, true).close());
            assertTrue(
                    exception.getMessage().contains("CRC mismatch"),
                    "The failure must name the CRC mismatch: " + exception.getMessage());
        } finally {
            Files.deleteIfExists(corrupted);
        }
    }

    /** Reads every folder, message, body and attachment payload so each data block gets verified. */
    private static int walk(PstFile pst, Folder folder) throws Exception {
        var messagesRead = 0;
        for (int nid : folder.getMessages()) {
            var message = new Message(pst, nid);
            assertFalse(message.getSubject() == null, "Subjects must read");
            message.getBody();
            message.getHtmlBody();
            message.getRecipients();
            for (var attachment : message.getAttachments()) {
                attachment.getData();
            }
            messagesRead++;
        }
        for (var subFolder : folder.getSubFolders()) {
            messagesRead += walk(pst, subFolder);
        }
        return messagesRead;
    }

    private static Path fixture(String name) throws URISyntaxException {
        var resource = CrcVerificationTest.class.getResource("/samples/pst/" + name);
        return Path.of(resource.toURI());
    }
}
