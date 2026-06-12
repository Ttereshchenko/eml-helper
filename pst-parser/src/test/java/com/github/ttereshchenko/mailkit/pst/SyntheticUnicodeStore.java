package com.github.ttereshchenko.mailkit.pst;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Writes a minimal but complete Unicode PST that {@link PstFile} can open: header, NBT/BBT root
 * pages, and one {@code IPM.Note} message whose recipient and attachment tables live in subnodes
 * with their row matrices in the tables' own <em>nested</em> subnode trees ([MS-PST]
 * §2.2.2.8.3.3 {@code SLENTRY.bidSub}) — the on-disk shape Outlook uses once a table outgrows the
 * heap, and the shape the F1 review finding showed was silently lost.
 *
 * <p>The store can be written with any {@link PstFile.EncryptionType}; leaf data blocks are
 * encoded with the matching scheme (internal SL blocks stay unencoded, as in real stores), which
 * also gives the HIGH ("strong") scheme an end-to-end fixture no public archive provides.
 */
final class SyntheticUnicodeStore {

    static final int MESSAGE_NID = 0x200004;
    static final String MESSAGE_CLASS = "IPM.Note";
    static final String SUBJECT = "Large tables";
    static final String FIRST_RECIPIENT_NAME = "Recipient Zero";
    static final String ATTACHMENT_FILENAME = "report.txt";
    static final byte[] ATTACHMENT_CONTENT = "hello attachment".getBytes(StandardCharsets.US_ASCII);

    private static final int NID_ATTACHMENT_TABLE = 0x0671;
    private static final int NID_RECIPIENT_TABLE = 0x0692;
    private static final int ATTACHMENT_NID = 0x8025;
    private static final int ROW_MATRIX_NID = 0x22; // low 5 bits non-zero -> subnode HNID

    // BIDs increment by 4; internal (SL) blocks carry the 0x02 flag.
    private static final long PC_BID = 0x04;
    private static final long MSG_SL_BID = 0x0A;
    private static final long TC_RECIPIENTS_BID = 0x0C;
    private static final long NESTED_SL_RECIPIENTS_BID = 0x12;
    private static final long ROWS_RECIPIENTS_BID = 0x14;
    private static final long TC_ATTACHMENTS_BID = 0x18;
    private static final long NESTED_SL_ATTACHMENTS_BID = 0x1E;
    private static final long ROWS_ATTACHMENTS_BID = 0x20;
    private static final long ATTACHMENT_PC_BID = 0x24;

    private static final long NBT_PAGE_OFFSET = 1024;
    private static final long BBT_PAGE_OFFSET = 1536;
    private static final long FIRST_BLOCK_OFFSET = 2048;
    private static final long BLOCK_SLOT = 2048;

    private SyntheticUnicodeStore() {}

    /** Writes the store to {@code path} with the given block encoding and recipient count. */
    static void write(Path path, PstFile.EncryptionType encryptionType, int recipientCount) throws Exception {
        var recipientsHeap = buildRecipientTableHeap(recipientCount);
        var recipientRows = buildRecipientRows(recipientCount);
        var attachmentsHeap = buildAttachmentTableHeap();
        var attachmentRows = buildAttachmentRows();
        var messageHeap = buildMessagePropertyHeap();
        var attachmentHeap = buildAttachmentPropertyHeap();

        // Leaf data blocks are encrypted with the store's scheme; SL blocks are not ([MS-PST] §2.7.1).
        record Block(long bid, byte[] data, boolean encoded) {}
        var blocks = new Block[] {
            new Block(PC_BID, messageHeap, true),
            new Block(
                    MSG_SL_BID,
                    buildSlBlock(new long[][] {
                        {NID_ATTACHMENT_TABLE, TC_ATTACHMENTS_BID, NESTED_SL_ATTACHMENTS_BID},
                        {NID_RECIPIENT_TABLE, TC_RECIPIENTS_BID, NESTED_SL_RECIPIENTS_BID},
                        {ATTACHMENT_NID, ATTACHMENT_PC_BID, 0},
                    }),
                    false),
            new Block(TC_RECIPIENTS_BID, recipientsHeap, true),
            new Block(
                    NESTED_SL_RECIPIENTS_BID,
                    buildSlBlock(new long[][] {{ROW_MATRIX_NID, ROWS_RECIPIENTS_BID, 0}}),
                    false),
            new Block(ROWS_RECIPIENTS_BID, recipientRows, true),
            new Block(TC_ATTACHMENTS_BID, attachmentsHeap, true),
            new Block(
                    NESTED_SL_ATTACHMENTS_BID,
                    buildSlBlock(new long[][] {{ROW_MATRIX_NID, ROWS_ATTACHMENTS_BID, 0}}),
                    false),
            new Block(ROWS_ATTACHMENTS_BID, attachmentRows, true),
            new Block(ATTACHMENT_PC_BID, attachmentHeap, true),
        };

        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(FIRST_BLOCK_OFFSET + blocks.length * BLOCK_SLOT);

            // --- header: magic, Unicode version, crypt method, NBT/BBT root BREFs ---
            var header = ByteBuffer.allocate(576).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0, 0x4E444221); // "!BDN"
            header.putShort(10, (short) 23); // wVer: Unicode
            header.putLong(224, NBT_PAGE_OFFSET); // BREFNBT.ib
            header.putLong(240, BBT_PAGE_OFFSET); // BREFBBT.ib
            header.put(513, (byte)
                    switch (encryptionType) {
                        case NONE -> 0x00;
                        case COMPRESSIBLE -> 0x01;
                        case HIGH -> 0x02;
                    });
            file.seek(0);
            file.write(header.array());

