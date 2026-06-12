package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;

/**
 * F1 regression at the {@link TableContext} level: a message-internal table's subnode HNIDs (its
 * row matrix here) resolve in the <em>table subnode's own</em> nested tree ([MS-PST] §2.3.3.2),
 * with the parent (message) tree as a compatibility fallback — and an HNID resolvable in neither
 * tree yields zero rows rather than rows fabricated from an unrelated heap item.
 */
class TableContextSubnodeTreeTest {

    private enum RowMatrixHome {
        NESTED_TREE,
        PARENT_TREE,
        MISSING
    }

    private static final int NID_RECIPIENT_TABLE = 0x0692;
    private static final int ROW_MATRIX_NID = 0x22;
    private static final int ROW_COUNT = 5;
    private static final int ROW_WIDTH = 16;

    private static final long MSG_SL_BID = 0x06;
    private static final long NESTED_SL_BID = 0x0A;
    private static final long TC_HEAP_BID = 0x0C;
    private static final long ROWS_BID = 0x10;

    @Test
    void rowMatrixResolvesInTheTablesOwnNestedTree() throws Exception {
        assertRowsRecovered(RowMatrixHome.NESTED_TREE, false);
    }

    @Test
    void rowMatrixInTheParentTreeResolvesViaTheFallback() throws Exception {
        assertRowsRecovered(RowMatrixHome.PARENT_TREE, false);
    }

    @Test
    void ansiRowMatrixResolvesInTheTablesOwnNestedTree() throws Exception {
        assertRowsRecovered(RowMatrixHome.NESTED_TREE, true);
    }

    @Test
    void unresolvableRowMatrixYieldsNoRowsNotFabricatedOnes() throws Exception {
        var store = Files.createTempFile("tc_subnode_missing", ".pst");
        try {
            writeStore(store, RowMatrixHome.MISSING, false);
            try (var channel = FileChannel.open(store, StandardOpenOption.READ)) {
                var nodeDatabase = new NodeDatabase(
                        channel, PstFile.Format.UNICODE, PstFile.EncryptionType.NONE, 0, 512, 64L * 1024 * 1024);
                var messageNode = new NodeEntry(0x200004, 0, MSG_SL_BID, 0);
                var tableEntry = nodeDatabase.readSubnodeEntry(messageNode.subBid(), NID_RECIPIENT_TABLE);
                var tableData = nodeDatabase.readNodeData(tableEntry.dataBid());

                var tableContext = new TableContext(tableData, nodeDatabase, tableEntry, messageNode, null);
                assertTrue(
                        tableContext.getRows().isEmpty(),
                        "An unresolvable row-matrix NID must yield no rows — the old heap-item fallback"
                                + " fabricated rows from unrelated heap bytes");
            }
        } finally {
            Files.deleteIfExists(store);
        }
    }

    private static void assertRowsRecovered(RowMatrixHome home, boolean ansi) throws Exception {
        var store = Files.createTempFile("tc_subnode_" + home + "_" + (ansi ? "ansi" : "unicode"), ".pst");
        try {
            writeStore(store, home, ansi);
            try (var channel = FileChannel.open(store, StandardOpenOption.READ)) {
                var nodeDatabase = new NodeDatabase(
                        channel,
                        ansi ? PstFile.Format.ANSI : PstFile.Format.UNICODE,
                        PstFile.EncryptionType.NONE,
                        0,
                        512,
                        64L * 1024 * 1024);
                var messageNode = new NodeEntry(0x200004, 0, MSG_SL_BID, 0);
                // Mirror Message.getRecipients/getAttachments: host the TC on the table's own entry,
                // fall back to the message tree.
                var tableEntry = nodeDatabase.readSubnodeEntry(messageNode.subBid(), NID_RECIPIENT_TABLE);
                assertNotNull(tableEntry, "The table subnode entry must resolve");
                var tableData = nodeDatabase.readNodeData(tableEntry.dataBid());

                var tableContext = new TableContext(tableData, nodeDatabase, tableEntry, messageNode, null);
                var rows = tableContext.getRows();
                assertEquals(ROW_COUNT, rows.size(), "Every row must be recovered for layout " + home);
                for (var index = 0; index < ROW_COUNT; index++) {
                    assertEquals(1000 + index, rows.get(index).get(MapiProperties.PidTagLtpRowId));
                }
            }
        } finally {
            Files.deleteIfExists(store);
        }
    }

