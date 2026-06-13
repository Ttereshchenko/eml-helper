package com.github.ttereshchenko.mailkit.conversion.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DistributionListMembers#parse(byte[][])} — the One-Off EntryID decoder
 * ([MS-OXCDATA] §2.2.5.1). All byte arrays are hand-built to stay independent of any Outlook binary.
 */
class DistributionListMembersTest {

    /**
     * The One-Off EntryID provider UID ([MS-OXCDATA] §2.2.5.1): a fixed 16-byte MAPIUID byte
     * sequence {@code 81 2B 1F A4 BE A3 10 19 9D 6E 00 DD 01 0F 54 02}, written verbatim by real
     * Outlook (NOT endianness-swapped). This is the exact sequence found in the vendored
     * {@code msgreader_distribution_list.msg} (and the Aspose/Outlook samples), so these hand-built
     * blobs stay byte-compatible with real member entries.
     */
    private static final byte[] ONE_OFF_MUID = {
        (byte) 0x81,
        (byte) 0x2B,
        (byte) 0x1F,
        (byte) 0xA4,
        (byte) 0xBE,
        (byte) 0xA3,
        (byte) 0x10,
        (byte) 0x19,
        (byte) 0x9D,
        (byte) 0x6E,
        (byte) 0x00,
        (byte) 0xDD,
        (byte) 0x01,
        (byte) 0x0F,
        (byte) 0x54,
        (byte) 0x02
    };

    // -----------------------------------------------------------------------
    // Happy paths
    // -----------------------------------------------------------------------

    @Test
    void unicodeEntryDecodesDisplayNameAndEmail() {
        var blob = buildOneOff("Alice", "SMTP", "alice@example.com", true);

        var members = DistributionListMembers.parse(new byte[][] {blob});

        assertEquals(1, members.size());
        assertEquals("Alice", members.get(0).name());
        assertEquals("alice@example.com", members.get(0).email());
    }

    @Test
    void ansiEntryDecodesDisplayNameAndEmail() {
        var blob = buildOneOff("Bob", "SMTP", "bob@example.com", false);

        var members = DistributionListMembers.parse(new byte[][] {blob});

        assertEquals(1, members.size());
        assertEquals("Bob", members.get(0).name());
        assertEquals("bob@example.com", members.get(0).email());
    }

    @Test
    void multipleEntriesAreReturnedInEncounterOrder() {
        var blob1 = buildOneOff("Alice", "SMTP", "alice@example.com", true);
        var blob2 = buildOneOff("Bob", "SMTP", "bob@example.com", true);
        var blob3 = buildOneOff("Carol", "SMTP", "carol@example.com", false);

        var members = DistributionListMembers.parse(new byte[][] {blob1, blob2, blob3});

        assertEquals(3, members.size());
        assertEquals("Alice", members.get(0).name());
        assertEquals("Bob", members.get(1).name());
        assertEquals("Carol", members.get(2).name());
    }

    @Test
    void unicodeEntryWithNonAsciiDisplayNameRoundTrips() {
        // Non-ASCII display name must survive UTF-16LE encoding / decoding.
        var blob = buildOneOff("Ångström", "SMTP", "angstrom@example.com", true);

        var members = DistributionListMembers.parse(new byte[][] {blob});

        assertEquals(1, members.size());
        assertEquals("Ångström", members.get(0).name());
    }

    @Test
    void entryWithEmailOnlyAndBlankNameIsIncluded() {
        // A member whose display name is empty string is valid as long as the email is present.
        var blob = buildOneOff("", "SMTP", "noreply@example.com", true);

        var members = DistributionListMembers.parse(new byte[][] {blob});

        assertEquals(1, members.size());
        assertEquals("noreply@example.com", members.get(0).email());
    }

    // -----------------------------------------------------------------------
    // Non-one-off (foreign provider UID) — must be skipped
    // -----------------------------------------------------------------------

    @Test
    void nonOneOffProviderUidIsSkipped() {
        // Replace the one-off MUID with a different GUID (e.g. an EX Address-Book EntryID).
        var foreignMuid = new byte[16];
        Arrays.fill(foreignMuid, (byte) 0x42);
        var blob = buildWithMuid(foreignMuid, "Alice", "SMTP", "alice@example.com", true);

        var members = DistributionListMembers.parse(new byte[][] {blob});

        assertTrue(members.isEmpty(), "a non-one-off EntryID must be skipped: " + members);
    }

    @Test
    void mixedListWithOnlyForeignProviderUidsYieldsEmpty() {
        var foreignMuid = new byte[16];
        Arrays.fill(foreignMuid, (byte) 0x01);
        var blob1 = buildWithMuid(foreignMuid, "X", "SMTP", "x@example.com", true);
        var blob2 = buildWithMuid(foreignMuid, "Y", "SMTP", "y@example.com", false);

        var members = DistributionListMembers.parse(new byte[][] {blob1, blob2});

        assertTrue(members.isEmpty());
    }

    @Test
    void mixedListSkipsForeignAndKeepsOneOff() {
        var foreignMuid = new byte[16];
        Arrays.fill(foreignMuid, (byte) 0x55);
        var foreign = buildWithMuid(foreignMuid, "Skip", "SMTP", "skip@example.com", true);
        var oneOff = buildOneOff("Keep", "SMTP", "keep@example.com", true);

        var members = DistributionListMembers.parse(new byte[][] {foreign, oneOff});

        assertEquals(1, members.size());
        assertEquals("Keep", members.get(0).name());
    }

    // -----------------------------------------------------------------------
    // Malformed / truncated input — must not throw, must skip
    // -----------------------------------------------------------------------

