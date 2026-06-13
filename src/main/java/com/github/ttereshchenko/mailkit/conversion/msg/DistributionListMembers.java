package com.github.ttereshchenko.mailkit.conversion.msg;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decodes the members of an Outlook personal distribution list ({@code IPM.DistList}) from the raw
 * MAPI named-property blobs that store them.
 *
 * <p>A personal distribution list keeps its membership in two multi-valued binary properties:
 * {@code PidLidDistributionListMembers} (0x8055) and {@code PidLidDistributionListOneOffMembers}
 * (0x8054), both in {@code PSETID_Address} with type {@code PT_MV_BINARY}. Every value is an
 * EntryID ([MS-OXCDATA] §2.2.5). The only EntryID resolvable offline is the One-Off EntryID
 * ([MS-OXCDATA] §2.2.5.1, [MS-OXOABK] §2.2.4.1), which embeds the display name, address type and
 * email address inline; store/Address-Book EntryIDs only reference an entry that lives in a store
 * or directory we cannot read here, so they are skipped.
 *
 * <p>The blobs originate from untrusted {@code .msg} files, so every read is bounds-checked and the
 * parser never throws on malformed or truncated input — it skips what it cannot decode.
 */
public final class DistributionListMembers {

    /** A single decoded member; {@code email} is empty when only a display name was recoverable. */
    public record Member(String name, String email) {}

    /**
     * The One-Off EntryID provider UID ([MS-OXCDATA] §2.2.5.1). The spec defines it as a fixed
     * 16-byte {@code MAPIUID} byte sequence, NOT a GUID subject to little-endian component swapping —
     * real Outlook {@code .msg} files store it verbatim as
     * {@code 81 2B 1F A4 BE A3 10 19 9D 6E 00 DD 01 0F 54 02} (confirmed against distribution-list
     * fixtures from Outlook, Aspose and MSGReader). Endianness-swapping the leading components (the
     * {@code A4 1F 2B 81 …} form) matches no real-world member blob and decodes zero members.
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

    /** Offset of the 16-byte provider UID within an EntryID: it follows the 4-byte EntryID flags. */
    private static final int PROVIDER_UID_OFFSET = 4;

    /** Offset of the inline strings: 4-byte flags + 16-byte UID + 2-byte version + 2-byte entry flags. */
    private static final int STRINGS_OFFSET = 24;

    /** Bit {@code 0x8000} (MAE_UNICODE / "U") in the 2-byte entry-flags field: strings are UTF-16LE. */
    private static final int MAE_UNICODE = 0x8000;

    /** The 8-bit charset for non-Unicode one-off strings (the legacy code page Outlook writes). */
    private static final Charset ANSI_CHARSET = Charset.forName("windows-1252");

    private DistributionListMembers() {}

    /**
     * Decodes every one-off member from the given multi-valued binary property values. Null-safe,
     * skips empty/unparseable/non-one-off blobs, and never throws on malformed input.
     *
     * @param memberBlobs the {@code PT_MV_BINARY} values (typically of 0x8054 / 0x8055); may be
     *     {@code null} or contain {@code null} elements
     * @return the decoded members in encounter order; empty when nothing decodes
     */
    public static List<Member> parse(byte[][] memberBlobs) {
        if (memberBlobs == null) {
            return List.of();
        }
        var members = new ArrayList<Member>();
        for (var blob : memberBlobs) {
            var member = parseOneOffEntry(blob);
            if (member != null) {
                members.add(member);
            }
        }
        return members;
    }

    /**
     * Decodes a single EntryID as a One-Off EntryID, or returns {@code null} when the blob is not a
     * one-off entry (e.g. a store or Address-Book EntryID) or is too short/malformed to decode.
     */
    private static Member parseOneOffEntry(byte[] blob) {
        if (blob == null || blob.length < STRINGS_OFFSET) {
            return null;
        }
        if (!hasOneOffProviderUid(blob)) {
            return null;
        }
        // The entry flags are the 2-byte field at offset 22 (after the 2-byte version at offset 20),
        // little-endian; the U bit selects the inline string charset ([MS-OXCDATA] §2.2.5.1).
        var entryFlags = (blob[22] & 0xFF) | ((blob[23] & 0xFF) << 8);
        var unicode = (entryFlags & MAE_UNICODE) != 0;

        var strings = readTerminatedStrings(blob, STRINGS_OFFSET, unicode, 3);
        if (strings == null) {
            return null;
        }
        var displayName = strings.get(0);
        // strings.get(1) is the address type (e.g. "SMTP"); not needed for the EML rendering.
        var email = strings.get(2);
        if (displayName.isBlank() && email.isBlank()) {
            return null;
        }
        return new Member(displayName, email);
    }

    /** Whether the 16 bytes at the provider-UID offset equal the one-off MUID. */
    private static boolean hasOneOffProviderUid(byte[] blob) {
        if (blob.length < PROVIDER_UID_OFFSET + ONE_OFF_MUID.length) {
            return false;
        }
        return Arrays.equals(
                blob,
                PROVIDER_UID_OFFSET,
                PROVIDER_UID_OFFSET + ONE_OFF_MUID.length,
                ONE_OFF_MUID,
                0,
                ONE_OFF_MUID.length);
    }

    /**
     * Reads {@code count} consecutive null-terminated strings starting at {@code offset}. Unicode
     * strings are UTF-16LE with a 2-byte terminator; otherwise the charset is the legacy 8-bit
     * Windows-1252. Returns {@code null} if the blob runs out before all strings are read.
     */
    private static List<String> readTerminatedStrings(byte[] blob, int offset, boolean unicode, int count) {
        var strings = new ArrayList<String>(count);
        var position = offset;
        for (var index = 0; index < count; index++) {
            var terminator = unicode ? findUtf16Terminator(blob, position) : findByteTerminator(blob, position);
            if (terminator < 0) {
                return null;
            }
            var charset = unicode ? StandardCharsets.UTF_16LE : ANSI_CHARSET;
            strings.add(new String(blob, position, terminator - position, charset).trim());
            position = terminator + (unicode ? 2 : 1);
        }
        return strings;
    }

    /** Index of the {@code 0x00} terminator byte at or after {@code start}, or -1 if none remains. */
    private static int findByteTerminator(byte[] blob, int start) {
        for (var index = start; index < blob.length; index++) {
            if (blob[index] == 0) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Index of the {@code 0x0000} UTF-16 terminator (a two-byte zero on an even boundary relative to
     * {@code start}) at or after {@code start}, or -1 if none remains.
     */
    private static int findUtf16Terminator(byte[] blob, int start) {
        for (var index = start; index + 1 < blob.length; index += 2) {
            if (blob[index] == 0 && blob[index + 1] == 0) {
                return index;
            }
        }
        return -1;
    }
}
