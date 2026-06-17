package com.github.ttereshchenko.mailkit.pst;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Decompresses LZFu-compressed RTF bodies (PR_RTF_COMPRESSED, [MS-OXRTFCP]).
 */
final class LzFu {

    private static final System.Logger LOG = System.getLogger(LzFu.class.getName());

    /** Sanity cap on the declared uncompressed size; output grows incrementally up to this. */
    private static final int MAX_UNCOMPRESSED_SIZE = 100 * 1024 * 1024;

    // Charset for the String {@link #decode} view only. windows-1252 matches how RTF readers map the
    // low bytes; it is NOT byte-lossless (five byte values are undefined), so callers needing exact
    // bytes use {@link #decodeToBytes} instead.
    private static final Charset RTF_CHARSET = Charset.forName("windows-1252");

    static final String LZFU_HEADER =
            "{\\rtf1\\ansi\\mac\\deff0\\deftab720{\\fonttbl;}{\\f0\\fnil \\froman \\fswiss \\fmodern \\fscript \\fdecor MS Sans SerifSymbolArialTimes New RomanCourier{\\colortbl\\red0\\green0\\blue0\r\n\\par \\pard\\plain\\f0\\fs20\\b\\i\\u\\tab\\tx";

    private LzFu() {
        // Utility class
    }

    /**
     * Decodes the RTF as a windows-1252 String, trimmed. Suitable for control-word parsing
     * (RtfStripper interprets {@code \'hh} escapes via {@code \ansicpg}); use {@link #decodeToBytes}
     * when the exact bytes must be preserved — windows-1252 leaves five byte values undefined, so the
     * String round-trip is not byte-lossless.
     */
    static String decode(byte[] data) {
        return new String(decodeToBytes(data), RTF_CHARSET).trim();
    }

    /**
     * Decodes the RTF to its exact decompressed bytes (no charset round-trip, untrimmed), or an empty
     * array. This is the byte-faithful path: it preserves every octet of the RTF stream so a body.rtf
     * attachment carries the original bytes rather than the lossy windows-1252 String re-encoding.
     */
    static byte[] decodeToBytes(byte[] data) {
        if (data == null || data.length < 16) {
            return new byte[0];
        }

        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.getInt(); // compressed size (unused; the input array bounds the read loop)
        int uncompressedSize = buffer.getInt();
        int compressionSignature = buffer.getInt();
        int storedCrc = buffer.getInt();

        if (compressionSignature == 0x75465A4C) { // LZFu
            // The header CRC covers the compressed contents ([MS-OXRTFCP] §2.1.3.1.4, same algorithm
            // as the PST trailers). A mismatch means the body bytes were corrupted; warn — loudly, so
            // garbled output is diagnosable — but still decode best-effort rather than drop the body.
            int computedCrc = PstCrc.compute(data, 16, data.length - 16);
            if (storedCrc != computedCrc) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () -> "RTF body CRC mismatch (stored 0x" + Integer.toHexString(storedCrc) + ", computed 0x"
                                + Integer.toHexString(computedCrc) + "); the decoded body may be corrupted");
            }
            if (uncompressedSize < 0 || uncompressedSize > MAX_UNCOMPRESSED_SIZE) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        () -> "Rejecting LZFu body with implausible uncompressed size " + uncompressedSize);
                return new byte[0];
            }
            // Grow the output incrementally instead of trusting the 4 untrusted header bytes with an
            // up-front allocation; the loop stops at uncompressedSize regardless.
            var output = new ByteArrayOutputStream(Math.min(uncompressedSize, 64 * 1024));
            var lzBuffer = new byte[4096];

            byte[] headerBytes = LZFU_HEADER.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(headerBytes, 0, lzBuffer, 0, headerBytes.length);

            int bufferPosition = headerBytes.length;
            int currentDataPosition = 16;
            // Bytes written into lzBuffer since the header. Until this reaches the fill threshold the
            // 4096-slot dictionary ring is only partially populated — slots at or past bufferPosition are
            // uninitialized — so a forward reference into them is corruption (see the guard below). Once it
            // reaches the threshold every slot holds produced data and any reference is a valid back-ref.
            int ringWrites = 0;
            int ringFillThreshold = 4096 - headerBytes.length;

            // Run to the end of the input: stopping 2 bytes early (an old guard) dropped up to two
            // trailing literals when the final flag byte sat near the tail. The token reads below
            // carry their own truncation guards.
            decode:
            while (currentDataPosition < data.length && output.size() < uncompressedSize) {
                int flags = data[currentDataPosition++] & 0xFF;
                for (int x = 0; x < 8 && output.size() < uncompressedSize; x++) {
                    boolean isReference = ((flags & 1) == 1);
                    flags >>= 1;
                    if (isReference) {
                        if (currentDataPosition + 1 >= data.length) {
                            break decode; // truncated reference token
                        }
                        int referenceHigh = data[currentDataPosition++] & 0xFF;
                        int referenceLow = data[currentDataPosition++] & 0xFF;
                        int referenceOffset = (referenceHigh << 4) | (referenceLow >>> 4);
                        int referenceSize = (referenceLow & 0xF) + 2;

                        if (referenceOffset == bufferPosition) {
                            // [MS-OXRTFCP] §2.1.3.1.2: a dictionary reference whose offset equals the
                            // current write cursor is the end-of-stream marker — stop here rather than
                            // copy spurious dictionary bytes until uncompressedSize is reached.
                            break decode;
                        }

                        if (ringWrites < ringFillThreshold && referenceOffset > bufferPosition) {
                            // Before the dictionary ring first fills, only [0, bufferPosition) holds bytes
                            // the stream has actually produced (the header preamble plus the literals
                            // written so far). A reference past the write cursor — a forward reference —
                            // would copy uninitialized dictionary slots as if they were data. A conformant
                            // [MS-OXRTFCP] compressor never emits one, so treat it as a corrupt stream and
                            // stop, matching the truncated-token handling.
                            LOG.log(
                                    System.Logger.Level.WARNING,
                                    () -> "LZFu forward dictionary reference (offset past the write cursor) before "
                                            + "the ring filled; the RTF body is corrupt and decoding stopped early");
                            break decode;
                        }

                        int index = referenceOffset;
                        for (int y = 0; y < referenceSize && output.size() < uncompressedSize; y++) {
                            output.write(lzBuffer[index]);
                            lzBuffer[bufferPosition] = lzBuffer[index];
                            bufferPosition = (bufferPosition + 1) % 4096;
                            index = (index + 1) % 4096;
                            ringWrites++;
                        }
                    } else {
                        if (currentDataPosition >= data.length) {
                            break; // truncated literal token
                        }
                        lzBuffer[bufferPosition] = data[currentDataPosition];
                        bufferPosition = (bufferPosition + 1) % 4096;
                        ringWrites++;
                        output.write(data[currentDataPosition++]);
                    }
                }
            }
            if (output.size() != uncompressedSize) {
                int decompressed = output.size();
                // WARNING, not DEBUG: a short decompression means body text was lost, which the
                // user should be able to see in the log without enabling debug output.
                LOG.log(
                        System.Logger.Level.WARNING,
                        () -> "LZFu body truncated: decompressed " + decompressed + " of " + uncompressedSize
                                + " declared bytes");
            }
            return output.toByteArray();

        } else if (compressionSignature == 0x414C454D) { // MELA (uncompressed)
            return Arrays.copyOfRange(data, 16, data.length);
        }

        LOG.log(
                System.Logger.Level.WARNING,
                () -> "Unknown RTF compression signature 0x" + Integer.toHexString(compressionSignature));
        return new byte[0];
    }
}
