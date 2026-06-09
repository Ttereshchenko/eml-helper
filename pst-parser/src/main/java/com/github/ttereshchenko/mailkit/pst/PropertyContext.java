package com.github.ttereshchenko.mailkit.pst;

// TODO: re-visit log
// import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Parses Property Context (PC) structures stored within a Node's data block.
 * A PC is essentially a BTree-on-Heap (BTH) that maps 16-bit property IDs (tags) to property values.
 */
class PropertyContext {

    // TODO: re-visit log
    // private static final Logger LOG = Logger.getInstance(PropertyContext.class);

    private final Map<Integer, Object> properties = new HashMap<>();
    private final Set<Integer> string8Tags = new HashSet<>();
    private final NodeDatabase nodeDatabase;
    private final NodeEntry node;

    public PropertyContext(byte[] nodeData, NodeDatabase nodeDatabase, NodeEntry node) {
        this.nodeDatabase = nodeDatabase;
        this.node = node;
        if (nodeData == null || nodeData.length < 16) {
            return;
        }
        var heap = nodeDatabase != null
                ? new HeapOnNode(nodeData, nodeDatabase.heapBlockSize())
                : new HeapOnNode(nodeData);
        parseBTreeOnHeap(heap);
    }

    private void parseBTreeOnHeap(HeapOnNode heap) {
        byte[] bthHeader = heap.getItem(heap.userRootHid());
        if (bthHeader.length < 8) return;

        var buf = ByteBuffer.wrap(bthHeader).order(ByteOrder.LITTLE_ENDIAN);
        int bType = Byte.toUnsignedInt(buf.get(0));
        if (bType != 0xB5) return; // Must be BTH

        int cbKey = Byte.toUnsignedInt(buf.get(1));
        int cbEnt = Byte.toUnsignedInt(buf.get(2));
        int bIdxLevels = Byte.toUnsignedInt(buf.get(3));
        int hidRoot = buf.getInt(4);

        parseBTreeNode(heap, hidRoot, cbKey, cbEnt, bIdxLevels);
    }

    private void parseBTreeNode(HeapOnNode heap, int hidRoot, int cbKey, int cbEnt, int level) {
        parseBTreeNode(heap, hidRoot, cbKey, cbEnt, level, new java.util.HashSet<>());
    }

    private void parseBTreeNode(
            HeapOnNode heap, int hidRoot, int cbKey, int cbEnt, int level, java.util.Set<Integer> visited) {
        if (!visited.add(hidRoot)) {
            throw new IllegalArgumentException("Cyclic B-Tree reference: " + hidRoot);
        }
        if (level > 8) {
            throw new IllegalArgumentException("BTH recursion limit exceeded");
        }
        if (level == 0) {
            parseLeafNode(heap, hidRoot, cbKey, cbEnt);
            return;
        }

        byte[] branchData = heap.getItem(hidRoot);
        var buf = ByteBuffer.wrap(branchData).order(ByteOrder.LITTLE_ENDIAN);

        int entrySize = cbKey + 4;
        int numEntries = branchData.length / entrySize;

        for (int i = 0; i < numEntries; i++) {
            buf.position(i * entrySize + cbKey);
            int childHid = buf.getInt();
            parseBTreeNode(heap, childHid, cbKey, cbEnt, level - 1, visited);
        }
    }

    private void parseLeafNode(HeapOnNode heap, int hidRoot, int cbKey, int cbEnt) {
        byte[] leafData = heap.getItem(hidRoot);
        // A PC's BTH key is always 2 bytes and each record is 4 or 6 ([MS-PST] §2.3.3.3 / §2.3.4.3);
        // reject any other shape — including the cbKey+cbEnt==0 divide-by-zero — instead of letting the
        // fixed-width reads below drift past the buffer into a BufferUnderflowException.
        if (cbKey != 2 || (cbEnt != 4 && cbEnt != 6)) {
            return;
        }
        var buf = ByteBuffer.wrap(leafData).order(ByteOrder.LITTLE_ENDIAN);

        int stride = cbKey + cbEnt;
        int numEntries = leafData.length / stride;
        for (int i = 0; i < numEntries; i++) {
            // Seek to each record by its stride so a malformed entry cannot shift every later read.
            buf.position(i * stride);
            int tag = Short.toUnsignedInt(buf.getShort()); // cbKey is 2

            if (cbEnt == 6) {
                int valType = Short.toUnsignedInt(buf.getShort());
                int val = buf.getInt();

                if (valType == 0x001F || valType == 0x001E || valType == 0x0102) { // String or Binary
                    byte[] data = null;
                    if ((val & 0x1F) != 0) { // Subnode NID
                        if (nodeDatabase != null && node != null && node.subBid() != 0) {
                            try {
                                data = nodeDatabase.readSubnodeData(node.subBid(), val);
                            } catch (IOException exception) {
                                // TODO: re-visit log
                                // LOG.warn("Failed to read external property data for tag 0x" +
                                // Integer.toHexString(tag), exception);
                            }
                        }
                    }
                    if (data == null) {
                        data = heap.getItem(val);
                    }

                    if (data != null) {
                        if (data.length == 0) {
                            if (valType == 0x001F || valType == 0x001E) {
                                properties.put(tag, "");
                                if (valType == 0x001E) {
                                    string8Tags.add(tag);
                                }
                            } else {
                                properties.put(tag, data);
                            }
                        } else if (valType == 0x001F) {
                            properties.put(tag, new String(data, StandardCharsets.UTF_16LE).trim());
                        } else if (valType == 0x001E) {
                            properties.put(tag, new String(data, StandardCharsets.ISO_8859_1).trim());
                            string8Tags.add(tag);
                        } else {
                            properties.put(tag, data);
                        }
                    }
                } else if (valType == 0x0003) { // Integer 32
                    properties.put(tag, val);
                } else if (valType == 0x000D) { // PT_OBJECT
                    properties.put(tag, val);
                } else if (valType == 0x000B) { // Boolean
                    properties.put(tag, val != 0);
                } else if (valType == 0x0040) { // PT_SYSTIME
                    byte[] data = heap.getItem(val);
                    if (data != null && data.length >= 8) {
                        long fileTime = ByteBuffer.wrap(data)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .getLong();
                        long epochDiff = 11644473600000L;
                        properties.put(tag, new Date(fileTime / 10000 - epochDiff));
                    }
                }
            } else if (cbEnt == 4) {
                properties.put(tag, buf.getInt());
            }
        }
    }

    public Object getProperty(int propertyId) {
        return properties.get(propertyId);
    }

    public void decodeString8(Charset charset) {
        if (charset == null || charset.equals(StandardCharsets.ISO_8859_1)) {
            return;
        }
        for (Integer tag : string8Tags) {
            Object obj = properties.get(tag);
            if (obj instanceof String) {
                byte[] raw = ((String) obj).getBytes(StandardCharsets.ISO_8859_1);
                properties.put(tag, new String(raw, charset).trim());
            }
        }
    }

    public String getString(int propertyId) {
        var val = properties.get(propertyId);
        return val instanceof String ? (String) val : null;
    }

    public Map<Integer, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    public NodeEntry getNode() {
        return node;
    }
}