    /**
     * Writes a BBT leaf page at 0, an empty NBT page at 512, and the message subnode tree: the
     * recipient-table subnode entry carries the TC heap; the row matrix lives in the table's nested
     * tree, in the parent (message) tree, or nowhere, depending on {@code home}.
     */
    private static void writeStore(Path store, RowMatrixHome home, boolean ansi) throws Exception {
        long msgSlOffset = 1024;
        long nestedSlOffset = 2048;
        long tcHeapOffset = 3072;
        long rowsOffset = 4096;
        var tcHeap = buildTcHeap(ansi);
        var rowsSize = ROW_COUNT * ROW_WIDTH;

        try (var file = new RandomAccessFile(store.toFile(), "rw")) {
            file.setLength(rowsOffset + rowsSize);

            var bbt = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
            var slBlockSize = ansi ? 4 + 2 * 12 : 8 + 2 * 24;
            writeBbtEntry(bbt, 0, MSG_SL_BID, msgSlOffset, slBlockSize, ansi);
            writeBbtEntry(bbt, 1, NESTED_SL_BID, nestedSlOffset, ansi ? 4 + 12 : 8 + 24, ansi);
            writeBbtEntry(bbt, 2, TC_HEAP_BID, tcHeapOffset, tcHeap.length, ansi);
            writeBbtEntry(bbt, 3, ROWS_BID, rowsOffset, rowsSize, ansi);
            var trailerOffset = ansi ? 496 : 488;
            bbt.put(trailerOffset, (byte) 4); // cEnt
            bbt.put(trailerOffset + 2, (byte) (ansi ? 12 : 24)); // cbEnt
            bbt.put(trailerOffset + 3, (byte) 0); // cLevel = leaf
            file.seek(0);
            file.write(bbt.array());

            // NBT root: empty leaf page at 512 (zeroes parse as cEnt=0)

            // Message subnode tree: the recipient-table entry, plus the row matrix when flattened
            // into the parent tree.
            var tableSubBid = home == RowMatrixHome.NESTED_TREE ? NESTED_SL_BID : 0;
            var messageEntries = home == RowMatrixHome.PARENT_TREE
                    ? new long[][] {
                        {NID_RECIPIENT_TABLE, TC_HEAP_BID, tableSubBid},
                        {ROW_MATRIX_NID, ROWS_BID, 0}
                    }
                    : new long[][] {{NID_RECIPIENT_TABLE, TC_HEAP_BID, tableSubBid}};
            file.seek(msgSlOffset);
            file.write(buildSlBlock(messageEntries, ansi));

            if (home == RowMatrixHome.NESTED_TREE) {
                file.seek(nestedSlOffset);
                file.write(buildSlBlock(new long[][] {{ROW_MATRIX_NID, ROWS_BID, 0}}, ansi));
            }

            file.seek(tcHeapOffset);
            file.write(tcHeap);

            var rows = ByteBuffer.allocate(rowsSize).order(ByteOrder.LITTLE_ENDIAN);
            for (var index = 0; index < ROW_COUNT; index++) {
                rows.putInt(index * ROW_WIDTH, 1000 + index);
                rows.put(index * ROW_WIDTH + 4, (byte) 0x80); // CEB bit 0
            }
            file.seek(rowsOffset);
            file.write(rows.array());
        }
    }

    private static void writeBbtEntry(ByteBuffer page, int index, long bid, long fileOffset, int size, boolean ansi) {
        if (ansi) {
            page.putInt(index * 12, (int) bid);
            page.putInt(index * 12 + 4, (int) fileOffset);
            page.putShort(index * 12 + 8, (short) size);
            page.putShort(index * 12 + 10, (short) 1); // cRef
        } else {
            page.putLong(index * 24, bid);
            page.putLong(index * 24 + 8, fileOffset);
            page.putShort(index * 24 + 16, (short) size);
            page.putShort(index * 24 + 18, (short) 1); // cRef
        }
    }

