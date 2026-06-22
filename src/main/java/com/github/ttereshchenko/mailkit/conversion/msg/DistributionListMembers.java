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
 * EntryID ([MS-OXCDATA] §2.2.5). Two kinds are resolvable offline: the One-Off EntryID
 * ([MS-OXCDATA] §2.2.5.1, [MS-OXOABK] §2.2.4.1), which embeds the display name, address type and
 * email address inline, and the Address-Book EntryID ([MS-OXCDATA] §2.2.5.2), whose X.500 DN is
 * IMCEA-encapsulated the same way the PST recipient parser handles it. Store EntryIDs only reference
 * an entry that lives in a store we cannot read here, so they are skipped.
 *
 * <p>The blobs originate from untrusted {@code .msg} files, so every read is bounds-checked and the
 * parser never throws on malformed or truncated input — it skips what it cannot decode.
 */
public final class DistributionListMembers {

    /**
     * A single decoded member. {@code addressType} is the MAPI address type of {@code email} (e.g.
     * {@code SMTP} or {@code EX}), used to IMCEA-encapsulate a non-SMTP address before rendering;
     * {@code email} is empty when only a display name was recoverable.
     */
    public record Member(String name, String addressType, String email) {}

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

    /**
     * The Address-Book EntryID provider UID ([MS-OXCDATA] §2.2.5.2):
     * {@code DC A7 40 C8 C0 42 10 1A B4 B9 08 00 2B 2F E1 82}. Kept in sync with pst-parser
     * {@code Message.ADDRESS_BOOK_PROVIDER_UID}.
     */
    private static final byte[] ADDRESS_BOOK_MUID = {
        (byte) 0xDC,
        (byte) 0xA7,
        (byte) 0x40,
        (byte) 0xC8,
        (byte) 0xC0,
        (byte) 0x42,
        (byte) 0x10,
        (byte) 0x1A,
        (byte) 0xB4,
        (byte) 0xB9,
        (byte) 0x08,
        (byte) 0x00,
        (byte) 0x2B,
        (byte) 0x2F,
        (byte) 0xE1,
        (byte) 0x82
    };

    /** Offset of the 16-byte provider UID within an EntryID: it follows the 4-byte EntryID flags. */
    private static final int PROVIDER_UID_OFFSET = 4;

    /** Offset of the inline strings: 4-byte flags + 16-byte UID + 2-byte version + 2-byte entry flags. */
    private static final int STRINGS_OFFSET = 24;

    /** Offset of the X.500 DN in an Address-Book EntryID: 4-byte flags + 16-byte UID + 4 version + 4 type. */
    private static final int AB_DN_OFFSET = 28;

    /** Bit {@code 0x8000} (MAE_UNICODE / "U") in the 2-byte entry-flags field: strings are UTF-16LE. */
    private static final int MAE_UNICODE = 0x8000;

    /** The default 8-bit charset for non-Unicode one-off strings when the message declares no code page. */
    private static final Charset ANSI_CHARSET = Charset.forName("windows-1252");

    private DistributionListMembers() {}

    /**
     * Decodes every one-off member using the windows-1252 default code page for non-Unicode inline
     * strings. Equivalent to {@link #parse(byte[][], Charset)} with {@link #ANSI_CHARSET}.
     */
    public static List<Member> parse(byte[][] memberBlobs) {
        return parse(memberBlobs, ANSI_CHARSET);
    }

    /**
     * Decodes every member from the given multi-valued binary property values. Null-safe,
     * skips empty/unparseable/unsupported-provider blobs, and never throws on malformed input.
     *
     * @param memberBlobs the {@code PT_MV_BINARY} values (typically of 0x8054 / 0x8055); may be
     *     {@code null} or contain {@code null} elements
     * @param ansiCharset the code page for non-Unicode (MAE_UNICODE-clear) inline strings — the
     *     message's PR_MESSAGE_CODEPAGE/PR_INTERNET_CPID; Unicode entries ignore it (they are UTF-16LE)
     * @return the decoded members in encounter order; empty when nothing decodes
     */
    public static List<Member> parse(byte[][] memberBlobs, Charset ansiCharset) {
        if (memberBlobs == null) {
            return List.of();
        }
        var members = new ArrayList<Member>();
        for (var blob : memberBlobs) {
            var member = parseMember(blob, ansiCharset);
            if (member != null) {
                members.add(member);
            }
        }
        return members;
    }

