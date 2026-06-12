package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;

/**
 * F2 coverage: an OLE attachment (attach method 6, {@code afStorage}) stores its object as a
 * PT_OBJECT property whose parsed value is the subnode NID. {@link Attachment#getObjectData()}
 * must materialize those bytes — previously they were unreachable through the public API and the
 * converter dropped the attachment.
 */
class AttachmentOleObjectTest {

    private static final long SL_BID = 0x06;
    private static final long PC_BID = 0x0C;
    private static final long OBJECT_DATA_BID = 0x10;
    private static final int OBJECT_NID = 0x42; // low 5 bits non-zero
    private static final byte[] OLE_BYTES = "ole-compound-document-bytes".getBytes(StandardCharsets.US_ASCII);

    @Test
    void objectDataMaterializesTheOleSubnode() throws Exception {
        var store = Files.createTempFile("ole_attachment", ".pst");
        try {
            writeStore(store);
            try (var channel = FileChannel.open(store, StandardOpenOption.READ)) {
                var nodeDatabase = new NodeDatabase(
                        channel, PstFile.Format.UNICODE, PstFile.EncryptionType.NONE, 0, 512, 64L * 1024 * 1024);
                var attachmentNode = new NodeEntry(0x8025, 0, SL_BID, 0);
                var propertyContext =
                        new PropertyContext(nodeDatabase.readNodeData(PC_BID), nodeDatabase, attachmentNode);
                var attachment = new Attachment(propertyContext);

                assertEquals(6, attachment.getAttachMethod());
                assertNull(attachment.getData(), "An OLE object is not PT_BINARY content");
                assertArrayEquals(OLE_BYTES, attachment.getObjectData(), "The OLE storage bytes must be reachable");
            }
        } finally {
            Files.deleteIfExists(store);
        }
    }

    /**
     * BBT leaf page at 0, empty NBT page at 512; the attachment PC (PT_OBJECT pointing at subnode
     * {@value #OBJECT_NID} plus attach method 6) and the subnode tree carrying the object bytes.
     */
    private static void writeStore(java.nio.file.Path store) throws Exception {
        long slOffset = 1024;
        long pcOffset = 2048;
        long dataOffset = 3072;
        var pcHeap = buildAttachmentPcHeap();

        try (var file = new RandomAccessFile(store.toFile(), "rw")) {
            file.setLength(dataOffset + OLE_BYTES.length);

            var bbt = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
            writeBbtEntry(bbt, 0, SL_BID, slOffset, 8 + 24);
            writeBbtEntry(bbt, 1, PC_BID, pcOffset, pcHeap.length);
            writeBbtEntry(bbt, 2, OBJECT_DATA_BID, dataOffset, OLE_BYTES.length);
            bbt.put(488, (byte) 3); // cEnt
            bbt.put(490, (byte) 24); // cbEnt
            bbt.put(491, (byte) 0); // cLevel = leaf
            file.seek(0);
            file.write(bbt.array());

            var slBlock = ByteBuffer.allocate(8 + 24).order(ByteOrder.LITTLE_ENDIAN);
            slBlock.put(0, (byte) 0x02);
            slBlock.put(1, (byte) 0);
            slBlock.putShort(2, (short) 1);
            slBlock.putLong(8, OBJECT_NID);
            slBlock.putLong(16, OBJECT_DATA_BID);
            slBlock.putLong(24, 0);
            file.seek(slOffset);
            file.write(slBlock.array());

            file.seek(pcOffset);
            file.write(pcHeap);

            file.seek(dataOffset);
            file.write(OLE_BYTES);
        }
    }

    private static void writeBbtEntry(ByteBuffer page, int index, long bid, long fileOffset, int size) {
        page.putLong(index * 24, bid);
        page.putLong(index * 24 + 8, fileOffset);
        page.putShort(index * 24 + 16, (short) size);
        page.putShort(index * 24 + 18, (short) 1); // cRef
    }

    /**
     * The attachment PC: item 1 = BTH header, item 2 = two records (PR_ATTACH_DATA_BIN as PT_OBJECT
     * via the HNID of item 3, PR_ATTACH_METHOD = 6 inline), item 3 = the {Nid, ulSize} object struct.
     */
    private static byte[] buildAttachmentPcHeap() {
        var heap = ByteBuffer.allocate(70).order(ByteOrder.LITTLE_ENDIAN);
        heap.putShort(0, (short) 48); // ibHnpm
        heap.put(2, (byte) 0xEC); // bSig
        heap.putInt(4, 0x20); // hidUserRoot -> item 1

        // Item 1 [16, 24): BTH header — cbKey=2, cbEnt=6, leaf, root at item 2.
        heap.put(16, (byte) 0xB5);
        heap.put(17, (byte) 2);
        heap.put(18, (byte) 6);
        heap.put(19, (byte) 0);
        heap.putInt(20, 0x40); // hidRoot -> item 2

        // Item 2 [24, 40): the records, sorted by tag.
        heap.putShort(24, (short) MapiProperties.PR_ATTACH_DATA_BIN);
        heap.putShort(26, (short) 0x000D); // PT_OBJECT
        heap.putInt(28, 0x60); // HNID -> item 3 ({Nid, ulSize})
        heap.putShort(32, (short) MapiProperties.PR_ATTACH_METHOD);
        heap.putShort(34, (short) 0x0003); // PT_LONG
        heap.putInt(36, 6); // afStorage

        // Item 3 [40, 48): {Nid, ulSize} ([MS-PST] §2.3.3.5).
        heap.putInt(40, OBJECT_NID);
        heap.putInt(44, OLE_BYTES.length);

        // Page map at 48: cAlloc=3, rgibAlloc = {16, 24, 40, 48}.
        heap.putShort(48, (short) 3);
        heap.putShort(50, (short) 0);
        heap.putShort(52, (short) 16);
        heap.putShort(54, (short) 24);
        heap.putShort(56, (short) 40);
        heap.putShort(58, (short) 48);
        return heap.array();
    }
}
