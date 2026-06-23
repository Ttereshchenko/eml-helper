package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /**
     * The HTML body (String8-typed PR_HTML / legacy PidTagBodyHtml) is governed by PR_INTERNET_CPID,
     * not the message-store code page that the other String8 properties follow ([MS-OXCMAIL]
     * §2.1.3.5.2). The per-tag {@code overrides} map must decode PR_HTML with the internet charset
     * while every other String8 property keeps the default store charset; the same raw bytes
     * (0xC3 0xA9) are "é" as UTF-8 but "Ã©" as windows-1252, so a single store-charset decode of both
     * would mojibake the body.
     */
    @Test
    void decodeString8OverrideUsesInternetCharsetForHtmlBodyOnly() throws Exception {
        var data = new byte[58];
        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, (short) 44); // ibHnpm -> page map at offset 44
        buf.put(2, (byte) 0xEC); // bSig
        buf.putInt(4, 0x20); // hidUserRoot -> item 1 (BTH header)

        // Item 1 [16, 24): BTH header — cbKey=2, cbEnt=6, leaf, root at item 2.
        buf.put(16, (byte) 0xB5);
        buf.put(17, (byte) 2);
        buf.put(18, (byte) 6);
        buf.put(19, (byte) 0);
        buf.putInt(20, 0x40); // hidRoot -> item 2

        // Item 2 [24, 40): two records.
        // Record A: tag 0x1013 (PR_HTML), type 0x001E (String8), HNID -> item 3.
        buf.putShort(24, (short) 0x1013);
        buf.putShort(26, (short) 0x001E);
        buf.putInt(28, 0x60);
        // Record B: tag 0x0037 (PR_SUBJECT), type 0x001E (String8), HNID -> item 4.
        buf.putShort(32, (short) 0x0037);
        buf.putShort(34, (short) 0x001E);
        buf.putInt(36, 0x80);

        // Item 3 [40, 42): PR_HTML bytes; Item 4 [42, 44): PR_SUBJECT bytes — both 0xC3 0xA9.
        buf.put(40, (byte) 0xC3);
        buf.put(41, (byte) 0xA9);
        buf.put(42, (byte) 0xC3);
        buf.put(43, (byte) 0xA9);

        // Page map at 44: cAlloc=4, cFree=0, rgibAlloc = {16, 24, 40, 42, 44}.
        buf.putShort(44, (short) 4);
        buf.putShort(46, (short) 0);
        buf.putShort(48, (short) 16);
        buf.putShort(50, (short) 24);
        buf.putShort(52, (short) 40);
        buf.putShort(54, (short) 42);
        buf.putShort(56, (short) 44);

        var propertyContext = new PropertyContext(data, null, null);
        propertyContext.decodeString8(
                Charset.forName("windows-1252"), Map.of(MapiProperties.PR_HTML, StandardCharsets.UTF_8));

        assertEquals(
                "é",
                propertyContext.getProperty(MapiProperties.PR_HTML),
                "PR_HTML String8 must decode with the override (internet) charset");
        assertEquals(
                "Ã©",
                propertyContext.getProperty(0x0037),
                "other String8 properties must keep the default store charset");
    }

    /**
     * [MS-PST] §2.3.3.4.2 (MV Properties with Variable-size Base Type): the record is {@code ulCount}
     * followed by exactly {@code ulCount} {@code rgulDataOffsets} (the start of each item, relative to
     * the record start). There is NO terminating {@code rgulDataOffsets[ulCount]} entry — the length of
     * the last item is the total size of the MV property data record minus its start offset, and
     * {@code rgDataItems} is byte-aligned (no trailing/inter-item padding). So the final element MUST run
     * to {@code data.length}; clipping it to a supposed {@code rgulDataOffsets[count]} or to skip
     * "padding" would corrupt the last value.
     */
    @Test
    void parsesVariableWidthMultiValueProperties() {
        // PT_MV_UNICODE blob: count=2, offsets {12, 16}, "ab" + "c" in UTF-16LE; record size 18.
        var unicodeBlob = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        unicodeBlob.putInt(0, 2);
        unicodeBlob.putInt(4, 12);
        unicodeBlob.putInt(8, 16);
        unicodeBlob.put(12, "ab".getBytes(StandardCharsets.UTF_16LE));
        unicodeBlob.put(16, "c".getBytes(StandardCharsets.UTF_16LE));

        Object parsedUnicode =
                PropertyContext.parseMultiValue(0x101F, unicodeBlob.array(), StandardCharsets.ISO_8859_1);
        assertEquals(List.of("ab", "c"), parsedUnicode);

        // PT_MV_BINARY blob whose LAST element ends at the record boundary with no terminating offset
        // entry: count=2, offsets {12, 15}, item0 = {0x01,0x02,0x03} [12,15), item1 = {0xFF,0x00,0x00}
        // [15,18). The 0x00 bytes are real payload (the byte-aligned spec layout has no padding), so
        // the last value must be the full 3 bytes — the regression would drop the trailing 0x00s.
        var binaryBlob = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        binaryBlob.putInt(0, 2);
        binaryBlob.putInt(4, 12);
        binaryBlob.putInt(8, 15);
        binaryBlob.put(12, new byte[] {0x01, 0x02, 0x03});
        binaryBlob.put(15, new byte[] {(byte) 0xFF, 0x00, 0x00});

        @SuppressWarnings("unchecked")
        var parsedBinary = (List<byte[]>) PropertyContext.parseMultiValue(0x1102, binaryBlob.array(), null);
        assertEquals(2, parsedBinary.size());
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03}, parsedBinary.get(0));
        assertArrayEquals(new byte[] {(byte) 0xFF, 0x00, 0x00}, parsedBinary.get(1));
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

    // F13: only trailing NUL terminators are stripped from string properties; real whitespace is
    // message content (PR_BODY's trailing newline) and must survive, unlike the old trim().
    @Test
    void stripsOnlyTrailingNulsNotWhitespace() {
        assertEquals("abc", PropertyContext.stripTrailingNuls("abc\0\0"));
        assertEquals("abc", PropertyContext.stripTrailingNuls("abc"));
        assertEquals("  body text \r\n", PropertyContext.stripTrailingNuls("  body text \r\n"));
        assertEquals("", PropertyContext.stripTrailingNuls("\0"));
        assertEquals("a\0b", PropertyContext.stripTrailingNuls("a\0b"));
    }

    /**
     * F13 regression: the old parse-time {@code trim()} ran before {@code decodeString8} and ate the
     * leading ESC (0x1B ≤ 0x20) of an ISO-2022-JP String8 value, destroying the escape sequence the
     * re-decode depends on. Built like {@link #decodesString8PropertiesUsingProvidedCharset} but
     * with the ISO-2022-JP bytes for あ ({@code ESC $ B 0x24 0x22 ESC ( B}).
     */
    @Test
    void iso2022LeadingEscapeSurvivesString8Redecode() throws Exception {
        byte[] jis = "あ".getBytes(Charset.forName("ISO-2022-JP"));

        var data = new byte[44 + jis.length + 12];
        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int pageMap = 32 + jis.length;
        buf.putShort(0, (short) pageMap); // ibHnpm
        buf.put(2, (byte) 0xEC); // bSig
        buf.putInt(4, 0x20); // hidUserRoot -> item 1 (BTH header)

        // Item 1 [16, 24): BTH header — cbKey=2, cbEnt=6, leaf, root at item 2.
        buf.put(16, (byte) 0xB5);
        buf.put(17, (byte) 2);
        buf.put(18, (byte) 6);
        buf.put(19, (byte) 0);
        buf.putInt(20, 0x40); // hidRoot -> item 2

        // Item 2 [24, 32): one record — tag 0x0037 (PR_SUBJECT), type 0x001E (String8), HNID item 3.
        buf.putShort(24, (short) 0x0037);
        buf.putShort(26, (short) 0x001E);
        buf.putInt(28, 0x60); // -> item 3

        // Item 3 [32, 32 + jis.length): the raw ISO-2022-JP bytes, leading ESC included.
        for (int i = 0; i < jis.length; i++) {
            buf.put(32 + i, jis[i]);
        }

        // Page map: cAlloc=3, cFree=0, rgibAlloc = {16, 24, 32, 32 + jis.length}.
        buf.putShort(pageMap, (short) 3);
        buf.putShort(pageMap + 2, (short) 0);
        buf.putShort(pageMap + 4, (short) 16);
        buf.putShort(pageMap + 6, (short) 24);
        buf.putShort(pageMap + 8, (short) 32);
        buf.putShort(pageMap + 10, (short) (32 + jis.length));

        var propertyContext = new PropertyContext(data, null, null);
        propertyContext.decodeString8(Charset.forName("ISO-2022-JP"));

        assertEquals("あ", propertyContext.getProperty(0x0037), "the escape sequence must survive re-decoding");
    }

    /**
     * A variable-length property whose HNID is a subnode NID (low 5 bits set) but whose host node carries
     * no subnode tree must be dropped, never reinterpreted as a Heap ID. An NID is structurally never an
     * HID ([MS-PST] §2.3.3.2); the old code fell back to {@code heap.getItem(value)}, which shifts off the
     * NID's type bits and surfaces an unrelated heap item as the property value. Here HNID {@code 0x41} has
     * hidType 1 (NID-shaped) and hidIndex 2, so the regression would return the bytes of heap item 2 (the
     * record area itself) instead of nothing.
     */
    @Test
    void subnodeNidWithoutSubnodeTreeDropsBinaryPropertyInsteadOfReadingHeap() throws Exception {
        var data = new byte[42];
        var buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(0, (short) 32); // ibHnpm -> page map at offset 32
        buf.put(2, (byte) 0xEC); // bSig
        buf.putInt(4, 0x20); // hidUserRoot -> item 1 (BTH header)

        // Item 1 [16, 24): BTH header — cbKey=2, cbEnt=6, leaf, root at item 2.
        buf.put(16, (byte) 0xB5);
        buf.put(17, (byte) 2);
        buf.put(18, (byte) 6);
        buf.put(19, (byte) 0);
        buf.putInt(20, 0x40); // hidRoot -> item 2

        // Item 2 [24, 32): one record — tag 0x3701 (PR_ATTACH_DATA_BIN), type 0x0102 (PT_BINARY),
        // HNID 0x41 (hidType 1 -> a subnode NID; hidIndex 2 -> heap item 2 if wrongly read as an HID).
        buf.putShort(24, (short) 0x3701);
        buf.putShort(26, (short) 0x0102);
        buf.putInt(28, 0x41);

        // Page map at 32: cAlloc=2, cFree=0, rgibAlloc = {16, 24, 32}.
        buf.putShort(32, (short) 2);
        buf.putShort(34, (short) 0);
        buf.putShort(36, (short) 16);
        buf.putShort(38, (short) 24);
        buf.putShort(40, (short) 32);

        // No NodeDatabase/node -> no subnode tree exists. The NID must not be reinterpreted as a heap id.
        var propertyContext = new PropertyContext(data, null, null);
        assertNull(
                propertyContext.getProperty(0x3701),
                "a subnode-NID binary property with no subnode tree must be dropped, not read from the heap");
    }
}
