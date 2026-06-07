package com.github.ttereshchenko.mailkit.conversion.pst;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents a Heap-on-Node (HN) which provides a memory allocation structure
 * within a PST Node's data block.
 */
public class HeapOnNode {

    /** Unicode block payload (8192 - 16-byte trailer); used when the format is unknown. */
    private static final int DEFAULT_BLOCK_PAYLOAD_SIZE = 8176;

    private final byte[] data;
    private final int hidUserRoot;
    private final int blockPayloadSize;

    public HeapOnNode(byte[] data) {
        this(data, DEFAULT_BLOCK_PAYLOAD_SIZE);
    }

    /**
     * @param blockPayloadSize the size of one NDB block payload in {@code data}, i.e. the stride
     *     between consecutive HN blocks in a multi-block node (see {@link NodeDatabase#heapBlockSize}).
     */
    public HeapOnNode(byte[] data, int blockPayloadSize) {
        this.data = data;
        this.blockPayloadSize = blockPayloadSize;
        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        int bSig = Byte.toUnsignedInt(buf.get(2));
        if (bSig != 0xEC) {
            throw new IllegalArgumentException("Invalid Heap-on-Node signature: " + bSig);
        }

        this.hidUserRoot = buf.getInt(4);
    }

    public int userRootHid() {
        return hidUserRoot;
    }

    public byte[] getItem(int hid) {
        if (hid == 0) {
            return new byte[0];
        }
        // hidBlockIndex is the top 16 bits
        int hidBlockIndex = hid >>> 16;
        int hidIndex = (hid >>> 5) & 0x7FF;

        int pageOffset = hidBlockIndex * blockPayloadSize; // start of the HN block in the node payload
        // Need 2 bytes for the ibHnpm read below; guard the odd-length-tail 1-byte OOB read too.
        if (pageOffset < 0 || pageOffset + 2 > data.length) {
            return new byte[0];
        }

        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int ibHnpm = Short.toUnsignedInt(buf.getShort(pageOffset));

        int pageMapOffset = pageOffset + ibHnpm;
        if (pageMapOffset + 4 > data.length) return new byte[0];

        buf.position(pageMapOffset);
        int cAlloc = Short.toUnsignedInt(buf.getShort());
        int cFree = Short.toUnsignedInt(buf.getShort());

        if (hidIndex == 0 || hidIndex > cAlloc) {
            return new byte[0];
        }

        // cAlloc is read from the (untrusted) HNPAGEMAP; the rgibAlloc array holds cAlloc+1 offsets, so
        // require the whole array to fit before indexing into it ([MS-PST] §2.3.1.5).
        if (pageMapOffset + 4 + (cAlloc + 1) * 2 > data.length) {
            return new byte[0];
        }

        int offsetInPage = Short.toUnsignedInt(buf.getShort(pageMapOffset + 4 + (hidIndex - 1) * 2));
        int nextOffsetInPage = Short.toUnsignedInt(buf.getShort(pageMapOffset + 4 + hidIndex * 2));

        int size = nextOffsetInPage - offsetInPage;
        int absOffset = pageOffset + offsetInPage;

        if (size < 0 || absOffset < 0 || absOffset + size > data.length) {
            return new byte[0];
        }

        byte[] item = new byte[size];
        System.arraycopy(data, absOffset, item, 0, size);
        return item;
    }
}
