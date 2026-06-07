package com.github.ttereshchenko.mailkit.conversion.pst;

import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Table Context (TC) structures stored within a Node's data block.
 * A TC provides rows of properties (columns), used for Folder Hierarchy and Message tables.
 */
public class TableContext {

    private static final Logger LOG = Logger.getInstance(TableContext.class);

    private final HeapOnNode heap;
    private final List<ColumnDescriptor> columns = new ArrayList<>();
    private final List<Map<Integer, Object>> rows = new ArrayList<>();
    private int tciBm;
    private int tci1b;
    private int hnidRows;
    private int hidRowIndex;
    private final NodeDatabase nodeDatabase;
    private final NodeEntry node;
    private final Charset charset;

    public TableContext(byte[] nodeData, NodeDatabase nodeDatabase, NodeEntry node) {
        this(nodeData, nodeDatabase, node, null);
    }

    public TableContext(byte[] nodeData, NodeDatabase nodeDatabase, NodeEntry node, Charset charset) {
        this.nodeDatabase = nodeDatabase;
        this.node = node;
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
        if (tcInfo.length < 4) return;

        var buf = ByteBuffer.wrap(tcInfo).order(ByteOrder.LITTLE_ENDIAN);
        int bType = Byte.toUnsignedInt(buf.get());
        if (bType != 0x7C) return; // Must be TC

        int cCols = Byte.toUnsignedInt(buf.get());

        // The fixed TCINFO header is 22 bytes, followed by a cCols-long TCOLDESC array of 8 bytes each
        // ([MS-PST] §2.3.4.1). A truncated buffer would throw IndexOutOfBounds on the reads below.
        if (tcInfo.length < 22 + cCols * 8) {
            return;
        }

        int rgib2 = Short.toUnsignedInt(buf.getShort(6)); // Offset to CEB
        int rgib3 = Short.toUnsignedInt(buf.getShort(8)); // Total row size
        this.hnidRows = buf.getInt(14); // hnidRows

        this.tci1b = rgib2;
        this.tciBm = rgib3;
        this.hidRowIndex = buf.getInt(10);

        buf.position(22); // TCOLDESC array starts at 22
        for (int i = 0; i < cCols; i++) {
            int valType = Short.toUnsignedInt(buf.getShort());
            int tagId = Short.toUnsignedInt(buf.getShort());
            int offset = Short.toUnsignedInt(buf.getShort());
            int size = Byte.toUnsignedInt(buf.get());
            int iBit = Byte.toUnsignedInt(buf.get());

            int propertyTag = (tagId << 16) | valType;
            columns.add(new ColumnDescriptor(propertyTag, valType, offset, size, iBit));
        }
    }

    private void parseRows() {
        if (columns.isEmpty()) return;

        if (hidRowIndex == 0) return;
        byte[] bthHeader = heap.getItem(hidRowIndex);
        if (bthHeader.length < 8) return;

        var buf = ByteBuffer.wrap(bthHeader).order(ByteOrder.LITTLE_ENDIAN);
        int bType = Byte.toUnsignedInt(buf.get(0));
        if (bType != 0xB5) return;

        int cbKey = Byte.toUnsignedInt(buf.get(1));
        int cbEnt = Byte.toUnsignedInt(buf.get(2));
        int bIdxLevels = Byte.toUnsignedInt(buf.get(3));
        int hidRoot = buf.getInt(4);

        parseBTreeNode(hidRoot, cbKey, cbEnt, bIdxLevels);
    }

    private void parseBTreeNode(int hidRoot, int cbKey, int cbEnt, int level) {
        parseBTreeNode(hidRoot, cbKey, cbEnt, level, new java.util.HashSet<>());
    }

    private void parseBTreeNode(int hidRoot, int cbKey, int cbEnt, int level, java.util.Set<Integer> visited) {
        if (!visited.add(hidRoot)) {
            throw new IllegalArgumentException("Cyclic B-Tree reference: " + hidRoot);
        }
        if (level > 8) {
            throw new IllegalArgumentException("BTH recursion limit exceeded");
        }
        if (level == 0) {
            parseLeafNode(hidRoot, cbKey, cbEnt);
            return;
        }

        byte[] branchData = heap.getItem(hidRoot);
        var buf = ByteBuffer.wrap(branchData).order(ByteOrder.LITTLE_ENDIAN);
        int entrySize = cbKey + 4;
        int numEntries = branchData.length / entrySize;

        for (int i = 0; i < numEntries; i++) {
            buf.position(i * entrySize + cbKey);
            int childHid = buf.getInt();
            parseBTreeNode(childHid, cbKey, cbEnt, level - 1, visited);
        }
    }