    private static byte[] buildSlBlock(long[][] entries, boolean ansi) {
        var entriesStart = ansi ? 4 : 8;
        var entrySize = ansi ? 12 : 24;
        var block =
                ByteBuffer.allocate(entriesStart + entries.length * entrySize).order(ByteOrder.LITTLE_ENDIAN);
        block.put(0, (byte) 0x02); // bType
        block.put(1, (byte) 0); // cLevel = leaf
        block.putShort(2, (short) entries.length);
        for (var index = 0; index < entries.length; index++) {
            var entryAt = entriesStart + index * entrySize;
            if (ansi) {
                block.putInt(entryAt, (int) entries[index][0]);
                block.putInt(entryAt + 4, (int) entries[index][1]);
                block.putInt(entryAt + 8, (int) entries[index][2]);
            } else {
                block.putLong(entryAt, entries[index][0]);
                block.putLong(entryAt + 8, entries[index][1]);
                block.putLong(entryAt + 16, entries[index][2]);
            }
        }
        return block.array();
    }

    /**
     * A single-block TC heap: one PidTagLtpRowId column (row width {@value #ROW_WIDTH}, CEB at 4),
     * the row index as a BTH with 4-byte keys and — matching the on-disk formats — 4-byte (Unicode)
     * or 2-byte (ANSI) row-index entries, and the row matrix in subnode {@value #ROW_MATRIX_NID}.
     */
    private static byte[] buildTcHeap(boolean ansi) {
        var rowIndexEntrySize = ansi ? 2 : 4;
        var tcInfoStart = 16;
        var tcInfoLength = 22 + 8;
        var bthHeaderStart = tcInfoStart + tcInfoLength;
        var bthLeafStart = bthHeaderStart + 8;
        var bthLeafLength = ROW_COUNT * (4 + rowIndexEntrySize);
        var pageMapStart = bthLeafStart + bthLeafLength;
        var heap = ByteBuffer.allocate(pageMapStart + 4 + 4 * 2).order(ByteOrder.LITTLE_ENDIAN);

        heap.putShort(0, (short) pageMapStart); // ibHnpm
        heap.put(2, (byte) 0xEC); // bSig
        heap.putInt(4, 0x20); // hidUserRoot -> item 1 (TCINFO)

        heap.put(tcInfoStart, (byte) 0x7C);
        heap.put(tcInfoStart + 1, (byte) 1); // cCols
        heap.putShort(tcInfoStart + 6, (short) 4); // CEB offset within a row
        heap.putShort(tcInfoStart + 8, (short) ROW_WIDTH);
        heap.putInt(tcInfoStart + 10, 0x40); // hidRowIndex -> item 2
        heap.putInt(tcInfoStart + 14, ROW_MATRIX_NID); // hnidRows -> subnode NID
        heap.putShort(tcInfoStart + 22, (short) 0x0003); // PT_LONG
        heap.putShort(tcInfoStart + 24, (short) MapiProperties.PidTagLtpRowId);
        heap.putShort(tcInfoStart + 26, (short) 0);
        heap.put(tcInfoStart + 28, (byte) 4);
        heap.put(tcInfoStart + 29, (byte) 0);

        heap.put(bthHeaderStart, (byte) 0xB5);
        heap.put(bthHeaderStart + 1, (byte) 4); // cbKey
        heap.put(bthHeaderStart + 2, (byte) rowIndexEntrySize); // cbEnt
        heap.put(bthHeaderStart + 3, (byte) 0);
        heap.putInt(bthHeaderStart + 4, 0x60); // hidRoot -> item 3

        for (var index = 0; index < ROW_COUNT; index++) {
            var entryAt = bthLeafStart + index * (4 + rowIndexEntrySize);
            heap.putInt(entryAt, 1000 + index); // dwRowID
            if (ansi) {
                heap.putShort(entryAt + 4, (short) index); // dwRowIndex (2 bytes in ANSI stores)
            } else {
                heap.putInt(entryAt + 4, index);
            }
        }

        heap.putShort(pageMapStart, (short) 3);
        heap.putShort(pageMapStart + 2, (short) 0);
        heap.putShort(pageMapStart + 4, (short) tcInfoStart);
        heap.putShort(pageMapStart + 6, (short) bthHeaderStart);
        heap.putShort(pageMapStart + 8, (short) bthLeafStart);
        heap.putShort(pageMapStart + 10, (short) pageMapStart);
        return heap.array();
    }
}
