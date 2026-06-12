package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LzFuTest {

    private static final int LZFU_SIGNATURE = 0x75465A4C;
    private static final int MELA_SIGNATURE = 0x414C454D;

    private static byte[] header(int uncompressedSize, int signature, int payloadLength) {
        var buffer = ByteBuffer.allocate(16 + payloadLength).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, 12 + payloadLength); // compressed size (informational)
        buffer.putInt(4, uncompressedSize);
        buffer.putInt(8, signature);
        buffer.putInt(12, 0); // CRC, not validated
        return buffer.array();
    }

    @Test
    void decodesLiteralRun() {
        byte[] payload = "hello".getBytes(StandardCharsets.US_ASCII);
        byte[] data = header(payload.length, LZFU_SIGNATURE, 1 + payload.length + 2);
        data[16] = 0x00; // flags: 8 literals
        System.arraycopy(payload, 0, data, 17, payload.length);

        assertEquals("hello", LzFu.decode(data));
    }

    @Test
    void decodesDictionaryReferenceIntoPreloadedHeader() {
        // One reference token: offset 0, size 10 -> the first 10 chars of the preloaded dictionary.
        byte[] data = header(10, LZFU_SIGNATURE, 3 + 2);
        data[16] = 0x01; // flags: first token is a reference
        data[17] = 0x00; // offset high bits
        data[18] = 0x08; // offset low nibble = 0, size nibble 8 -> 8 + 2 = 10 bytes

        assertEquals(LzFu.LZFU_HEADER.substring(0, 10), LzFu.decode(data));
    }

    @Test
    void decodesUncompressedMela() {
        byte[] payload = "{\\rtf1 hi}".getBytes(StandardCharsets.US_ASCII);
        byte[] data = header(payload.length, MELA_SIGNATURE, payload.length);
        System.arraycopy(payload, 0, data, 16, payload.length);

        assertEquals("{\\rtf1 hi}", LzFu.decode(data));
    }

    @Test
    void unknownSignatureYieldsEmptyString() {
        byte[] data = header(100, 0x12345678, 8);
        assertEquals("", LzFu.decode(data));
    }

    @Test
    void truncatedInputYieldsEmptyString() {
        assertEquals("", LzFu.decode(null));
        assertEquals("", LzFu.decode(new byte[7]));
    }

    @Test
    void bombHeaderDoesNotPreallocateAndReturnsAvailablePrefix() {
        // 90 MB declared from 5 literal bytes: must decode the available prefix without allocating
        // the declared size up front (the old code allocated uncompressedSize before reading a byte).
        byte[] payload = "small".getBytes(StandardCharsets.US_ASCII);
        byte[] data = header(90 * 1024 * 1024, LZFU_SIGNATURE, 1 + payload.length + 2);
        data[16] = 0x00;
        System.arraycopy(payload, 0, data, 17, payload.length);

        String decoded = LzFu.decode(data);
        assertTrue(decoded.startsWith("small"), "Expected the available literal prefix, got: " + decoded);
        assertTrue(decoded.length() < 100, "Output must stop at the actual data, not the declared size");
    }

    @Test
    void implausibleNegativeSizeIsRejected() {
        byte[] data = header(-5, LZFU_SIGNATURE, 8);
        assertEquals("", LzFu.decode(data));
    }

    /**
     * G3 regression: the decode loop used to stop two bytes before the end of the input, so a flag
     * byte at {@code data.length - 2} stranded its trailing literal and the body lost its last
     * characters. No padding bytes after the final literal here, unlike the other fixtures.
     */
    @Test
    void decodesLiteralsAtTheVeryEndOfTheStream() {
        byte[] firstRun = "12345678".getBytes(StandardCharsets.US_ASCII);
        byte[] data = header(9, LZFU_SIGNATURE, 1 + firstRun.length + 1 + 1);
        data[16] = 0x00; // flags: 8 literals
        System.arraycopy(firstRun, 0, data, 17, firstRun.length);
        data[25] = 0x00; // second flag byte, at data.length - 2
        data[26] = '9'; // final literal, at data.length - 1

        assertEquals("123456789", LzFu.decode(data));
    }
}
