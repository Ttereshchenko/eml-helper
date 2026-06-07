package com.github.ttereshchenko.mailkit.conversion.pst;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class PropertyContextTest {

    /**
     * A BTH header with {@code cbKey + cbEnt == 0} previously divided by zero in
     * {@code parseLeafNode} ({@code leafData.length / (cbKey + cbEnt)}). The shape guard must reject it
     * (a PC's key is always 2 bytes) instead of throwing ArithmeticException (M4).
     */
    @Test
    void zeroWidthBthRecordDoesNotDivideByZero() {
        // Lay out a single-block Heap-on-Node whose user-root item is an 8-byte BTH header with
        // cbKey = cbEnt = 0 and bIdxLevels = 0 (a leaf).
        byte[] data = new byte[40];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, (short) 32); // ibHnpm -> page map at offset 32
        buf.put(2, (byte) 0xEC); // bSig
        buf.putInt(4, 0x20); // hidUserRoot -> hidIndex 1, block 0

        // BTH header item at [16, 24): bType=0xB5, cbKey=0, cbEnt=0, bIdxLevels=0, hidRoot=0.
        buf.put(16, (byte) 0xB5);
        buf.put(17, (byte) 0); // cbKey
        buf.put(18, (byte) 0); // cbEnt
        buf.put(19, (byte) 0); // bIdxLevels (leaf)
        buf.putInt(20, 0); // hidRoot

        // Page map at offset 32: cAlloc=1, cFree=0, rgibAlloc = {16, 24}.
        buf.putShort(32, (short) 1); // cAlloc
        buf.putShort(34, (short) 0); // cFree
        buf.putShort(36, (short) 16); // rgibAlloc[0]
        buf.putShort(38, (short) 24); // rgibAlloc[1]

        PropertyContext context = assertDoesNotThrow(() -> new PropertyContext(data, null, null));
        assertNull(context.getProperty(0x0037), "No properties should be parsed from a rejected BTH");
    }
}
