package com.github.ttereshchenko.mailkit.pst;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses Table Context (TC) structures stored within a Node's data block ([MS-PST] §2.3.4).
 * A TC provides rows of properties (columns), used for Folder Hierarchy and Message tables.
 */
class TableContext {

    private static final System.Logger LOG = System.getLogger(TableContext.class.getName());

    private static final int MAX_BTH_LEVELS = 8;

    private final HeapOnNode heap;
    private final List<ColumnDescriptor> columns = new ArrayList<>();
    private final List<Map<Integer, Object>> rows = new ArrayList<>();
    private int rowWidth;
    private int existenceBitmapOffset;
    private int hnidRows;
    private int hidRowIndex;
    private final NodeDatabase nodeDatabase;
    private final NodeEntry node;
    private final NodeEntry fallbackNode;
    private final Charset charset;

    TableContext(byte[] nodeData, NodeDatabase nodeDatabase, NodeEntry node) throws PstException {
        this(nodeData, nodeDatabase, node, null, null);
    }

    TableContext(byte[] nodeData, NodeDatabase nodeDatabase, NodeEntry node, Charset charset) throws PstException {
        this(nodeData, nodeDatabase, node, null, charset);
    }

    /**
     * @param node the node hosting this TC — for a message-internal table (recipients/attachments)
     *     this is the table's own subnode entry, whose nested subnode tree ([MS-PST] §2.2.2.8.3.3
     *     {@code SLENTRY.bidSub}) is where subnode HNIDs inside the TC resolve ([MS-PST] §2.3.3.2)
     * @param fallbackNode an optional second node whose subnode tree is consulted when {@code node}'s
     *     does not contain a referenced subnode (compatibility net for writers that flatten the
     *     table's subnodes into the parent message's tree); may be {@code null}
     */
    TableContext(byte[] nodeData, NodeDatabase nodeDatabase, NodeEntry node, NodeEntry fallbackNode, Charset charset)
            throws PstException {
        this.nodeDatabase = nodeDatabase;
        this.node = node;
        this.fallbackNode = fallbackNode;
        this.charset = charset;
        if (nodeData == null || nodeData.length < 16) {
            this.heap = null;
            return;
        }
        this.heap = nodeDatabase != null
                ? new HeapOnNode(nodeData, nodeDatabase.heapBlockSize())
                : new HeapOnNode(nodeData);
        parseTcInfo();
        parseRows();
    }

    private void parseTcInfo() {
        byte[] tcInfo = heap.getItem(heap.userRootHid());
        if (tcInfo.length < 4) {
            return;
        }

        var buffer = ByteBuffer.wrap(tcInfo).order(ByteOrder.LITTLE_ENDIAN);
        int headerType = Byte.toUnsignedInt(buffer.get());
        if (headerType != 0x7C) { // Must be TC
            return;
        }

        int columnCount = Byte.toUnsignedInt(buffer.get());

        // The fixed TCINFO header is 22 bytes, followed by a cCols-long TCOLDESC array of 8 bytes each
        // ([MS-PST] §2.3.4.1). A truncated buffer would throw IndexOutOfBounds on the reads below.
        if (tcInfo.length < 22 + columnCount * 8) {
            return;
        }

        this.existenceBitmapOffset = Short.toUnsignedInt(buffer.getShort(6)); // rgib[TCI_1b]: offset to CEB
        this.rowWidth = Short.toUnsignedInt(buffer.getShort(8)); // rgib[TCI_bm]: total row size
        this.hidRowIndex = buffer.getInt(10);
        this.hnidRows = buffer.getInt(14);

        buffer.position(22); // TCOLDESC array starts at 22
        for (int i = 0; i < columnCount; i++) {
            int valueType = Short.toUnsignedInt(buffer.getShort());
            int tagId = Short.toUnsignedInt(buffer.getShort());
            int offset = Short.toUnsignedInt(buffer.getShort());
            int size = Byte.toUnsignedInt(buffer.get());
            int existenceBit = Byte.toUnsignedInt(buffer.get());

            int propertyTag = (tagId << 16) | valueType;
            columns.add(new ColumnDescriptor(propertyTag, valueType, offset, size, existenceBit));
        }
    }

