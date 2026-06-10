package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
        var data = new byte[40];
        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
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

    /**
     * String8 (PtypString8) properties hold raw code-page bytes; {@code decodeString8} must re-decode
     * them with the message's charset. Built from a real synthetic heap (no reflection): a PC whose
     * single record is a String8 subject containing the Windows-1252 bytes for em-dash and right
     * double quotation mark.
     */
    @Test
    void decodesString8PropertiesUsingProvidedCharset() throws Exception {
        var data = new byte[46];
        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, (short) 34); // ibHnpm -> page map at offset 34
        buf.put(2, (byte) 0xEC); // bSig
        buf.putInt(4, 0x20); // hidUserRoot -> item 1 (BTH header)

        // Item 1 [16, 24): BTH header — cbKey=2, cbEnt=6, leaf, root at item 2.
        buf.put(16, (byte) 0xB5);
        buf.put(17, (byte) 2); // cbKey
        buf.put(18, (byte) 6); // cbEnt
        buf.put(19, (byte) 0); // bIdxLevels
        buf.putInt(20, 0x40); // hidRoot -> item 2

        // Item 2 [24, 32): one record — tag 0x0037 (PR_SUBJECT), type 0x001E (String8), HNID item 3.
        buf.putShort(24, (short) 0x0037);
        buf.putShort(26, (short) 0x001E);
        buf.putInt(28, 0x60); // -> item 3

        // Item 3 [32, 34): the Windows-1252 bytes for em-dash (0x97) and right double quote (0x94).
        buf.put(32, (byte) 0x97);
        buf.put(33, (byte) 0x94);

        // Page map at 34: cAlloc=3, cFree=0, rgibAlloc = {16, 24, 32, 34}.
        buf.putShort(34, (short) 3);
        buf.putShort(36, (short) 0);
        buf.putShort(38, (short) 16);
        buf.putShort(40, (short) 24);
        buf.putShort(42, (short) 32);
        buf.putShort(44, (short) 34);

        var propertyContext = new PropertyContext(data, null, null);
        propertyContext.decodeString8(Charset.forName("windows-1252"));

        assertEquals(
                "—”",
                propertyContext.getProperty(0x0037),
                "String8 bytes should be decoded using the provided charset");
    }

    @Test
    void parsesVariableWidthMultiValueProperties() {
        // PT_MV_UNICODE blob: count=2, offsets {12, 16}, "ab" + "c" in UTF-16LE.
        var blob = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        blob.putInt(0, 2);
        blob.putInt(4, 12);
        blob.putInt(8, 16);
        blob.put(12, "ab".getBytes(StandardCharsets.UTF_16LE));
        blob.put(16, "c".getBytes(StandardCharsets.UTF_16LE));

        Object parsed = PropertyContext.parseMultiValue(0x101F, blob.array(), StandardCharsets.ISO_8859_1);
        assertEquals(List.of("ab", "c"), parsed);
    }

    @Test
    void parsesFixedWidthMultiValueProperties() {
        var blob = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        blob.putInt(0, 7);
        blob.putInt(4, 42);
        assertEquals(List.of(7, 42), PropertyContext.parseMultiValue(0x1003, blob.array(), null));
    }

    @Test
    void rejectsMalformedMultiValueOffsets() {
        // Offset table claims more entries than the blob holds — must degrade to empty, not throw.
        var blob = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        blob.putInt(0, 1000);
        assertEquals(List.of(), PropertyContext.parseMultiValue(0x1102, blob.array(), null));
    }

    @Test
    void convertsFileTimeToInstant() {
        assertEquals(Instant.EPOCH, PropertyContext.fileTimeToInstant(116_444_736_000_000_000L));
        // One second past the Unix epoch is 10^7 FILETIME ticks later.
        assertEquals(Instant.ofEpochSecond(1), PropertyContext.fileTimeToInstant(116_444_736_010_000_000L));
    }
}
