package com.github.ttereshchenko.mailkit.pst;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses Property Context (PC) structures stored within a Node's data block ([MS-PST] §2.3.3).
 * A PC is a BTree-on-Heap (BTH) that maps 16-bit property IDs to property values.
 *
 * <p>Binary properties whose data lives in a subnode are <em>not</em> materialized during parsing;
 * they are resolved on first access ({@link #getProperty}) or streamed without materialization via
 * {@link #openBinaryStream}, so walking a store does not pull every attachment payload into memory.
 */
class PropertyContext {

    private static final System.Logger LOG = System.getLogger(PropertyContext.class.getName());

    /** 100-nanosecond intervals between 1601-01-01 (FILETIME epoch) and 1970-01-01 (Unix epoch). */
    private static final long FILETIME_EPOCH_DIFF = 116_444_736_000_000_000L;

    private static final int MAX_BTH_LEVELS = 8;

    private final Map<Integer, Object> properties = new HashMap<>();
    private final Set<Integer> string8Tags = new HashSet<>();
    /** Subnode-resident binary properties deferred until first access: tag → subnode NID. */
    private final Map<Integer, Integer> pendingSubnodeBinaries = new HashMap<>();

    private final NodeDatabase nodeDatabase;
    private final NodeEntry node;

    PropertyContext(byte[] nodeData, NodeDatabase nodeDatabase, NodeEntry node) throws PstException {
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

    private void parseBTreeOnHeap(HeapOnNode heap) throws PstException {
        byte[] bthHeader = heap.getItem(heap.userRootHid());
        if (bthHeader.length < 8) {
            return;
        }

        var buffer = ByteBuffer.wrap(bthHeader).order(ByteOrder.LITTLE_ENDIAN);
        int headerType = Byte.toUnsignedInt(buffer.get(0));
        if (headerType != 0xB5) { // Must be BTH
            return;
        }

        int keySize = Byte.toUnsignedInt(buffer.get(1));
        int entrySize = Byte.toUnsignedInt(buffer.get(2));
        int indexLevels = Byte.toUnsignedInt(buffer.get(3));
        int hidRoot = buffer.getInt(4);

        parseBTreeNode(heap, hidRoot, keySize, entrySize, indexLevels, new HashSet<>());
    }

    private void parseBTreeNode(
            HeapOnNode heap, int hidRoot, int keySize, int entrySize, int level, Set<Integer> visited)
            throws PstException {
        if (!visited.add(hidRoot)) {
            throw new PstException("Cyclic B-Tree-on-Heap reference: " + hidRoot);
        }
        if (level > MAX_BTH_LEVELS) {
            throw new PstException("BTH recursion limit exceeded");
        }
        if (level == 0) {
            parseLeafNode(heap, hidRoot, keySize, entrySize);
            return;
        }

        byte[] branchData = heap.getItem(hidRoot);
        var buffer = ByteBuffer.wrap(branchData).order(ByteOrder.LITTLE_ENDIAN);

        int branchEntrySize = keySize + 4;
        int entryCount = branchData.length / branchEntrySize;

        for (int i = 0; i < entryCount; i++) {
            buffer.position(i * branchEntrySize + keySize);
            int childHid = buffer.getInt();
            parseBTreeNode(heap, childHid, keySize, entrySize, level - 1, visited);
        }
    }

    private void parseLeafNode(HeapOnNode heap, int hidRoot, int keySize, int entrySize) {
        byte[] leafData = heap.getItem(hidRoot);
        // A PC's BTH key is always 2 bytes and each record is 4 or 6 ([MS-PST] §2.3.3.3 / §2.3.4.3);
        // reject any other shape — including the cbKey+cbEnt==0 divide-by-zero — instead of letting the
        // fixed-width reads below drift past the buffer into a BufferUnderflowException.
        if (keySize != 2 || (entrySize != 4 && entrySize != 6)) {
            return;
        }
        var buffer = ByteBuffer.wrap(leafData).order(ByteOrder.LITTLE_ENDIAN);

        int stride = keySize + entrySize;
        int entryCount = leafData.length / stride;
        for (int i = 0; i < entryCount; i++) {
            // Seek to each record by its stride so a malformed entry cannot shift every later read.
            buffer.position(i * stride);
            int tag = Short.toUnsignedInt(buffer.getShort()); // cbKey is 2

            if (entrySize == 6) {
                int valueType = Short.toUnsignedInt(buffer.getShort());
                int value = buffer.getInt();
                parseRecord(heap, tag, valueType, value);
            } else {
                properties.put(tag, buffer.getInt());
            }
        }
    }

    private void parseRecord(HeapOnNode heap, int tag, int valueType, int value) {
        switch (valueType) {
            case 0x001F, 0x001E -> { // PT_UNICODE / PT_STRING8
                byte[] data = resolveVariableData(heap, value);
                if (data == null) {
                    return;
                }
                if (valueType == 0x001F) {
                    properties.put(tag, stripTrailingNuls(new String(data, StandardCharsets.UTF_16LE)));
                } else {
                    properties.put(tag, stripTrailingNuls(new String(data, StandardCharsets.ISO_8859_1)));
                    string8Tags.add(tag);
                }
            }
            case 0x0102 -> { // PT_BINARY
                if ((value & 0x1F) != 0 && nodeDatabase != null && node != null && node.subBid() != 0) {
                    // Subnode-resident binary (attachment payloads live here); defer until accessed.
                    pendingSubnodeBinaries.put(tag, value);
                    return;
                }
                byte[] data = heap.getItem(value);
                if (data != null) {
                    properties.put(tag, data);
                }
            }
            case 0x0003 -> properties.put(tag, value); // PT_LONG
            case 0x0002 -> properties.put(tag, (int) (short) value); // PT_SHORT
            case 0x0004 -> properties.put(tag, Float.intBitsToFloat(value)); // PT_FLOAT
            case 0x000B -> properties.put(tag, value != 0); // PT_BOOLEAN
            case 0x000D -> { // PT_OBJECT — the HNID points to a {Nid, ulSize} heap struct ([MS-PST] §2.3.3.5)
                byte[] data = heap.getItem(value);
                if (data.length >= 4) {
                    // Surface the subnode NID (e.g. of an embedded message), not the raw HNID.
                    properties.put(
                            tag,
                            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt());
                } else {
                    properties.put(tag, value);
                }
            }
            case 0x0014, 0x0005 -> { // PT_LONGLONG / PT_DOUBLE — 8-byte heap item
                byte[] data = heap.getItem(value);
                if (data != null && data.length >= 8) {
                    long bits =
                            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong();
                    properties.put(tag, valueType == 0x0014 ? bits : Double.longBitsToDouble(bits));
                }
            }
            case 0x0040 -> { // PT_SYSTIME
                byte[] data = heap.getItem(value);
                if (data != null && data.length >= 8) {
                    long fileTime =
                            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong();
                    properties.put(tag, fileTimeToInstant(fileTime));
                }
            }
            case 0x0048 -> { // PT_CLSID — 16-byte heap item, surfaced raw
                byte[] data = heap.getItem(value);
                if (data != null && data.length >= 16) {
                    properties.put(tag, data);
                }
            }
            case 0x1003, 0x1014, 0x1040, 0x101E, 0x101F, 0x1102 -> { // multi-valued
                byte[] data = resolveVariableData(heap, value);
                if (data != null) {
                    properties.put(tag, parseMultiValue(valueType, data, StandardCharsets.ISO_8859_1));
                    if (valueType == 0x101E) {
                        string8Tags.add(tag);
                    }
                }
            }
            default ->
                LOG.log(
                        System.Logger.Level.DEBUG,
                        () -> "Unhandled property type 0x" + Integer.toHexString(valueType) + " for tag 0x"
                                + Integer.toHexString(tag));
        }
    }

    /** Variable-length data addressed by an HNID: a subnode NID (low 5 bits set) or an in-heap HID. */
    private byte[] resolveVariableData(HeapOnNode heap, int hnid) {
        if ((hnid & 0x1F) != 0 && nodeDatabase != null && node != null && node.subBid() != 0) {
            try {
                byte[] data = nodeDatabase.readSubnodeData(node.subBid(), hnid);
                if (data != null) {
                    return data;
                }
            } catch (IOException exception) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () -> "Failed to read subnode property data for HNID 0x" + Integer.toHexString(hnid),
                        exception);
            }
        }
        return heap.getItem(hnid);
    }

    /**
     * The parsed value for the property id, or {@code null} if absent. Subnode-resident binary
     * properties are materialized (and cached) on first access.
     */
    public Object getProperty(int propertyId) {
        var value = properties.get(propertyId);
        if (value != null) {
            return value;
        }
        var subnodeNid = pendingSubnodeBinaries.get(propertyId);
        if (subnodeNid == null) {
            return null;
        }
        try {
            byte[] data = nodeDatabase.readSubnodeData(node.subBid(), subnodeNid);
            if (data != null) {
                properties.put(propertyId, data);
                pendingSubnodeBinaries.remove(propertyId);
                return data;
            }
        } catch (IOException exception) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () -> "Failed to read subnode binary property 0x" + Integer.toHexString(propertyId),
                    exception);
        }
        return null;
    }

    /**
     * Materializes the subnode data behind a PT_OBJECT property (whose parsed value is the subnode
     * NID, see the {@code 0x000D} case above) — e.g. the raw storage of an OLE attachment object.
     * Returns {@code null} when the property is absent, not a PT_OBJECT NID, or unresolvable.
     */
    byte[] readObjectData(int propertyId) throws IOException {
        if (!(properties.get(propertyId) instanceof Integer subnodeNid)
                || nodeDatabase == null
                || node == null
                || node.subBid() == 0) {
            return null;
        }
        return nodeDatabase.readSubnodeData(node.subBid(), subnodeNid);
    }

    /**
     * Opens a stream over a binary property's content. Subnode-resident data is streamed block by
     * block without being materialized; heap-resident data is wrapped as-is. Returns {@code null}
     * if the property is absent or not binary.
     */
    InputStream openBinaryStream(int propertyId) throws IOException {
        var subnodeNid = pendingSubnodeBinaries.get(propertyId);
        if (subnodeNid != null && nodeDatabase != null && node != null && node.subBid() != 0) {
            var entry = nodeDatabase.readSubnodeEntry(node.subBid(), subnodeNid);
            if (entry == null) {
                return null;
            }
            return nodeDatabase.openNodeDataStream(entry.dataBid());
        }
        return getProperty(propertyId) instanceof byte[] bytes ? new ByteArrayInputStream(bytes) : null;
    }

    public void decodeString8(Charset charset) {
        if (charset == null || charset.equals(StandardCharsets.ISO_8859_1)) {
            return;
        }
        for (Integer tag : string8Tags) {
            Object value = properties.get(tag);
            if (value instanceof String text) {
                properties.put(tag, redecode(text, charset));
            } else if (value instanceof List<?> list) {
                var redecoded = new ArrayList<>(list.size());
                for (Object element : list) {
                    redecoded.add(element instanceof String text ? redecode(text, charset) : element);
                }
                properties.put(tag, Collections.unmodifiableList(redecoded));
            }
        }
    }

    private static String redecode(String latin1Text, Charset charset) {
        byte[] raw = latin1Text.getBytes(StandardCharsets.ISO_8859_1);
        return stripTrailingNuls(new String(raw, charset));
    }

    /**
     * Strips trailing NUL terminators some PST writers persist into string properties. Deliberately
     * narrower than {@link String#trim()}: real leading/trailing whitespace is message content
     * (e.g. in PR_BODY) and must survive conversion, and trimming before {@link #redecode} would
     * destroy charsets whose escape bytes are control characters (ISO-2022's leading ESC).
     */
    static String stripTrailingNuls(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\0') {
            end--;
        }
        return end == text.length() ? text : text.substring(0, end);
    }

    public String getString(int propertyId) {
        return getProperty(propertyId) instanceof String value ? value : null;
    }

    /**
     * All parsed properties, keyed by 16-bit property id. Deferred subnode binaries are resolved
     * first, so prefer {@link #getProperty} when only specific tags are needed.
     */
    public Map<Integer, Object> getProperties() {
        for (Integer tag : List.copyOf(pendingSubnodeBinaries.keySet())) {
            getProperty(tag);
        }
        return Collections.unmodifiableMap(properties);
    }

    public NodeEntry getNode() {
        return node;
    }

    /** Converts a Windows FILETIME (100ns intervals since 1601-01-01 UTC) to an {@link Instant}. */
    static Instant fileTimeToInstant(long fileTime) {
        long hundredNanos = fileTime - FILETIME_EPOCH_DIFF;
        return Instant.ofEpochSecond(
                Math.floorDiv(hundredNanos, 10_000_000L), Math.floorMod(hundredNanos, 10_000_000L) * 100);
    }

    /**
     * Parses a multi-valued property blob ([MS-PST] §2.3.3.4): fixed-width types are packed values;
     * variable-width types are a count, an offset table and the concatenated payloads. Unknown
     * shapes are returned raw.
     */
    static Object parseMultiValue(int valueType, byte[] data, Charset string8Charset) {
        return switch (valueType) {
            case 0x1003 -> fixedMultiValue(data, 4, buffer -> buffer.getInt());
            case 0x1014 -> fixedMultiValue(data, 8, buffer -> buffer.getLong());
            case 0x1040 -> fixedMultiValue(data, 8, buffer -> fileTimeToInstant(buffer.getLong()));
            case 0x101F -> {
                var values = new ArrayList<String>();
                for (byte[] segment : splitVariableMultiValue(data)) {
                    values.add(stripTrailingNuls(new String(segment, StandardCharsets.UTF_16LE)));
                }
                yield Collections.unmodifiableList(values);
            }
            case 0x101E -> {
                var values = new ArrayList<String>();
                for (byte[] segment : splitVariableMultiValue(data)) {
                    values.add(stripTrailingNuls(new String(segment, string8Charset)));
                }
                yield Collections.unmodifiableList(values);
            }
            case 0x1102 -> Collections.unmodifiableList(splitVariableMultiValue(data));
            default -> data;
        };
    }

    private interface FixedValueReader {
        Object read(ByteBuffer buffer);
    }

    private static List<Object> fixedMultiValue(byte[] data, int width, FixedValueReader reader) {
        // A fixed-width multi-value blob is exactly count*width bytes ([MS-PST] §2.3.3.4.1); a length
        // that is not a whole multiple of the element width signals truncation/corruption, and the
        // trailing partial element is dropped — log it at DEBUG so the silent loss is diagnosable.
        if (data.length % width != 0) {
            LOG.log(
                    System.Logger.Level.DEBUG,
                    () -> "Fixed-width multi-value blob length " + data.length + " is not a multiple of element width "
                            + width + "; dropping the trailing partial element");
        }
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        var values = new ArrayList<>();
        for (int offset = 0; offset + width <= data.length; offset += width) {
            buffer.position(offset);
            values.add(reader.read(buffer));
        }
        return Collections.unmodifiableList(values);
    }

    private static List<byte[]> splitVariableMultiValue(byte[] data) {
        if (data.length < 4) {
            return List.of();
        }
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int count = buffer.getInt(0);
        // [MS-PST] §2.3.3.4.2: the record is ulCount followed by exactly ulCount rgulDataOffsets (item
        // starts). The count and offsets come from untrusted data; require the offset table to fit and
        // each segment to lie inside the blob, skipping (not aborting on) individual bad segments.
        if (count <= 0 || 4 + (long) count * 4 > data.length) {
            return List.of();
        }
        var segments = new ArrayList<byte[]>(count);
        for (int i = 0; i < count; i++) {
            long start = Integer.toUnsignedLong(buffer.getInt(4 + i * 4));
            // [MS-PST] §2.3.3.4.2: length(N) = rgulDataOffsets[N+1] - rgulDataOffsets[N], EXCEPT the last
            // item, which runs to the total size of the MV property data record (there is no terminating
            // rgulDataOffsets[count] entry, and rgDataItems is byte-aligned with no trailing padding).
            long end = (i + 1 < count) ? Integer.toUnsignedLong(buffer.getInt(4 + (i + 1) * 4)) : data.length;
            if (start > end || end > data.length) {
                continue;
            }
            var segment = new byte[(int) (end - start)];
            System.arraycopy(data, (int) start, segment, 0, segment.length);
            segments.add(segment);
        }
        return segments;
    }
}
