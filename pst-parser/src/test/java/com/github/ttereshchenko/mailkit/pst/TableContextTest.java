package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression test for multi-block row-matrix addressing (audit F1). Rows are not permitted to span
 * blocks ([MS-PST] §2.3.4.4): a row matrix larger than one 8176-byte block payload packs
 * {@code floor(8176 / rowWidth)} rows per payload with dead bytes at each payload tail, so row
 * {@code i} must be addressed by payload chunk — the old contiguous {@code i * rowWidth} addressing
 * read garbage for every row past the first payload (folders with more than ~80 messages).
 */
class TableContextTest {

    private static final int ROW_WIDTH = 100;
    private static final int BLOCK_PAYLOAD = 8176; // Unicode block payload; 81 rows of 100 bytes fit
    private static final int ROWS_PER_BLOCK = BLOCK_PAYLOAD / ROW_WIDTH;
    private static final int ROW_COUNT = 90; // spills 9 rows into a second block

    private static final long SL_BID = 0x06; // internal flag set
    private static final long XBLOCK_BID = 0x0A; // internal flag set
    private static final long DATA1_BID = 0x0C;
    private static final long DATA2_BID = 0x10;
    private static final int ROW_MATRIX_NID = 0x22; // low 5 bits non-zero -> subnode HNID