    /**
     * Decodes a single EntryID member, dispatching on its 16-byte provider UID: a One-Off EntryID
     * ([MS-OXCDATA] §2.2.5.1) or an Address-Book EntryID ([MS-OXCDATA] §2.2.5.2). Returns {@code null}
     * for any other provider (e.g. a store EntryID) or a blob too short/malformed to decode.
     */
    private static Member parseMember(byte[] blob, Charset ansiCharset) {
        if (blob == null || blob.length < PROVIDER_UID_OFFSET + ONE_OFF_MUID.length) {
            return null;
        }
        if (hasProviderUid(blob, ONE_OFF_MUID)) {
            return parseOneOffEntry(blob, ansiCharset);
        }
        if (hasProviderUid(blob, ADDRESS_BOOK_MUID)) {
            return parseAddressBookEntry(blob);
        }
        return null;
    }

    /** Decodes a One-Off EntryID's inline display name, address type and email ([MS-OXCDATA] §2.2.5.1). */
    private static Member parseOneOffEntry(byte[] blob, Charset ansiCharset) {
        if (blob.length < STRINGS_OFFSET) {
            return null;
        }
        // The entry flags are the 2-byte field at offset 22 (after the 2-byte version at offset 20),
        // little-endian; the U bit selects the inline string charset ([MS-OXCDATA] §2.2.5.1).
        var entryFlags = (blob[22] & 0xFF) | ((blob[23] & 0xFF) << 8);
        var unicode = (entryFlags & MAE_UNICODE) != 0;

        var strings = readTerminatedStrings(blob, STRINGS_OFFSET, unicode, 3, ansiCharset);
        if (strings == null) {
            return null;
        }
        var displayName = strings.get(0);
        var addressType = strings.get(1);
        var email = strings.get(2);
        if (displayName.isBlank() && email.isBlank()) {
            return null;
        }
        return new Member(displayName, addressType, email);
    }

    /**
     * Decodes an Address-Book EntryID's X.500 DN ([MS-OXCDATA] §2.2.5.2): a US-ASCII, null-terminated
     * string after the 4-byte version and 4-byte type that follow the provider UID. The member carries
     * address type {@code EX} so the caller IMCEA-encapsulates the DN, mirroring the PST recipient
     * parser ({@code Message.parseEntryIdRecipient}). The DN has no inline display name.
     */
    private static Member parseAddressBookEntry(byte[] blob) {
        if (blob.length <= AB_DN_OFFSET) {
            return null;
        }
        var end = AB_DN_OFFSET;
        while (end < blob.length && blob[end] != 0) {
            end++;
        }
        var legacyDn = new String(blob, AB_DN_OFFSET, end - AB_DN_OFFSET, StandardCharsets.US_ASCII).trim();
        if (legacyDn.isBlank()) {
            return null;
        }
        return new Member("", "EX", legacyDn);
    }

    /** Whether the 16 bytes at the provider-UID offset equal {@code uid}. */
    private static boolean hasProviderUid(byte[] blob, byte[] uid) {
        if (blob.length < PROVIDER_UID_OFFSET + uid.length) {
            return false;
        }
        return Arrays.equals(blob, PROVIDER_UID_OFFSET, PROVIDER_UID_OFFSET + uid.length, uid, 0, uid.length);
    }

    /**
     * Reads {@code count} consecutive null-terminated strings starting at {@code offset}. Unicode
     * strings are UTF-16LE with a 2-byte terminator; otherwise the charset is the supplied
     * {@code ansiCharset}. Returns {@code null} if the blob runs out before all strings are read.
     */
    private static List<String> readTerminatedStrings(
            byte[] blob, int offset, boolean unicode, int count, Charset ansiCharset) {
        var strings = new ArrayList<String>(count);
        var position = offset;
        for (var index = 0; index < count; index++) {
            var terminator = unicode ? findUtf16Terminator(blob, position) : findByteTerminator(blob, position);
            if (terminator < 0) {
                return null;
            }
            var charset = unicode ? StandardCharsets.UTF_16LE : ansiCharset;
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
