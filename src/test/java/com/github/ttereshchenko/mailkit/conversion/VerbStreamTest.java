package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FIX DSC-2 coverage for {@link VerbStream}: a sent poll's PidLidVerbStream ([MS-OXOMSG] §2.2.1.74)
 * must yield its Unicode voting-button labels, and any structural anomaly in the untrusted blob must
 * produce an empty list without throwing (truncation is exercised at every byte boundary).
 */
class VerbStreamTest {

    @Test
    void parsesUnicodeVoteOptionsInStoredOrder() {
        assertEquals(List.of("Approve", "Reject"), VerbStream.parseVoteOptions(build("Approve", "Reject")));
    }

    @Test
    void parsesThreeOptions() {
        assertEquals(List.of("Yes", "No", "Maybe"), VerbStream.parseVoteOptions(build("Yes", "No", "Maybe")));
    }

    @Test
    void preservesNonAsciiOptionLabels() {
        // The Unicode DisplayName must survive verbatim so the caller can RFC-2047-encode it downstream.
        assertEquals(List.of("Approuvé", "承認"), VerbStream.parseVoteOptions(build("Approuvé", "承認")));
    }

    @Test
    void blankOptionLabelsAreFilteredOut() {
        // A VoteOptionExtras record with an empty (or whitespace-only) DisplayName carries no usable
        // label and must be dropped, while its neighbours survive.
        assertEquals(List.of("Approve"), VerbStream.parseVoteOptions(build("Approve", "")));
    }

    @Test
    void nullOrTooShortBlobYieldsNoOptions() {
        assertTrue(VerbStream.parseVoteOptions(null).isEmpty());
        assertTrue(VerbStream.parseVoteOptions(new byte[0]).isEmpty());
        assertTrue(VerbStream.parseVoteOptions(new byte[] {0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x02})
                .isEmpty());
    }

    @Test
    void zeroCountStreamYieldsNoOptionsWithoutError() {
        // Version + Count(0) + Version2 is structurally valid but carries no buttons.
        assertEquals(List.of(), VerbStream.parseVoteOptions(build()));
    }

    @Test
    void implausibleRecordCountIsRejected() {
        var out = new ByteArrayOutputStream();
        writeLe16(out, 0x0102);
        writeLe32(out, 1000); // far above the defensive cap
        writeLe16(out, 0x0102);
        assertTrue(VerbStream.parseVoteOptions(out.toByteArray()).isEmpty());
    }

    @Test
    void negativeRecordCountIsRejected() {
        var out = new ByteArrayOutputStream();
        writeLe16(out, 0x0102);
        writeLe32(out, -1);
        writeLe16(out, 0x0102);
        assertTrue(VerbStream.parseVoteOptions(out.toByteArray()).isEmpty());
    }

    @Test
    void versionMismatchBetweenVersionAndVersion2IsRejected() {
        var valid = build("Approve", "Reject");
        // Corrupt Version2 (the two little-endian bytes immediately after the last VerbData record).
        var corrupted = valid.clone();
        var version2Offset = version2Offset("Approve", "Reject");
        corrupted[version2Offset] = 0x7F;
        corrupted[version2Offset + 1] = 0x7F;
        assertTrue(VerbStream.parseVoteOptions(corrupted).isEmpty());
    }

    @Test
    void truncationAtEveryByteBoundaryYieldsNoOptionsAndNeverThrows() {
        var full = build("Approve", "Reject");
        // Only the complete stream may produce options; every shorter prefix (which covers every field
        // boundary) must return an empty list without throwing.
        for (var length = 0; length < full.length; length++) {
            var prefix = new byte[length];
            System.arraycopy(full, 0, prefix, 0, length);
            var index = length;
            assertDoesNotThrow(() -> VerbStream.parseVoteOptions(prefix), () -> "threw at prefix length " + index);
            assertTrue(
                    VerbStream.parseVoteOptions(prefix).isEmpty(),
                    () -> "prefix length " + index + " must yield no options");
        }
        assertEquals(List.of("Approve", "Reject"), VerbStream.parseVoteOptions(full));
    }

    @Test
    void oversizedUnicodeNameLengthThatOverrunsIsRejected() {
        // A single VoteOptionExtras whose DisplayNameCount claims more characters than remain must be
        // rejected rather than read out of bounds.
        var out = new ByteArrayOutputStream();
        writeLe16(out, 0x0102);
        writeLe32(out, 1);
        writeVerbData(out, "X");
        writeLe16(out, 0x0102);
        out.write(200); // DisplayNameCount = 200 chars => 400 bytes, but none follow
        assertTrue(VerbStream.parseVoteOptions(out.toByteArray()).isEmpty());
    }

    // -----------------------------------------------------------------------
    // A minimal, spec-shaped VerbStream builder ([MS-OXOMSG] §2.2.1.74).
    // -----------------------------------------------------------------------

    private static byte[] build(String... options) {
        var out = new ByteArrayOutputStream();
        writeLe16(out, 0x0102); // Version
        writeLe32(out, options.length); // Count
        for (var option : options) {
            writeVerbData(out, option);
        }
        writeLe16(out, 0x0102); // Version2
        for (var option : options) {
            writeVoteOptionExtra(out, option);
        }
        return out.toByteArray();
    }

    /** The byte offset of Version2 in the stream {@link #build} produces for the given options. */
    private static int version2Offset(String... options) {
        var out = new ByteArrayOutputStream();
        writeLe16(out, 0x0102);
        writeLe32(out, options.length);
        for (var option : options) {
            writeVerbData(out, option);
        }
        return out.size();
    }

    private static void writeVerbData(ByteArrayOutputStream out, String option) {
        writeLe32(out, 4); // VerbType
        writeAnsiCounted(out, option); // DisplayName
        out.write(0); // MsgClsNameCount
        out.write(0); // Internal1Count
        writeAnsiCounted(out, option); // DisplayNameRepeat
        out.writeBytes(new byte[29]); // Internal2..Internal6 fixed tail
    }

    private static void writeVoteOptionExtra(ByteArrayOutputStream out, String option) {
        writeUnicodeCounted(out, option); // DisplayName
        writeUnicodeCounted(out, option); // DisplayNameRepeat
    }

    private static void writeAnsiCounted(ByteArrayOutputStream out, String value) {
        var bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeUnicodeCounted(ByteArrayOutputStream out, String value) {
        out.write(value.length());
        out.writeBytes(value.getBytes(StandardCharsets.UTF_16LE));
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