            // --- NBT leaf page: the one message node ---
            var nbt = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
            nbt.putLong(0, MESSAGE_NID);
            nbt.putLong(8, PC_BID);
            nbt.putLong(16, MSG_SL_BID);
            nbt.putInt(24, 0); // parent nid
            nbt.put(488, (byte) 1); // cEnt
            nbt.put(490, (byte) 32); // cbEnt
            nbt.put(491, (byte) 0); // cLevel = leaf
            file.seek(NBT_PAGE_OFFSET);
            file.write(nbt.array());

            // --- BBT leaf page + the blocks themselves ---
            var bbt = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
            for (var index = 0; index < blocks.length; index++) {
                var block = blocks[index];
                var offset = FIRST_BLOCK_OFFSET + index * BLOCK_SLOT;
                bbt.putLong(index * 24, block.bid());
                bbt.putLong(index * 24 + 8, offset);
                bbt.putShort(index * 24 + 16, (short) block.data().length);
                bbt.putShort(index * 24 + 18, (short) 1); // cRef

                var data = block.encoded() ? encode(block.data(), block.bid(), encryptionType) : block.data();
                file.seek(offset);
                file.write(data);
            }
            bbt.put(488, (byte) blocks.length);
            bbt.put(490, (byte) 24);
            bbt.put(491, (byte) 0);
            file.seek(BBT_PAGE_OFFSET);
            file.write(bbt.array());
        }
    }

    /** Encodes a leaf data block the way {@code NodeDatabase.readLeafBlockData} will decode it. */
    private static byte[] encode(byte[] plain, long bid, PstFile.EncryptionType encryptionType) {
        return switch (encryptionType) {
            case NONE -> plain;
            case COMPRESSIBLE -> {
                var inverse = invert(CompressibleEncryption.COMP_ENC);
                var encoded = plain.clone();
                for (var index = 0; index < encoded.length; index++) {
                    encoded[index] = (byte) inverse[encoded[index] & 0xFF];
                }
                yield encoded;
            }
            case HIGH -> highEncode(plain, bid);
        };
    }

    /** The exact inverse chain of {@link HighEncryption#decode}: same salt schedule, reversed steps. */
    private static byte[] highEncode(byte[] plain, long bid) {
        var inverseHigh1 = invert(HighEncryption.HIGH_1);
        var inverseHigh2 = invert(HighEncryption.HIGH_2);
        var inverseCompEnc = invert(CompressibleEncryption.COMP_ENC);

        var encoded = plain.clone();
        var key = (int) bid;
        var salt = ((key & 0xffff0000) >>> 16) ^ (key & 0x0000ffff);
        for (var index = 0; index < encoded.length; index++) {
            var lowerSalt = salt & 0x00ff;
            var upperSalt = (salt & 0xff00) >>> 8;
            var value = encoded[index] & 0xFF;

            value += lowerSalt;
            value = inverseCompEnc[value & 0xFF];
            value += upperSalt;
            value = inverseHigh2[value & 0xFF];
            value -= upperSalt;
            value = inverseHigh1[value & 0xFF];
            value -= lowerSalt;

            encoded[index] = (byte) value;
            salt++;
        }
        return encoded;
    }

    private static int[] invert(byte[] table) {
        var inverse = new int[256];
        for (var index = 0; index < 256; index++) {
            inverse[table[index] & 0xFF] = index;
        }
        return inverse;
    }

    private static int[] invert(int[] table) {
        var inverse = new int[256];
        for (var index = 0; index < 256; index++) {
            inverse[table[index] & 0xFF] = index;
        }
        return inverse;
    }

    /** A leaf SLBLOCK with the given {@code {nid, bidData, bidSub}} entries (Unicode layout). */
    private static byte[] buildSlBlock(long[][] entries) {
        var block = ByteBuffer.allocate(8 + entries.length * 24).order(ByteOrder.LITTLE_ENDIAN);
        block.put(0, (byte) 0x02); // bType
        block.put(1, (byte) 0); // cLevel = leaf
        block.putShort(2, (short) entries.length);
        for (var index = 0; index < entries.length; index++) {
            block.putLong(8 + index * 24, entries[index][0]);
            block.putLong(8 + index * 24 + 8, entries[index][1]);
            block.putLong(8 + index * 24 + 16, entries[index][2]);
        }
        return block.array();
    }

    /** An HN block whose 1-based items are the given byte arrays; item {@code i} gets HID {@code i << 5}. */
    private static byte[] buildHeap(byte[]... items) {
        var itemsLength = 0;
        for (var item : items) {
            itemsLength += item.length;
        }
        var firstItemOffset = 16;
        var pageMapOffset = firstItemOffset + itemsLength;
        var heap =
                ByteBuffer.allocate(pageMapOffset + 4 + (items.length + 1) * 2).order(ByteOrder.LITTLE_ENDIAN);

        heap.putShort(0, (short) pageMapOffset); // ibHnpm
        heap.put(2, (byte) 0xEC); // bSig
        heap.putInt(4, 0x20); // hidUserRoot -> item 1

        var offset = firstItemOffset;
        heap.putShort(pageMapOffset, (short) items.length); // cAlloc
        heap.putShort(pageMapOffset + 2, (short) 0); // cFree
        for (var index = 0; index < items.length; index++) {
            heap.putShort(pageMapOffset + 4 + index * 2, (short) offset);
            heap.position(offset);
            heap.put(items[index]);
            offset += items[index].length;
        }
        heap.putShort(pageMapOffset + 4 + items.length * 2, (short) offset);
        return heap.array();
    }

    /** A PC BTH header (cbKey=2, cbEnt=6, leaf) whose records live in item 2. */
    private static byte[] propertyContextBthHeader() {
        var header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.put(0, (byte) 0xB5);
        header.put(1, (byte) 2);
        header.put(2, (byte) 6);
        header.put(3, (byte) 0);
        header.putInt(4, 0x40); // hidRoot -> item 2
        return header.array();
    }

    private static byte[] propertyRecord(int tag, int type, int value) {
        var record = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        record.putShort(0, (short) tag);
        record.putShort(2, (short) type);
        record.putInt(4, value);
        return record.array();
    }

    private static byte[] concat(byte[]... parts) {
        var length = 0;
        for (var part : parts) {
            length += part.length;
        }
        var joined = new byte[length];
        var offset = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }

    /** The message PC: PR_MESSAGE_CLASS_W, PR_SUBJECT_W (heap strings) and PR_HASATTACH = true. */
    private static byte[] buildMessagePropertyHeap() {
        var records = concat(
                propertyRecord(MapiProperties.PR_MESSAGE_CLASS_W, 0x001F, 0x60),
                propertyRecord(MapiProperties.PR_SUBJECT_W, 0x001F, 0x80),
                propertyRecord(MapiProperties.PR_HASATTACH, 0x000B, 1));
        return buildHeap(
                propertyContextBthHeader(),
                records,
                MESSAGE_CLASS.getBytes(StandardCharsets.UTF_16LE),
                SUBJECT.getBytes(StandardCharsets.UTF_16LE));
    }

    /** The attachment PC: PT_BINARY content, PT_LONG attach method 1 (afByValue), PT_UNICODE filename. */
    private static byte[] buildAttachmentPropertyHeap() {
        var records = concat(
                propertyRecord(MapiProperties.PR_ATTACH_DATA_BIN, 0x0102, 0x60),
                propertyRecord(MapiProperties.PR_ATTACH_METHOD, 0x0003, 1),
                propertyRecord(MapiProperties.PR_ATTACH_LONG_FILENAME_W, 0x001F, 0x80));
        return buildHeap(
                propertyContextBthHeader(),
                records,
                ATTACHMENT_CONTENT,
                ATTACHMENT_FILENAME.getBytes(StandardCharsets.UTF_16LE));
    }

    /**
     * The recipient table's TC heap: PidTagLtpRowId + PR_RECIPIENT_TYPE + PR_DISPLAY_NAME_W columns,
     * row width 16 (CEB at 12), the row matrix in subnode {@link #ROW_MATRIX_NID}, and the first
     * recipient's display name as in-heap item 4.
     */
    private static byte[] buildRecipientTableHeap(int recipientCount) {
        var tcInfo = ByteBuffer.allocate(22 + 3 * 8).order(ByteOrder.LITTLE_ENDIAN);
        tcInfo.put(0, (byte) 0x7C);
        tcInfo.put(1, (byte) 3); // cCols
        tcInfo.putShort(6, (short) 12); // rgib[TCI_1b]: CEB offset
        tcInfo.putShort(8, (short) 16); // rgib[TCI_bm]: row width
        tcInfo.putInt(10, 0x40); // hidRowIndex -> item 2
        tcInfo.putInt(14, ROW_MATRIX_NID); // hnidRows -> subnode
        putColumn(tcInfo, 22, 0x0003, MapiProperties.PidTagLtpRowId, 0, 4, 0);
        putColumn(tcInfo, 30, 0x0003, MapiProperties.PR_RECIPIENT_TYPE, 4, 4, 1);
        putColumn(tcInfo, 38, 0x001F, MapiProperties.PR_DISPLAY_NAME_W, 8, 4, 2);

        return buildHeap(
                tcInfo.array(),
                rowIndexBthHeader(),
                buildRowIndexLeaf(recipientCount),
                FIRST_RECIPIENT_NAME.getBytes(StandardCharsets.UTF_16LE));
    }

    /** The attachment table's TC heap: a single PidTagLtpRowId column, row matrix in the nested subnode. */
    private static byte[] buildAttachmentTableHeap() {
        var tcInfo = ByteBuffer.allocate(22 + 8).order(ByteOrder.LITTLE_ENDIAN);
        tcInfo.put(0, (byte) 0x7C);
        tcInfo.put(1, (byte) 1);
        tcInfo.putShort(6, (short) 4); // CEB offset
        tcInfo.putShort(8, (short) 8); // row width
        tcInfo.putInt(10, 0x40); // hidRowIndex -> item 2
        tcInfo.putInt(14, ROW_MATRIX_NID);
        putColumn(tcInfo, 22, 0x0003, MapiProperties.PidTagLtpRowId, 0, 4, 0);

        var leaf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        leaf.putInt(0, ATTACHMENT_NID); // dwRowID
        leaf.putInt(4, 0); // dwRowIndex
        return buildHeap(tcInfo.array(), rowIndexBthHeader(), leaf.array());
    }

    private static void putColumn(ByteBuffer tcInfo, int writeAt, int type, int tag, int offset, int size, int bit) {
        tcInfo.putShort(writeAt, (short) type);
        tcInfo.putShort(writeAt + 2, (short) tag);
        tcInfo.putShort(writeAt + 4, (short) offset);
        tcInfo.put(writeAt + 6, (byte) size);
        tcInfo.put(writeAt + 7, (byte) bit);
    }

    /** A row-index BTH header (4-byte rowId keys, 4-byte rowIndex entries) rooted at item 3. */
    private static byte[] rowIndexBthHeader() {
        var header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.put(0, (byte) 0xB5);
        header.put(1, (byte) 4);
        header.put(2, (byte) 4);
        header.put(3, (byte) 0);
        header.putInt(4, 0x60); // hidRoot -> item 3
        return header.array();
    }

    private static byte[] buildRowIndexLeaf(int rowCount) {
        var leaf = ByteBuffer.allocate(rowCount * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < rowCount; index++) {
            leaf.putInt(index * 8, 1000 + index); // dwRowID
            leaf.putInt(index * 8 + 4, index); // dwRowIndex
        }
        return leaf.array();
    }

    /**
     * The recipient row matrix: rowId, recipient type cycling To/Cc/Bcc, and (for the first row
     * only — its CEB bit 2 is set) the display-name HNID pointing at heap item 4.
     */
    private static byte[] buildRecipientRows(int recipientCount) {
        var rows = ByteBuffer.allocate(recipientCount * 16).order(ByteOrder.LITTLE_ENDIAN);
        for (var index = 0; index < recipientCount; index++) {
            rows.putInt(index * 16, 1000 + index);
            rows.putInt(index * 16 + 4, 1 + (index % 3)); // PR_RECIPIENT_TYPE: To/Cc/Bcc
            if (index == 0) {
                rows.putInt(index * 16 + 8, 0x80); // PR_DISPLAY_NAME_W -> heap item 4
                rows.put(index * 16 + 12, (byte) 0xE0); // CEB: bits 0..2
            } else {
                rows.put(index * 16 + 12, (byte) 0xC0); // CEB: bits 0..1
            }
        }
        return rows.array();
    }

    private static byte[] buildAttachmentRows() {
        var rows = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        rows.putInt(0, ATTACHMENT_NID);
        rows.put(4, (byte) 0x80); // CEB: bit 0
        return rows.array();
    }
}