    private void parseRows() throws PstException {
        if (columns.isEmpty() || rowWidth <= 0) {
            return;
        }

        if (hidRowIndex == 0) {
            return;
        }
        byte[] bthHeader = heap.getItem(hidRowIndex);
        if (bthHeader.length < 8) {
            return;
        }

        var buffer = ByteBuffer.wrap(bthHeader).order(ByteOrder.LITTLE_ENDIAN);
        int headerType = Byte.toUnsignedInt(buffer.get(0));
        if (headerType != 0xB5) {
            return;
        }

        int keySize = Byte.toUnsignedInt(buffer.get(1));
        int entrySize = Byte.toUnsignedInt(buffer.get(2));
        int indexLevels = Byte.toUnsignedInt(buffer.get(3));
        int hidRoot = buffer.getInt(4);

        parseBTreeNode(hidRoot, keySize, entrySize, indexLevels, new HashSet<>());
    }

    private void parseBTreeNode(int hidRoot, int keySize, int entrySize, int level, Set<Integer> visited)
            throws PstException {
        if (!visited.add(hidRoot)) {
            throw new PstException("Cyclic B-Tree-on-Heap reference: " + hidRoot);
        }
        if (level > MAX_BTH_LEVELS) {
            throw new PstException("BTH recursion limit exceeded");
        }
        if (level == 0) {
            parseLeafNode(hidRoot, keySize, entrySize);
            return;
        }

        byte[] branchData = heap.getItem(hidRoot);
        var buffer = ByteBuffer.wrap(branchData).order(ByteOrder.LITTLE_ENDIAN);
        int branchEntrySize = keySize + 4;
        int entryCount = branchData.length / branchEntrySize;

        for (int i = 0; i < entryCount; i++) {
            buffer.position(i * branchEntrySize + keySize);
            int childHid = buffer.getInt();
            parseBTreeNode(childHid, keySize, entrySize, level - 1, visited);
        }
    }

