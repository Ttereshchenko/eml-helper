package com.github.ttereshchenko.mailkit.conversion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses an Outlook VerbStream (PidLidVerbStream, PSETID_Common LID 0x8520, PT_BINARY) into the
 * voting-button labels a sent poll offers, per [MS-OXOMSG] §2.2.1.74. A poll message stores one
 * VerbData record per button (its ANSI label plus routing internals) followed by one
 * VoteOptionExtras record per button (the same label in Unicode); this reader walks the VerbData
 * records only far enough to reach the VoteOptionExtras, whose Unicode DisplayName is the option
 * text that both converters export as the {@code X-MS-Exchange-Vote-Options} header — the
 * send-side counterpart of the recipient-side {@code X-MS-Exchange-Vote-Response} (PidLidVerbResponse).
 *
 * <p>The whole blob is treated as untrusted input: any structural anomaly — a truncated buffer, an
 * implausible record count, a length that would over-run the stream, or a {@code Version2} that does
 * not match {@code Version} — yields an empty list rather than a partially decoded or
 * exception-throwing result, so a corrupt property never aborts a conversion or emits garbage. It
 * mirrors {@link AppointmentRecurrence#parse} / {@link WindowsTimeZone#parse}, which likewise return
 * a null/empty result on malformed input.
 *
 * <p>The exact record layout is not reproduced in {@code .docs/}; it is derived from [MS-OXOMSG]
 * §2.2.1.74 and documented here with field offsets:
 *
 * <pre>
 * VerbStream {
 *   Version           (2 bytes, LE)   // 0x0102
 *   Count             (4 bytes, LE)   // number of VerbData and of VoteOptionExtras records
 *   VerbData[Count]                   // variable length, see below
 *   Version2          (2 bytes, LE)   // MUST equal Version
 *   VoteOptionExtras[Count]           // variable length, see below
 * }
 * VerbData {                          // 4-byte head, four counted ANSI strings, 29-byte tail
 *   VerbType            (4 bytes, LE)
 *   DisplayNameCount    (1 byte)   DisplayName        (DisplayNameCount    bytes, ANSI)
 *   MsgClsNameCount     (1 byte)   MsgClsName         (MsgClsNameCount     bytes, ANSI)
 *   Internal1Count      (1 byte)   Internal1String    (Internal1Count      bytes, ANSI)
 *   DisplayNameCountDup (1 byte)   DisplayNameRepeat  (DisplayNameCountDup bytes, ANSI)
 *   Internal2 (4) Internal3 (1) fUseUSHeaders (4) Internal4 (4) SendBehavior (4)
 *   Internal5 (4) ID (4) Internal6 (4)      // = 29 fixed trailing bytes
 * }
 * VoteOptionExtras {                  // two counted UTF-16LE strings
 *   DisplayNameCount    (1 byte)   DisplayName        (DisplayNameCount    * 2 bytes, UTF-16LE)
 *   DisplayNameCountDup (1 byte)   DisplayNameRepeat  (DisplayNameCountDup * 2 bytes, UTF-16LE)
 * }
 * </pre>
 */
public final class VerbStream {

    /** Defensive upper bound on an untrusted record count: a real poll offers only a handful of buttons. */
    private static final int MAX_OPTIONS = 32;

    /** Defensive upper bound on a decoded option label; a 1-byte count already caps this at 255. */
    private static final int MAX_NAME_CHARACTERS = 255;

    /** The fixed bytes trailing the four counted strings in a VerbData record (see the class comment). */
    private static final int VERB_DATA_TAIL_BYTES = 29;

    /** The smallest structurally valid stream (Count == 0): Version + Count + Version2. */
    private static final int MINIMUM_LENGTH = 8;

    private VerbStream() {}

    /**
     * The non-blank Unicode voting-button labels the stream carries, in stored order, or an empty
     * list when the blob is absent, malformed, or carries no options. Never throws.
     */
    public static List<String> parseVoteOptions(byte[] blob) {
        if (blob == null || blob.length < MINIMUM_LENGTH) {
            return List.of();
        }
        try {
            return parseValidated(ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN));
        } catch (RuntimeException malformed) {
            // Truncated or internally inconsistent blob: report no options rather than fail the whole
            // message export (belt-and-suspenders behind the explicit range checks below).
            return List.of();
        }
    }

    private static List<String> parseValidated(ByteBuffer buffer) {
        var version = Short.toUnsignedInt(buffer.getShort());
        var count = buffer.getInt();
        if (count < 0 || count > MAX_OPTIONS) {
            return List.of();
        }
        for (var record = 0; record < count; record++) {
            if (!skipVerbData(buffer)) {
                return List.of();
            }
        }
        if (buffer.remaining() < Short.BYTES) {
            return List.of();
        }
        var version2 = Short.toUnsignedInt(buffer.getShort());
        if (version2 != version) {
            // The VerbData walk did not land exactly on Version2, so the layout assumption is wrong and
            // nothing beyond can be trusted as a vote option ([MS-OXOMSG] §2.2.1.74: the two MUST match).
            return List.of();
        }
        var options = new ArrayList<String>();
        for (var record = 0; record < count; record++) {
            var name = readVoteOptionExtra(buffer);
            if (name == null) {
                return List.of();
            }
            var trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                options.add(trimmed);
            }
        }
        return options;
    }

    /** Advances past one VerbData record; returns {@code false} when the record would over-run the buffer. */
    private static boolean skipVerbData(ByteBuffer buffer) {
        if (buffer.remaining() < Integer.BYTES) {
            return false;
        }
        buffer.getInt(); // VerbType — unused; only its position matters
        // Four counted ANSI strings: DisplayName, MsgClsName, Internal1String, DisplayNameRepeat.
        for (var string = 0; string < 4; string++) {
            if (buffer.remaining() < 1) {
                return false;
            }
            var length = Byte.toUnsignedInt(buffer.get());
            if (buffer.remaining() < length) {
                return false;
            }
            buffer.position(buffer.position() + length);
        }
        if (buffer.remaining() < VERB_DATA_TAIL_BYTES) {
            return false;
        }
        buffer.position(buffer.position() + VERB_DATA_TAIL_BYTES);
        return true;
    }

    /**
     * Reads one VoteOptionExtras record and returns its Unicode DisplayName, or {@code null} when the
     * record would over-run the buffer. The DisplayNameRepeat that follows is consumed but discarded.
     */
    private static String readVoteOptionExtra(ByteBuffer buffer) {
        if (buffer.remaining() < 1) {
            return null;
        }
        var nameCharacters = Byte.toUnsignedInt(buffer.get());
        if (nameCharacters > MAX_NAME_CHARACTERS || buffer.remaining() < nameCharacters * 2) {
            return null;
        }
        var nameBytes = new byte[nameCharacters * 2];
        buffer.get(nameBytes);
        var name = new String(nameBytes, StandardCharsets.UTF_16LE);

        if (buffer.remaining() < 1) {
            return null;
        }
        var repeatCharacters = Byte.toUnsignedInt(buffer.get());
        if (buffer.remaining() < repeatCharacters * 2) {
            return null;
        }
        buffer.position(buffer.position() + repeatCharacters * 2);
        return name;
    }
}