    @Test
    void multiBlockRowMatrixRowsResolveByBlockChunk() throws Exception {
        Path tempFile = Files.createTempFile("test_row_matrix", ".pst");
        try {
            writeSyntheticStore(tempFile);

            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                var nodeDatabase = new NodeDatabase(
                        channel, PstFile.Format.UNICODE, PstFile.EncryptionType.NONE, 0, 512, 64L * 1024 * 1024);
                var node = new NodeEntry(0x100E, 0, SL_BID, 0);

                var tableContext = new TableContext(buildTcHeap(), nodeDatabase, node);
                var rows = tableContext.getRows();

                assertEquals(ROW_COUNT, rows.size(), "Every row of the two-block matrix must be loaded");
                for (int i = 0; i < ROW_COUNT; i++) {
                    Map<Integer, Object> row = rows.get(i);
                    assertEquals(
                            1000 + i,
                            row.get(MapiProperties.PidTagLtpRowId),
                            "Row " + i + " must carry its own row id — rows past " + ROWS_PER_BLOCK
                                    + " live in the second block payload");
                }
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void nodeDataStreamMatchesMaterializedRead() throws Exception {
        Path tempFile = Files.createTempFile("test_stream", ".pst");
        try {
            writeSyntheticStore(tempFile);
            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                var nodeDatabase = new NodeDatabase(
                        channel, PstFile.Format.UNICODE, PstFile.EncryptionType.NONE, 0, 512, 64L * 1024 * 1024);
                byte[] materialized = nodeDatabase.readNodeData(XBLOCK_BID);
                byte[] streamed;
                try (var stream = nodeDatabase.openNodeDataStream(XBLOCK_BID)) {
                    streamed = stream.readAllBytes();
                }
                assertArrayEquals(
                        materialized, streamed, "Streaming a node must yield the same bytes as materializing it");
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * A subnode-resident binary property must be deferred at parse time, materialize on first
     * access, and stream the same bytes without materialization (audit F2): exercised through
     * {@link Attachment}, whose PR_ATTACH_DATA_BIN is the production case of this path.
     */
    @Test
    void subnodeBinaryPropertyMaterializesLazilyAndStreams() throws Exception {
        Path tempFile = Files.createTempFile("test_lazy_binary", ".pst");
        try {
            writeSyntheticStore(tempFile);
            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                var nodeDatabase = new NodeDatabase(
                        channel, PstFile.Format.UNICODE, PstFile.EncryptionType.NONE, 0, 512, 64L * 1024 * 1024);
                var node = new NodeEntry(0x100E, 0, SL_BID, 0);
                var attachment = new Attachment(new PropertyContext(buildBinaryPropertyHeap(), nodeDatabase, node));

                byte[] expected = nodeDatabase.readNodeData(XBLOCK_BID);
                try (var stream = attachment.openDataStream()) {
                    assertArrayEquals(expected, stream.readAllBytes(), "Streaming must not require materializing");
                }
                assertArrayEquals(expected, attachment.getData(), "Materializing on access must yield the data");
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /** A PC heap whose single record is PR_ATTACH_DATA_BIN (PT_BINARY) pointing at the subnode. */
    private static byte[] buildBinaryPropertyHeap() {
        var heap = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        heap.putShort(0, (short) 34); // ibHnpm
        heap.put(2, (byte) 0xEC); // bSig
        heap.putInt(4, 0x20); // hidUserRoot -> item 1 (BTH header)

        // Item 1 [16, 24): BTH header — cbKey=2, cbEnt=6, leaf, root at item 2.
        heap.put(16, (byte) 0xB5);
        heap.put(17, (byte) 2);
        heap.put(18, (byte) 6);
        heap.put(19, (byte) 0);
        heap.putInt(20, 0x40); // hidRoot -> item 2

        // Item 2 [24, 32): tag PR_ATTACH_DATA_BIN, type PT_BINARY, HNID = the subnode NID.
        heap.putShort(24, (short) MapiProperties.PR_ATTACH_DATA_BIN);
        heap.putShort(26, (short) 0x0102);
        heap.putInt(28, ROW_MATRIX_NID);

        // Page map at 34: cAlloc=2, rgibAlloc = {16, 24, 32}.
        heap.putShort(34, (short) 2);
        heap.putShort(36, (short) 0);
        heap.putShort(38, (short) 16);
        heap.putShort(40, (short) 24);
        heap.putShort(42, (short) 32);
        return heap.array();
    }

    /**
     * Writes a minimal Unicode store: a BBT leaf page at 0 describing the SLBLOCK, the XBLOCK and
     * two data blocks of the row matrix; an empty NBT leaf page at 512; and the blocks themselves.
     */
    private static void writeSyntheticStore(Path tempFile) throws Exception {
        int data2Size = (ROW_COUNT - ROWS_PER_BLOCK) * ROW_WIDTH;
        long data1Offset = 4096;
        long data2Offset = 4096 + 8192;
        long slOffset = 1024;
        long xbOffset = 2048;

        try (RandomAccessFile file = new RandomAccessFile(tempFile.toFile(), "rw")) {
            file.setLength(data2Offset + data2Size);

            // --- BBT leaf page at offset 0 (512 bytes, Unicode trailer at 488) ---
            var bbt = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
            writeBbtEntry(bbt, 0, SL_BID, slOffset, 8 + 24);
            writeBbtEntry(bbt, 24, XBLOCK_BID, xbOffset, 8 + 2 * 8);
            writeBbtEntry(bbt, 48, DATA1_BID, data1Offset, BLOCK_PAYLOAD);
            writeBbtEntry(bbt, 72, DATA2_BID, data2Offset, data2Size);
            bbt.put(488, (byte) 4); // cEnt
            bbt.put(490, (byte) 24); // cbEnt
            bbt.put(491, (byte) 0); // cLevel = leaf
            file.seek(0);
            file.write(bbt.array());

            // --- NBT root: an empty leaf page at offset 512 (all zeroes parse as cEnt=0) ---

            // --- SLBLOCK: one entry mapping ROW_MATRIX_NID to the XBLOCK ---
            var slBlock = ByteBuffer.allocate(8 + 24).order(ByteOrder.LITTLE_ENDIAN);
            slBlock.put(0, (byte) 0x02); // bType
            slBlock.put(1, (byte) 0); // cLevel = leaf
            slBlock.putShort(2, (short) 1); // cEnt
            slBlock.putLong(8, ROW_MATRIX_NID);
            slBlock.putLong(16, XBLOCK_BID);
            slBlock.putLong(24, 0); // no nested subnode
            file.seek(slOffset);
            file.write(slBlock.array());

            // --- XBLOCK: two child data blocks ---
            var xblock = ByteBuffer.allocate(8 + 2 * 8).order(ByteOrder.LITTLE_ENDIAN);
            xblock.put(0, (byte) 0x01); // bType
            xblock.put(1, (byte) 1); // cLevel
            xblock.putShort(2, (short) 2); // cEnt
            xblock.putInt(4, BLOCK_PAYLOAD + data2Size); // lcbTotal
            xblock.putLong(8, DATA1_BID);
            xblock.putLong(16, DATA2_BID);
            file.seek(xbOffset);
            file.write(xblock.array());

            // --- Row matrix blocks: ROWS_PER_BLOCK whole rows + dead tail in block 1, rest in 2 ---
            var data1 = ByteBuffer.allocate(BLOCK_PAYLOAD).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < ROWS_PER_BLOCK; i++) {
                writeRow(data1, i * ROW_WIDTH, 1000 + i);
            }
            file.seek(data1Offset);
            file.write(data1.array());

            var data2 = ByteBuffer.allocate(data2Size).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = ROWS_PER_BLOCK; i < ROW_COUNT; i++) {
                writeRow(data2, (i - ROWS_PER_BLOCK) * ROW_WIDTH, 1000 + i);
            }
            file.seek(data2Offset);
            file.write(data2.array());
        }
    }

    private static void writeBbtEntry(ByteBuffer page, int offset, long bid, long fileOffset, int size) {
        page.putLong(offset, bid);
        page.putLong(offset + 8, fileOffset);
        page.putShort(offset + 16, (short) size);
        page.putShort(offset + 18, (short) 1); // cRef
    }

    /** A row: the PidTagLtpRowId int column at offset 0 and the CEB (bit 0 set) at offset 4. */
    private static void writeRow(ByteBuffer block, int offset, int rowId) {
        block.putInt(offset, rowId);
        block.put(offset + 4, (byte) 0x80);
    }

    /**
     * The TC node data: a single-block Heap-on-Node holding the TCINFO (one PidTagLtpRowId column,
     * row width {@value #ROW_WIDTH}, row matrix in subnode {@value #ROW_MATRIX_NID}), the row-index
     * BTH header and its leaf with {@value #ROW_COUNT} (rowId, rowIndex) entries.
     */
    private static byte[] buildTcHeap() {
        int tcInfoStart = 16;
        int tcInfoLength = 22 + 8;
        int bthHeaderStart = tcInfoStart + tcInfoLength; // 46
        int bthLeafStart = bthHeaderStart + 8; // 54
        int bthLeafLength = ROW_COUNT * 8;
        int pageMapStart = bthLeafStart + bthLeafLength; // 774
        var heap = ByteBuffer.allocate(pageMapStart + 4 + 4 * 2).order(ByteOrder.LITTLE_ENDIAN);

        heap.putShort(0, (short) pageMapStart); // ibHnpm
        heap.put(2, (byte) 0xEC); // bSig
        heap.putInt(4, 0x20); // hidUserRoot -> item 1 (TCINFO)

        // TCINFO
        heap.put(tcInfoStart, (byte) 0x7C); // bType TC
        heap.put(tcInfoStart + 1, (byte) 1); // cCols
        heap.putShort(tcInfoStart + 6, (short) 4); // rgib[TCI_1b]: CEB offset within a row
        heap.putShort(tcInfoStart + 8, (short) ROW_WIDTH); // rgib[TCI_bm]: row width
        heap.putInt(tcInfoStart + 10, 0x40); // hidRowIndex -> item 2
        heap.putInt(tcInfoStart + 14, ROW_MATRIX_NID); // hnidRows -> subnode
        // TCOLDESC: PidTagLtpRowId, PT_LONG, offset 0, size 4, iBit 0
        heap.putShort(tcInfoStart + 22, (short) 0x0003);
        heap.putShort(tcInfoStart + 24, (short) MapiProperties.PidTagLtpRowId);
        heap.putShort(tcInfoStart + 26, (short) 0);
        heap.put(tcInfoStart + 28, (byte) 4);
        heap.put(tcInfoStart + 29, (byte) 0);

        // Row-index BTH header: 4-byte keys (rowId), 4-byte entries (rowIndex), leaf root
        heap.put(bthHeaderStart, (byte) 0xB5);
        heap.put(bthHeaderStart + 1, (byte) 4); // cbKey
        heap.put(bthHeaderStart + 2, (byte) 4); // cbEnt
        heap.put(bthHeaderStart + 3, (byte) 0); // bIdxLevels
        heap.putInt(bthHeaderStart + 4, 0x60); // hidRoot -> item 3

        for (int i = 0; i < ROW_COUNT; i++) {
            heap.putInt(bthLeafStart + i * 8, 1000 + i); // dwRowID
            heap.putInt(bthLeafStart + i * 8 + 4, i); // dwRowIndex
        }

        // HN page map: 3 allocations -> rgibAlloc has 4 offsets
        heap.putShort(pageMapStart, (short) 3); // cAlloc
        heap.putShort(pageMapStart + 2, (short) 0); // cFree
        heap.putShort(pageMapStart + 4, (short) tcInfoStart);
        heap.putShort(pageMapStart + 6, (short) bthHeaderStart);
        heap.putShort(pageMapStart + 8, (short) bthLeafStart);
        heap.putShort(pageMapStart + 10, (short) pageMapStart);
        return heap.array();
    }
}
