package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class HeapOnNodeTest {

    /**
     * A Heap-on-Node spans the concatenated block payloads produced by {@code readNodeData}, which
     * strips the per-block trailer — so consecutive HN blocks are {@code blockPayloadSize} apart
     * (8176 for Unicode), not 8192. This builds a 2-block buffer at the 8176 boundary and checks an
     * item in block 1 resolves correctly.
     */
    @Test
    void testMultiBlockHeapOnNodeUnicodeStride() throws Exception {
        int stride = 8176;
        byte[] data = new byte[stride * 2];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // --- Block 0 ---
        buf.putShort(0, (short) 8000); // ibHnpm
        buf.put(2, (byte) 0xEC); // bSig
        buf.putInt(4, 0x1234); // hidUserRoot
        for (int i = 0; i < 10; i++) {
            data[100 + i] = (byte) i;
        }
        buf.position(8000);
        buf.putShort((short) 1); // cAlloc
        buf.putShort((short) 0); // cFree
        buf.putShort((short) 100); // allocs[0]
        buf.putShort((short) 110); // allocs[1]

        // --- Block 1 (starts at the 8176 payload boundary) ---
        buf.putShort(stride, (short) 8000); // ibHnpm (relative to block start)
        for (int i = 0; i < 15; i++) {
            data[stride + 200 + i] = (byte) (i + 10);
        }
        buf.position(stride + 8000);
        buf.putShort((short) 1); // cAlloc
        buf.putShort((short) 0); // cFree
        buf.putShort((short) 200); // allocs[0]
        buf.putShort((short) 215); // allocs[1]

        HeapOnNode hon = new HeapOnNode(data); // single-arg default == Unicode 8176
        assertEquals(0x1234, hon.userRootHid());

        byte[] item0 = hon.getItem((0 << 16) | (1 << 5));
        assertEquals(10, item0.length);
        for (int i = 0; i < 10; i++) {
            assertEquals((byte) i, item0[i]);
        }

        byte[] item1 = hon.getItem((1 << 16) | (1 << 5));
        assertEquals(15, item1.length, "Item in block 1 must resolve at the 8176 payload boundary");
        for (int i = 0; i < 15; i++) {
            assertEquals((byte) (i + 10), item1[i]);
        }
    }

    /** ANSI nodes use a 12-byte trailer, so the block payload (and HN stride) is 8180. */
    @Test
    void testMultiBlockHeapOnNodeAnsiStride() throws Exception {
        int stride = 8180;
        byte[] data = new byte[stride * 2];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        buf.putShort(0, (short) 8000);
        buf.put(2, (byte) 0xEC);
        buf.putInt(4, 0x1);
        buf.position(8000);
        buf.putShort((short) 1);
        buf.putShort((short) 0);
        buf.putShort((short) 100);
        buf.putShort((short) 110);

        buf.putShort(stride, (short) 8000);
        for (int i = 0; i < 7; i++) {
            data[stride + 300 + i] = (byte) (i + 1);
        }
        buf.position(stride + 8000);
        buf.putShort((short) 1);
        buf.putShort((short) 0);
        buf.putShort((short) 300);
        buf.putShort((short) 307);

        HeapOnNode hon = new HeapOnNode(data, stride);
        byte[] item1 = hon.getItem((1 << 16) | (1 << 5));
        assertEquals(7, item1.length, "Item in block 1 must resolve at the 8180 ANSI payload boundary");
        for (int i = 0; i < 7; i++) {
            assertEquals((byte) (i + 1), item1[i]);
        }
    }

    /** A crafted cAlloc that claims more allocations than the page holds must not read past data (M2). */
    @Test
    void getItemReturnsEmptyWhenAllocationArrayOverflowsData() throws Exception {
        byte[] data = new byte[64];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, (short) 8); // ibHnpm -> page map at offset 8
        buf.put(2, (byte) 0xEC); // bSig
        buf.putInt(4, 0x20); // hidUserRoot (unused here)
        buf.position(8);
        buf.putShort((short) 1000); // cAlloc: 1000 allocations cannot fit in 64 bytes
        buf.putShort((short) 0); // cFree

        HeapOnNode hon = new HeapOnNode(data, 8176);
        byte[] item = hon.getItem((0 << 16) | (1 << 5)); // hidIndex 1
        assertEquals(0, item.length, "Out-of-range cAlloc must yield an empty item, not an exception");
    }

    /** pageOffset == data.length - 1 would make the 2-byte ibHnpm read run one byte past the array (M2). */
    @Test
    void getItemHandlesOddLengthTailWithoutOutOfBounds() throws Exception {
        byte[] data = new byte[8177]; // one block payload (8176) + 1 trailing byte
        data[2] = (byte) 0xEC; // bSig required by the constructor
        HeapOnNode hon = new HeapOnNode(data, 8176);
        // hidBlockIndex 1 -> pageOffset 8176 == data.length - 1
        byte[] item = hon.getItem((1 << 16) | (1 << 5));
        assertEquals(0, item.length, "Odd-length tail must not cause an out-of-bounds read");
    }
}