    private void parseLeafNode(int hidRoot, int cbKey, int cbEnt) {
        byte[] leafData = heap.getItem(hidRoot);
        if (leafData.length == 0) {
            return;
        }
        // cbKey/cbEnt come from the (untrusted) BTH header; a zero sum would divide-by-zero here.
        if (cbKey + cbEnt == 0) {
            return;
        }
        var buf = ByteBuffer.wrap(leafData).order(ByteOrder.LITTLE_ENDIAN);

        int numEntries = leafData.length / (cbKey + cbEnt);

        byte[] rowMatrix = null;
        if ((hnidRows & 0x1F) != 0) {
            if (nodeDatabase != null && node != null && node.subBid() != 0) {
                try {
                    rowMatrix = nodeDatabase.readSubnodeData(node.subBid(), hnidRows);
                } catch (IOException exception) {
                    LOG.warn("Failed to read table row-matrix subnode", exception);
                }
            }
        }
        if (rowMatrix == null) {
            rowMatrix = heap.getItem(hnidRows);
        }

        for (int i = 0; i < numEntries; i++) {
            // TCROWID = dwRowID (cbKey bytes) + dwRowIndex (cbEnt bytes; 4 for Unicode, 2 for ANSI).
            buf.position(i * (cbKey + cbEnt));
            int rowId = readUnsigned(buf, cbKey);
            int rowIndex = readUnsigned(buf, cbEnt);

            byte[] rowData = new byte[tciBm];
            boolean rowLoaded = false;

            long startOffset = (long) rowIndex * tciBm;
            if (startOffset >= 0 && startOffset + tciBm <= rowMatrix.length) {
                System.arraycopy(rowMatrix, (int) startOffset, rowData, 0, tciBm);
                rowLoaded = true;
            }

            if (rowLoaded) {
                Map<Integer, Object> rowProps = parseRowData(rowId, rowData);
                rows.add(rowProps);
            }
        }
    }

    private Map<Integer, Object> parseRowData(int rowId, byte[] rowData) {
        Map<Integer, Object> props = new HashMap<>();
        var buf = ByteBuffer.wrap(rowData).order(ByteOrder.LITTLE_ENDIAN);

        // CEB (Cell Existence Bitmap) is at tci_1b
        int cebLength = (int) Math.ceil(columns.size() / 8.0);
        byte[] ceb = new byte[cebLength];
        if (tci1b + cebLength <= rowData.length) {
            System.arraycopy(rowData, tci1b, ceb, 0, cebLength);
        }

        for (var col : columns) {
            int byteIndex = col.iBit() / 8;
            int bitIndex = col.iBit() % 8;
            if (byteIndex < ceb.length && (ceb[byteIndex] & (1 << (7 - bitIndex))) == 0) {
                continue; // CEB bit is 0, column does not exist for this row
            }

            if (col.offset() + col.size() <= rowData.length) {
                buf.position(col.offset());

                if (col.size() == 4) {
                    int val = buf.getInt();
                    if (col.valType() == 0x001F) { // String
                        if (val != 0) {
                            byte[] strData = resolveData(val);
                            if (strData.length > 0) {
                                props.put(col.tagId(), new String(strData, StandardCharsets.UTF_16LE).trim());
                            }
                        }
                    } else if (col.valType() == 0x001E) { // String8 (ANSI)
                        if (val != 0) {
                            byte[] strData = resolveData(val);
                            if (strData.length > 0) {
                                Charset charsetToUse =
                                        this.charset != null ? this.charset : StandardCharsets.ISO_8859_1;
                                props.put(col.tagId(), new String(strData, charsetToUse).trim());
                            }
                        }
                    } else if (col.valType() == 0x0102) { // Binary
                        if (val != 0) {
                            byte[] binData = resolveData(val);
                            if (binData.length > 0) {
                                props.put(col.tagId(), binData);
                            }
                        }
                    } else if (col.valType() == 0x0003) { // Int32
                        props.put(col.tagId(), val);
                    }
                } else if (col.size() == 8) {
                    long val = buf.getLong();
                    if (col.valType() == 0x0040) { // PT_SYSTIME
                        long epochDiff = 11644473600000L;
                        props.put(col.tagId(), new Date(val / 10000 - epochDiff));
                    } else {
                        props.put(col.tagId(), val);
                    }
                } else if (col.size() == 2) {
                    props.put(col.tagId(), Short.toUnsignedInt(buf.getShort()));
                } else if (col.size() == 1) {
                    props.put(col.tagId(), Byte.toUnsignedInt(buf.get()));
                }
            }
        }
        return props;
    }

    private static int readUnsigned(ByteBuffer buf, int size) {
        return switch (size) {
            case 1 -> Byte.toUnsignedInt(buf.get());
            case 2 -> Short.toUnsignedInt(buf.getShort());
            default -> buf.getInt();
        };
    }

    public List<Map<Integer, Object>> getRows() {
        return rows;
    }

    private byte[] resolveData(int hnid) {
        if ((hnid & 0x1F) != 0) {
            if (nodeDatabase != null && node != null && node.subBid() != 0) {
                try {
                    byte[] data = nodeDatabase.readSubnodeData(node.subBid(), hnid);
                    if (data != null) return data;
                } catch (IOException ignored) {
                    // fall through to the in-heap item
                }
            }
        }
        return heap.getItem(hnid);
    }

    private record ColumnDescriptor(int propertyTag, int valType, int offset, int size, int iBit) {
        public int tagId() {
            return propertyTag >> 16;
        }
    }
}
