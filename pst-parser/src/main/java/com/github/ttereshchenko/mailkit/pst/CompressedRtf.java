package com.github.ttereshchenko.mailkit.pst;

import java.util.Arrays;

/**
 * Public façade over the LZFu codec ([MS-OXRTFCP]) for callers outside the parser package.
 *
 * <p>The MSG→EML converter obtains {@code PR_RTF_COMPRESSED} bytes from Apache POI and needs the same
 * byte-faithful decompression the PST path uses internally via {@link Message#getRawRtfBytes()}.
 * Decoding the stream to a windows-1252 {@code String} and re-encoding it (POI's only RTF accessor)
 * loses the five byte values that code page leaves undefined, corrupting a {@code body.rtf}
 * attachment; decompressing straight to bytes preserves every octet.
 */
public final class CompressedRtf {

    private CompressedRtf() {}

    /**
     * Decompresses {@code PR_RTF_COMPRESSED} bytes to the exact decompressed RTF bytes, with leading
     * and trailing ASCII whitespace trimmed (matching {@link Message#getRawRtfBytes()}). Returns an
     * empty array when {@code compressed} is {@code null}, too short, or not a recognized LZFu/MELA
     * stream; never throws on malformed input.
     */
    public static byte[] decompressToBytes(byte[] compressed) {
        return trimAsciiWhitespace(LzFu.decodeToBytes(compressed));
    }

    /** Trims leading and trailing bytes &le; 0x20, mirroring {@link String#trim()} over a low-byte charset. */
    private static byte[] trimAsciiWhitespace(byte[] data) {
        var start = 0;
        var end = data.length;
        while (start < end && (data[start] & 0xFF) <= 0x20) {
            start++;
        }
        while (end > start && (data[end - 1] & 0xFF) <= 0x20) {
            end--;
        }
        return start == 0 && end == data.length ? data : Arrays.copyOfRange(data, start, end);
    }
}
