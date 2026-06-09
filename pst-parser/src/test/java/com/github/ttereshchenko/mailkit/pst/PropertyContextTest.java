package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
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

    /**
     * String8 (PtypString8) properties hold raw code-page bytes; {@code decodeString8} must re-decode
     * them with the message's charset. Moved here from the plugin's conversion test once
     * {@code PropertyContext} stopped being part of the library's public API.
     */
    @Test
    void decodesString8PropertiesUsingProvidedCharset() throws Exception {
        // The Windows-1252 bytes for em-dash (0x97) and right double quotation mark (0x94).
        byte[] emDashBytes = new byte[] {(byte) 0x97, (byte) 0x94};

        PropertyContext propertyContext = new PropertyContext(null, null, null);

        Field propsField = PropertyContext.class.getDeclaredField("properties");
        propsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Object> props = (Map<Integer, Object>) propsField.get(propertyContext);

        Field string8TagsField = PropertyContext.class.getDeclaredField("string8Tags");
        string8TagsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Integer> string8Tags = (Set<Integer>) string8TagsField.get(propertyContext);

        // Emulate parseLeafNode placing the String8 decoded as ISO-8859-1 initially.
        props.put(0x0037, new String(emDashBytes, StandardCharsets.ISO_8859_1));
        string8Tags.add(0x0037);

        propertyContext.decodeString8(Charset.forName("windows-1252"));

        assertEquals("—”", props.get(0x0037), "String8 bytes should be decoded using the provided charset");
    }
}