    private void parseLeafNode(int hidRoot, int keySize, int entrySize) {
        byte[] leafData = heap.getItem(hidRoot);
        if (leafData.length == 0) {
            return;
        }
        // cbKey/cbEnt come from the (untrusted) BTH header; a zero sum would divide-by-zero here.
        if (keySize + entrySize == 0) {
            return;
        }
        var buffer = ByteBuffer.wrap(leafData).order(ByteOrder.LITTLE_ENDIAN);

        int entryCount = leafData.length / (keySize + entrySize);

        byte[] rowMatrix;
        if ((hnidRows & 0x1F) != 0) {
            // The HNID is a subnode NID; a heap lookup on it would dereference an unrelated heap
            // item and parse it as the row matrix (fabricated rows), so resolve it as a subnode or
            // surface the loss — never fall back to the heap.
            rowMatrix = readSubnodeBytes(node, hnidRows);
            if (rowMatrix == null) {
                rowMatrix = readSubnodeBytes(fallbackNode, hnidRows);
            }
            if (rowMatrix == null) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () -> "Table row-matrix subnode 0x" + Integer.toHexString(hnidRows) + " not found; "
                                + entryCount + " row(s) lost");
                return;
            }
        } else {
            rowMatrix = heap.getItem(hnidRows);
        }

        // Rows are not permitted to span blocks ([MS-PST] §2.3.4.4): a multi-block row matrix packs
        // floor(blockPayload / rowWidth) rows per block payload with dead bytes at each payload tail,
        // so row i lives in payload i / rowsPerBlock — not at the contiguous offset i * rowWidth.
        // (For an in-heap or single-block matrix the two addressing schemes coincide, because such a
        // matrix is always smaller than one payload.)
        int blockPayload = nodeDatabase != null ? nodeDatabase.heapBlockSize() : HeapOnNode.DEFAULT_BLOCK_PAYLOAD_SIZE;
        int rowsPerBlock = blockPayload / rowWidth;
        if (rowsPerBlock == 0) {
            return; // a row wider than a block payload violates the spec
        }
        if (entryCount > 0 && rowMatrix.length < rowWidth) {
            // Smaller than a single row: parsing it would yield garbage cells, so reject loudly.
            int matrixLength = rowMatrix.length;
            LOG.log(
                    System.Logger.Level.WARNING,
                    () -> "Table row matrix is " + matrixLength + " byte(s), smaller than one " + rowWidth
                            + "-byte row; " + entryCount + " row(s) lost");
            return;
        }

        for (int i = 0; i < entryCount; i++) {
            // TCROWID = dwRowID (cbKey bytes) + dwRowIndex (cbEnt bytes; 4 for Unicode, 2 for ANSI).
            buffer.position(i * (keySize + entrySize));
            int rowId = readUnsigned(buffer, keySize);
            int rowIndex = readUnsigned(buffer, entrySize);

            long startOffset =
                    (long) (rowIndex / rowsPerBlock) * blockPayload + (long) (rowIndex % rowsPerBlock) * rowWidth;
            if (startOffset >= 0 && startOffset + rowWidth <= rowMatrix.length) {
                var rowData = new byte[rowWidth];
                System.arraycopy(rowMatrix, (int) startOffset, rowData, 0, rowWidth);
                rows.add(parseRowData(rowId, rowData));
            }
        }
    }

    private Map<Integer, Object> parseRowData(int rowId, byte[] rowData) {
        Map<Integer, Object> rowProperties = new HashMap<>();
        var buffer = ByteBuffer.wrap(rowData).order(ByteOrder.LITTLE_ENDIAN);

        // CEB (Cell Existence Bitmap) is at rgib[TCI_1b]
        int bitmapLength = (int) Math.ceil(columns.size() / 8.0);
        var existenceBitmap = new byte[bitmapLength];
        if (existenceBitmapOffset + bitmapLength <= rowData.length) {
            System.arraycopy(rowData, existenceBitmapOffset, existenceBitmap, 0, bitmapLength);
        }

        for (var column : columns) {
            int byteIndex = column.existenceBit() / 8;
            int bitIndex = column.existenceBit() % 8;
            if (byteIndex < existenceBitmap.length && (existenceBitmap[byteIndex] & (1 << (7 - bitIndex))) == 0) {
                continue; // CEB bit is 0, column does not exist for this row
            }

            if (column.offset() + column.size() > rowData.length) {
                continue;
            }
            buffer.position(column.offset());

            switch (column.size()) {
                case 4 -> parseFourByteColumn(column, buffer.getInt(), rowProperties);
                case 8 -> {
                    long value = buffer.getLong();
                    switch (column.valueType()) {
                        case 0x0040 -> rowProperties.put(column.tagId(), PropertyContext.fileTimeToInstant(value));
                        case 0x0005 -> rowProperties.put(column.tagId(), Double.longBitsToDouble(value));
                        default -> rowProperties.put(column.tagId(), value);
                    }
                }
                case 2 -> {
                    short shortValue = buffer.getShort();
                    // PT_SHORT (i2) is signed, matching the PC path; other 2-byte cells (e.g. the
                    // ANSI row-index) keep the raw unsigned interpretation.
                    rowProperties.put(
                            column.tagId(),
                            column.valueType() == 0x0002 ? (int) shortValue : Short.toUnsignedInt(shortValue));
                }
                case 16 -> { // PT_CLSID — surfaced as the raw 16 bytes, matching the PC path
                    var clsid = new byte[16];
                    buffer.get(clsid);
                    rowProperties.put(column.tagId(), clsid);
                }
                case 1 -> {
                    int value = Byte.toUnsignedInt(buffer.get());
                    rowProperties.put(column.tagId(), column.valueType() == 0x000B ? (Object) (value != 0) : value);
                }
                default ->
                    LOG.log(
                            System.Logger.Level.DEBUG,
                            () -> "Unhandled column width " + column.size() + " for tag 0x"
                                    + Integer.toHexString(column.tagId()));
            }
        }
        return rowProperties;
    }

    private void parseFourByteColumn(ColumnDescriptor column, int value, Map<Integer, Object> rowProperties) {
        switch (column.valueType()) {
            case 0x001F -> { // PT_UNICODE
                byte[] data = value != 0 ? resolveData(value) : null;
                if (data != null && data.length > 0) {
                    rowProperties.put(
                            column.tagId(),
                            PropertyContext.stripTrailingNuls(new String(data, StandardCharsets.UTF_16LE)));
                }
            }
            case 0x001E -> { // PT_STRING8
                byte[] data = value != 0 ? resolveData(value) : null;
                if (data != null && data.length > 0) {
                    var effectiveCharset = charset != null ? charset : StandardCharsets.ISO_8859_1;
                    rowProperties.put(
                            column.tagId(), PropertyContext.stripTrailingNuls(new String(data, effectiveCharset)));
                }
            }
            case 0x0102 -> { // PT_BINARY
                byte[] data = value != 0 ? resolveData(value) : null;
                if (data != null && data.length > 0) {
                    rowProperties.put(column.tagId(), data);
                }
            }
            case 0x0003 -> rowProperties.put(column.tagId(), value); // PT_LONG
            case 0x0004 -> rowProperties.put(column.tagId(), Float.intBitsToFloat(value)); // PT_FLOAT
            case 0x1003, 0x1014, 0x1040, 0x101E, 0x101F, 0x1102 -> { // multi-valued
                byte[] data = value != 0 ? resolveData(value) : null;
                if (data != null && data.length > 0) {
                    var effectiveCharset = charset != null ? charset : StandardCharsets.ISO_8859_1;
                    rowProperties.put(
                            column.tagId(),
                            PropertyContext.parseMultiValue(column.valueType(), data, effectiveCharset));
                }
            }
            default ->
                LOG.log(
                        System.Logger.Level.DEBUG,
                        () -> "Unhandled 4-byte column type 0x" + Integer.toHexString(column.valueType())
                                + " for tag 0x" + Integer.toHexString(column.tagId()));
        }
    }

    private static int readUnsigned(ByteBuffer buffer, int size) {
        return switch (size) {
            case 1 -> Byte.toUnsignedInt(buffer.get());
            case 2 -> Short.toUnsignedInt(buffer.getShort());
            default -> buffer.getInt();
        };
    }

    public List<Map<Integer, Object>> getRows() {
        return rows;
    }

    private byte[] resolveData(int hnid) {
        if ((hnid & 0x1F) == 0) {
            return heap.getItem(hnid);
        }
        // Subnode NID: resolve against the TC's own subnode tree first ([MS-PST] §2.3.3.2), then the
        // optional fallback tree; treating an unresolvable NID as a heap id would surface an
        // unrelated heap item as the cell value.
        byte[] data = readSubnodeBytes(node, hnid);
        if (data == null) {
            data = readSubnodeBytes(fallbackNode, hnid);
        }
        if (data == null) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () -> "Table cell subnode 0x" + Integer.toHexString(hnid) + " not found; the cell value is lost");
        }
        return data;
    }

    /** The data of subnode {@code nid} within {@code owner}'s subnode tree, or {@code null}. */
    private byte[] readSubnodeBytes(NodeEntry owner, int nid) {
        if (nodeDatabase == null || owner == null || owner.subBid() == 0) {
            return null;
        }
        try {
            return nodeDatabase.readSubnodeData(owner.subBid(), nid);
        } catch (IOException exception) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () -> "Failed to read table subnode 0x" + Integer.toHexString(nid),
                    exception);
            return null;
        }
    }

    private record ColumnDescriptor(int propertyTag, int valueType, int offset, int size, int existenceBit) {
        public int tagId() {
            return propertyTag >> 16;
        }
    }
}