    @Test
    void nullInputReturnsEmpty() {
        var members = DistributionListMembers.parse(null);

        assertNotNull(members);
        assertTrue(members.isEmpty());
    }

    @Test
    void emptyArrayInputReturnsEmpty() {
        var members = DistributionListMembers.parse(new byte[0][]);

        assertTrue(members.isEmpty());
    }

    @Test
    void nullElementInArrayIsSkipped() {
        var valid = buildOneOff("Alice", "SMTP", "alice@example.com", true);

        var members = DistributionListMembers.parse(new byte[][] {null, valid, null});

        assertEquals(1, members.size());
        assertEquals("Alice", members.get(0).name());
    }

    @Test
    void emptyBlobIsSkipped() {
        var valid = buildOneOff("Bob", "SMTP", "bob@example.com", true);

        var members = DistributionListMembers.parse(new byte[][] {new byte[0], valid});

        assertEquals(1, members.size());
        assertEquals("Bob", members.get(0).name());
    }

    @Test
    void tooShortBlobIsSkipped() {
        // Fewer than STRINGS_OFFSET (24) bytes — too short to contain the MUID + header.
        var tooShort = new byte[10];

        var members = DistributionListMembers.parse(new byte[][] {tooShort});

        assertTrue(members.isEmpty(), "a blob shorter than 24 bytes must be skipped");
    }

    @Test
    void blobWithCorrectMuidButTruncatedStringsIsSkipped() {
        // Build a blob that has the correct MUID and header but cuts off before the first
        // null terminator, so string parsing cannot complete.
        var truncated = buildTruncatedAfterHeader(true);

        var members = DistributionListMembers.parse(new byte[][] {truncated});

        assertTrue(members.isEmpty(), "a blob with no null terminator must be skipped, not throw");
    }

    @Test
    void blobWithOnlyNullDisplayNameAndNullEmailIsSkipped() {
        // Both displayName and email are empty — the resulting member is useless.
        var blob = buildOneOff("", "SMTP", "", true);

        var members = DistributionListMembers.parse(new byte[][] {blob});

        assertTrue(members.isEmpty(), "an entry with blank name and email must be skipped");
    }

    @Test
    void allNullsInArrayDoNotThrow() {
        var members = DistributionListMembers.parse(new byte[][] {null, null, null});

        assertNotNull(members);
        assertTrue(members.isEmpty());
    }

    @Test
    void parseResultIsUnmodifiableOrAtLeastDoesNotThrowOnRead() {
        var blob = buildOneOff("Alice", "SMTP", "alice@example.com", true);
        var members = DistributionListMembers.parse(new byte[][] {blob});

        // Reading must never throw.
        assertFalse(members.isEmpty());
        var member = members.get(0);
        assertNotNull(member.name());
        assertNotNull(member.email());
    }

    // -----------------------------------------------------------------------
    // Helpers — build One-Off EntryID byte arrays
    // -----------------------------------------------------------------------

    /**
     * Builds a well-formed One-Off EntryID with the production one-off MUID, the given strings, and
     * either Unicode (UTF-16LE) or ANSI (Windows-1252) encoding.
     *
     * <p>Layout ([MS-OXCDATA] §2.2.5.1):
     * <pre>
     *   4 bytes  abFlags (all zero)
     *  16 bytes  provider UID (one-off MUID)
     *   2 bytes  Version (0x0000)
     *   2 bytes  Pad flags (0x8000 = Unicode; 0x0000 = ANSI)
     *   ?        DisplayName \0 (UTF-16LE or ANSI)
     *   ?        AddressType \0
     *   ?        EmailAddress \0
     * </pre>
     */
    private static byte[] buildOneOff(String displayName, String addressType, String email, boolean unicode) {
        return buildWithMuid(ONE_OFF_MUID, displayName, addressType, email, unicode);
    }

    private static byte[] buildWithMuid(
            byte[] muid, String displayName, String addressType, String email, boolean unicode) {
        var charset = unicode ? StandardCharsets.UTF_16LE : Charset.forName("windows-1252");
        var terminator = unicode ? new byte[] {0, 0} : new byte[] {0};

        var namePart = concat(displayName.getBytes(charset), terminator);
        var typePart = concat(addressType.getBytes(charset), terminator);
        var emailPart = concat(email.getBytes(charset), terminator);

        var strings = concat(namePart, typePart, emailPart);

        // Header: 4 (flags) + 16 (MUID) + 2 (version) + 2 (entry-flags) = 24 bytes
        var buffer = ByteBuffer.allocate(24 + strings.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0); // abFlags
        buffer.put(muid);
        buffer.putShort((short) 0); // Version
        buffer.putShort(unicode ? (short) 0x8000 : (short) 0); // Pad flags
        buffer.put(strings);
        return buffer.array();
    }

    /**
     * Builds a blob whose MUID is correct and whose header is complete (24 bytes) but that contains
     * no null terminator after the header — so the string reader will fail gracefully.
     */
    private static byte[] buildTruncatedAfterHeader(boolean unicode) {
        // Header only, no string data at all.
        var buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0);
        buffer.put(ONE_OFF_MUID);
        buffer.putShort((short) 0);
        buffer.putShort(unicode ? (short) 0x8000 : (short) 0);
        return buffer.array();
    }

    private static byte[] concat(byte[]... parts) {
        var totalLength = Arrays.stream(parts).mapToInt(part -> part.length).sum();
        var result = new byte[totalLength];
        var position = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, result, position, part.length);
            position += part.length;
        }
        return result;
    }

    /** Convenience overload for two arrays. */
    private static byte[] concat(byte[] first, byte[] second) {
        return concat(new byte[][] {first, second});
    }
}
