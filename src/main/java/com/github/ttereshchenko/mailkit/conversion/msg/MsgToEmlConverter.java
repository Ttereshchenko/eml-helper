package com.github.ttereshchenko.mailkit.conversion.msg;

import com.github.ttereshchenko.mailkit.conversion.AppointmentRecurrence;
import com.github.ttereshchenko.mailkit.conversion.AttachmentBudget;
import com.github.ttereshchenko.mailkit.conversion.ConversionException;
import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.conversion.EmlSerializer;
import com.github.ttereshchenko.mailkit.conversion.HtmlMetaCharset;
import com.github.ttereshchenko.mailkit.conversion.ICalendarGenerator;
import com.github.ttereshchenko.mailkit.conversion.ReportGenerator;
import com.github.ttereshchenko.mailkit.conversion.RtfStripper;
import com.github.ttereshchenko.mailkit.conversion.SmimeEntityHoist;
import com.github.ttereshchenko.mailkit.conversion.VCardGenerator;
import com.github.ttereshchenko.mailkit.conversion.WindowsTimeZone;
import com.github.ttereshchenko.mailkit.pst.CompressedRtf;
import com.github.ttereshchenko.mailkit.pst.Message;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.hpsf.ClassID;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.ByteChunk;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertiesChunk;
import org.apache.poi.hsmf.datatypes.PropertyValue;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.hsmf.exceptions.ChunkNotFoundException;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.CodePageUtil;

/**
 * Converts an Outlook {@code .msg} file (an OLE2/CFB container, parsed by Apache POI's HSMF module)
 * into a standards-compliant EML document written through {@link EmlSerializer}.
 *
 * <p>Both modern Unicode and legacy ANSI MSG files are supported; for ANSI files the string codepage
 * is detected from the message's codepage properties/headers before any text is read. Plain-text and
 * HTML bodies are exported as alternatives; an RTF body is de-encapsulated back to HTML when it is an
 * MS-OXRTFEX wrapper and otherwise preserved as a {@code body.rtf} attachment (with a stripped
 * plain-text fallback when no other body exists). Attachments —
 * including recursively nested {@code .msg} messages (capped at depth 10) and OLE objects — are
 * preserved, and transport headers missing from the source are synthesized from MAPI properties.
 *
 * <p>Every parse-level failure mode of the underlying POI parser is collapsed into
 * {@link ConversionException}; progress and degradations (e.g. an attachment whose payload could not
 * be extracted) are reported through the supplied {@link ConversionLog}.
 */
public final class MsgToEmlConverter {

    // Kept in step with PstToEmlConverter.MAX_EMBEDDED_DEPTH so both pipelines truncate alike.
    private static final int MAX_EMBEDDED_DEPTH = 10;

    // Bounds the synthesized To/Cc/Bcc headers on a pathological message; the overflow is logged and
    // truncated rather than failing the conversion of an otherwise valid message.
    private static final int MAX_RECIPIENTS = 2048;

    // windows-1252 is the legacy fallback charset: it backs a body.rtf attachment only when the raw
    // PR_RTF_COMPRESSED chunk is unavailable (the faithful path decompresses that chunk to exact bytes
    // via CompressedRtf), and is the default code page when a message declares none. Mirrors the PST
    // converter.
    private static final Charset RTF_CHARSET = Charset.forName("windows-1252");

    // Named-property sets ([MS-OXPROPS] §1.3.2) resolved through the message's __nameid mapping.
    private static final ClassID PSETID_APPOINTMENT = new ClassID("{00062002-0000-0000-C000-000000000046}");
    private static final ClassID PSETID_MEETING = new ClassID("{6ED8DA90-450B-101B-98DA-00AA003F1305}");
    private static final ClassID PSETID_TASK = new ClassID("{00062003-0000-0000-C000-000000000046}");
    private static final ClassID PSETID_ADDRESS = new ClassID("{00062004-0000-0000-C000-000000000046}");
    private static final ClassID PS_PUBLIC_STRINGS = new ClassID("{00020329-0000-0000-C000-000000000046}");

    private MsgToEmlConverter() {}

    /**
     * Converts the MSG document supplied as a stream. The underlying parser buffers the entire stream
     * in memory; prefer {@link #convert(Path, OutputStream, ConversionLog)} when the message already
     * exists on disk.
     *
     * @param msgStream raw {@code .msg} bytes; fully consumed but not closed
     * @param outStream destination for the generated EML (encoded as UTF-8); flushed but not closed
     * @param log progress/degradation sink; never {@code null} (pass {@link ConversionLog#NOOP})
     * @throws ConversionException if the input is not an OLE2 container or its MAPI structures are
     *     corrupt or truncated — POI's checked and unchecked parse failures are wrapped with the
     *     original exception kept as the cause
     */
    public static void convert(InputStream msgStream, OutputStream outStream, ConversionLog log)
            throws ConversionException {
        Objects.requireNonNull(msgStream, "msgStream");
        Objects.requireNonNull(outStream, "outStream");
        Objects.requireNonNull(log, "log");
        try (var message = new MAPIMessage(msgStream)) {
            writeEml(message, outStream, log);
        } catch (ChunkNotFoundException | IOException | RuntimeException failure) {
            throw wrap(failure);
        }
    }

    /**
     * Converts a {@code .msg} file in place on disk. Unlike
     * {@link #convert(InputStream, OutputStream, ConversionLog)} this opens the file through a
     * file-channel-backed block store, so OLE blocks are read on demand instead of the whole file
     * being buffered in heap — use this overload for large messages.
     *
     * @param msgPath path to the {@code .msg} file
     * @param outStream destination for the generated EML (encoded as UTF-8); flushed but not closed
     * @param log progress/degradation sink; never {@code null} (pass {@link ConversionLog#NOOP})
     * @throws ConversionException if the file cannot be read, is not an OLE2 container, or its MAPI
     *     structures are corrupt or truncated
     */
    public static void convert(Path msgPath, OutputStream outStream, ConversionLog log) throws ConversionException {
        Objects.requireNonNull(msgPath, "msgPath");
        Objects.requireNonNull(outStream, "outStream");
        Objects.requireNonNull(log, "log");
        try (var message = new MAPIMessage(msgPath.toFile())) {
            writeEml(message, outStream, log);
        } catch (ChunkNotFoundException | IOException | RuntimeException failure) {
            throw wrap(failure);
        }
    }

    private static void writeEml(MAPIMessage message, OutputStream outStream, ConversionLog log)
            throws IOException, ChunkNotFoundException {
        // writeTo(OutputStream) encodes the message as UTF-8 (matching the PST path: a stored
        // transport-header block may legitimately carry non-ASCII bytes), but writes a hoisted
        // S/MIME entity's body as raw bytes so an 8-bit clear-signed envelope is preserved verbatim.
        convert(message, 0, log).writeTo(outStream);
    }

    private static ConversionException wrap(Exception failure) {
        // POI surfaces malformed input as its own checked ChunkNotFoundException, various unchecked
        // exceptions (e.g. RecordFormatException), and parse-level IOExceptions (NotOLE2FileException).
        // Collapse them into one domain type so the conversion API has a clean exception boundary.
        var detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return new ConversionException("Failed to convert MSG to EML: " + detail, failure);
    }

    static EmlSerializer convert(MAPIMessage message, int depth, ConversionLog log)
            throws IOException, ChunkNotFoundException {
        // Each top-level message gets a fresh attachment budget; the recursive overload threads the
        // same instance through embedded messages so the caps bound the whole tree (see AttachmentBudget).
        return convert(message, depth, log, new AttachmentBudget());
    }

    private static EmlSerializer convert(MAPIMessage message, int depth, ConversionLog log, AttachmentBudget budget)
            throws IOException, ChunkNotFoundException {
        if (depth > MAX_EMBEDDED_DEPTH) {
            // Mirror the PST converter: emit a stub rather than throwing, so an over-deep nested
            // message truncates gracefully instead of failing the entire parent conversion.
            var stub = new EmlSerializer();
            stub.setSubject("Nested Message Limit Exceeded");
            stub.addBody("The maximum nested message depth was reached.", "text/plain; charset=UTF-8");
            return stub;
        }
        message.setReturnNullOnMissingChunk(true);
        if (message.has7BitEncodingStrings()) {
            // Legacy ANSI MSG: string chunks are stored as 8-bit bytes whose codepage lives in
            // PR_MESSAGE_CODEPAGE / PR_INTERNET_CPID / the headers charset. Without this detection POI
            // decodes every string as windows-1252, mojibaking Cyrillic/CJK subjects and bodies.
            message.guess7BitEncoding();
            // POI's guess7BitEncoding has two blind spots (a UTF-8 body codepage it discards, and
            // attachment name chunks it never visits); compensate for both before any string is read.
            applySourceCodepage(message, log);
        }

        var details = message.getRecipientDetailsChunks();
        if (details != null && details.length > MAX_RECIPIENTS) {
            log.error("Message has " + details.length + " recipients; exporting only the first " + MAX_RECIPIENTS);
        }

        // The legacy code page for this message's non-Unicode one-off strings (reply-to entries and
        // distribution-list members), applied only where MAE_UNICODE is clear; defaults to windows-1252.
        var ansiCharset = resolveMessageAnsiCharset(message);

        var serializer = new EmlSerializer();

        var transportHeaders = readMainString(message, MAPIProperty.TRANSPORT_MESSAGE_HEADERS);
        if (transportHeaders != null && !transportHeaders.isBlank()) {
            serializer.setTransportHeaders(transportHeaders);
        }

        // RFC 5322 §3.6.2: From names the author and Sender the actual transmitter. MAPI stores the
        // author in PR_SENT_REPRESENTING_* and the transmitter in PR_SENDER_*; on a delegate send
        // the two differ and both must survive (the PST pipeline performs the same split). Name and
        // address are always paired from the same identity, so the transmitter's display name can
        // no longer be attributed to the author's address or vice versa.
        var sender = resolveSenderIdentity(message);
        var author = resolveAuthorIdentity(message);
        var from = sender;
        if (author.hasEmail()) {
            if (!sender.hasEmail()) {
                from = author;
            } else if (!author.email().equalsIgnoreCase(sender.email())) {
                from = author;
                serializer.setTransmitter(sender.name(), sender.email());
            } else if (from.name() == null || from.name().isBlank()) {
                // Same address for sender and represented author but PR_SENDER_NAME is absent: pair the
                // author's display name with the From address instead of emitting an address-only From
                // (RFC 5322 §3.6.2). Mirrors the PST path, which backfills the represented author's name
                // when the sender name is blank.
                from = new MailboxIdentity(author.name(), from.email());
            }
        } else if (sender.isBlank() && !author.isBlank()) {
            from = author;
        }
        serializer.setSender(from.name(), from.email());
        populateRecipients(message, EmlSerializer.RECIPIENT_TYPE_TO, serializer);
        populateRecipients(message, EmlSerializer.RECIPIENT_TYPE_CC, serializer);
        populateRecipients(message, EmlSerializer.RECIPIENT_TYPE_BCC, serializer);
        serializer.setSubject(safeString(safeSubject(message)));
        serializer.setDate(safeDate(message));
        var messageId = readMainString(message, MAPIProperty.INTERNET_MESSAGE_ID);
        if (messageId != null && !messageId.isBlank()) {
            serializer.setMessageId(messageId);
        }

        // Recover reply-threading headers from MAPI when the original transport headers are absent;
        // EmlSerializer's transport-header dedup keeps these from doubling up when they are present.
        // RFC 5322 §3.6.4: In-Reply-To/References are msg-id lists and each id is angle-bracketed,
        // exactly as Message-ID is. MAPI stores these unbracketed, so normalize them the same way the
        // serializer normalizes Message-ID instead of emitting the bare values.
        var inReplyTo = readMainString(message, MAPIProperty.IN_REPLY_TO_ID);
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            var normalized = EmlSerializer.normalizeMessageIdList(inReplyTo);
            if (!normalized.isEmpty()) {
                serializer.addCustomHeader("In-Reply-To", normalized);
            }
        }
        var references = readMainString(message, MAPIProperty.INTERNET_REFERENCES);
        if (references != null && !references.isBlank()) {
            var normalized = EmlSerializer.normalizeMessageIdList(references);
            if (!normalized.isEmpty()) {
                serializer.addCustomHeader("References", normalized);
            }
        }

        // PidTagContentFilterSpamConfidenceLevel. POI has no constant for it, and a
        // MAPIProperty.createCustom lookup key can never match a map entry (MAPIProperty does not
        // override equals/hashCode), so the property table is scanned by numeric id instead.
        var spamConfidenceLevel = readMainLong(message, 0x4076);
        if (spamConfidenceLevel != null) {
            serializer.setScl(spamConfidenceLevel);
        }

        populateMapiHeaders(message, serializer, from, ansiCharset);

        var messageClass = readMessageClass(message);
        if (messageClass != null
                && (messageClass.startsWith("IPM.Note.SMIME") || messageClass.startsWith("IPM.Note.Secure"))
                && hoistSmimeEntity(message, serializer, log)) {
            // [MS-OXOSMIME] §2.2.1 (and the legacy IPM.Note.Secure classes): the single attachment IS
            // the original signed/encrypted MIME entity, so it becomes the message's own top-level
            // entity and the signature stays verifiable. Bodies and other content are skipped — they
            // live inside the hoisted entity.
            return serializer;
        }

        // REPORT.* messages (NDR/DSN and read/non-read receipts) become an RFC 6522 multipart/report
        // so the structured delivery-status / disposition-notification survives instead of being
        // flattened to a plain body.
        if (messageClass != null
                && messageClass.startsWith("REPORT.")
                && emitReport(message, messageClass, serializer)) {
            return serializer;
        }

        // A distribution list carries no body of its own: synthesize one listing its members, mirroring
        // the PST pipeline. Fall back to the regular body pass when no members decode.
        var distListBody = messageClass != null
                && messageClass.startsWith("IPM.DistList")
                && populateDistributionList(message, serializer, ansiCharset);
        if (!distListBody) {
            populateBodies(message, serializer, log);
        }
        populateCalendarInvite(message, messageClass, from, serializer, log);
        populateContactCard(message, messageClass, serializer);
        populateTaskTodo(message, messageClass, serializer, log);
        if (messageClass != null && !hasSpecializedHandler(messageClass)) {
            // Every other class still exported a generic EML above; make that downgrade visible rather
            // than silently dropping the item's specialized semantics (journal, document, custom forms).
            log.info("No specialized handler for message class " + messageClass + "; exported as a generic message");
        }

        populateAttachments(message, depth, serializer, log, budget);

        return serializer;
    }

    private static void populateBodies(MAPIMessage message, EmlSerializer serializer, ConversionLog log) {
        var hasPlain = false;
        var text = readBody(message::getTextBody, "plain text", log);
        if (text != null && !text.isEmpty()) {
            serializer.addBody(text, "text/plain; charset=UTF-8");
            hasPlain = true;
        }
        var hasHtml = false;
        var html = readBody(message::getHtmlBody, "HTML", log);
        if (html != null && !html.isEmpty()) {
            // The HTML was decoded from its original codepage and is re-emitted as UTF-8; rewrite any
            // surviving in-document <meta charset=...> so it cannot contradict the MIME charset.
            serializer.addBody(HtmlMetaCharset.rewriteToUtf8(html), "text/html; charset=UTF-8");
            hasHtml = true;
        }
        var rtfText = readBody(message::getRtfBody, "RTF", log);
        if (rtfText == null || rtfText.isEmpty()) {
            return;
        }
        if (RtfStripper.isHtmlEncapsulated(rtfText)) {
            // HTML-encapsulated RTF (MS-OXRTFEX) is just a transport encoding of the HTML body: when
            // the real HTML is present the RTF adds nothing and is dropped; otherwise the HTML is
            // recovered from it. Only if recovery comes back empty is the raw RTF preserved (as a
            // body.rtf attachment) so the body text is not lost entirely.
            if (hasHtml) {
                return;
            }
            var recovered = RtfStripper.deEncapsulateHtml(rtfText);
            if (!recovered.isBlank()) {
                serializer.addBody(HtmlMetaCharset.rewriteToUtf8(recovered), "text/html; charset=UTF-8");
            } else {
                serializer.addAttachment("body.rtf", "application/rtf", rawRtfBytes(message, rtfText), null, false);
            }
            return;
        }
        // A genuine RTF body is not renderable by mail clients as a multipart/alternative sibling;
        // mirror the PST converter: strip it to a plain-text body fallback when no plain text exists,
        // and preserve the original rich text as a body.rtf attachment.
        if (!hasPlain) {
            var stripped = RtfStripper.strip(rtfText);
            if (!stripped.isEmpty()) {
                serializer.addBody(stripped, "text/plain; charset=UTF-8");
            }
        }
        serializer.addAttachment("body.rtf", "application/rtf", rawRtfBytes(message, rtfText), null, false);
    }

    /**
     * The byte-faithful RTF for a {@code body.rtf} attachment: the raw {@code PR_RTF_COMPRESSED} chunk
     * decompressed straight to bytes ([MS-OXRTFCP]), which preserves every octet of the RTF stream.
     * POI's only RTF accessor ({@link MAPIMessage#getRtfBody()}) returns a windows-1252 {@code String};
     * re-encoding that maps the five byte values that code page leaves undefined to {@code '?'} and
     * corrupts the attachment, so the compressed chunk is decoded independently — matching the PST
     * path's {@link Message#getRawRtfBytes()}. Falls back to re-encoding {@code rtfText} only when the
     * compressed chunk is unavailable.
     */
    private static byte[] rawRtfBytes(MAPIMessage message, String rtfText) {
        var mainChunks = message.getMainChunks();
        if (mainChunks != null) {
            var rtfChunk = mainChunks.getRtfBodyChunk();
            if (rtfChunk != null && rtfChunk.getValue() != null) {
                var decoded = CompressedRtf.decompressToBytes(rtfChunk.getValue());
                if (decoded.length > 0) {
                    return decoded;
                }
            }
        }
        return rtfText.getBytes(RTF_CHARSET);
    }

    /** Supplies one body flavour from POI; the checked miss exception keeps the lambda references terse. */
    @FunctionalInterface
    private interface BodySupplier {
        String get() throws ChunkNotFoundException;
    }

    private static String readBody(BodySupplier supplier, String kind, ConversionLog log) {
        try {
            return supplier.get();
        } catch (ChunkNotFoundException ignored) {
            return null;
        } catch (RuntimeException failure) {
            // POI surfaces a corrupt body stream as an unchecked exception (a truncated compressed
            // RTF stream becomes IllegalStateException, a bad codepage an IllegalArgumentException);
            // degrade to "no such body" so one broken part does not fail the whole conversion.
            log.error("Could not extract the " + kind + " body, skipping it: "
                    + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            return null;
        }
    }

    private static void populateAttachments(
            MAPIMessage message, int depth, EmlSerializer serializer, ConversionLog log, AttachmentBudget budget)
            throws IOException {
        var raw = message.getAttachmentFiles();
        if (raw == null || raw.length == 0) {
            return;
        }
        var usedEmbeddedNames = new HashSet<String>();
        for (var chunks : raw) {
            // Bound what a hostile .msg can buffer: EmlSerializer holds every part in memory before
            // writing, so an unbounded count or aggregate size can OutOfMemoryError (which escapes the
            // per-item catch below). The budget is shared with nested messages so the caps cover the
            // whole tree, mirroring the PST converter.
            if (budget.atCountLimit()) {
                log.error("Message has more than " + AttachmentBudget.MAX_ATTACHMENT_COUNT
                        + " attachments (including nested messages); remaining attachments were skipped");
                break;
            }
            budget.recordAttachment();
            if (isEmbeddedMessageAttachment(chunks)) {
                var embedded = chunks.getEmbeddedMessage();
                byte[] nestedEml;
                try {
                    // Serialize the child to BYTES (not a char sink): a nested clear-signed S/MIME entity
                    // must reach the parent EML byte-for-byte or its signature breaks. EmlSerializer emits
                    // these bytes through its byte-exact raw-body path.
                    var nestedStream = new ByteArrayOutputStream();
                    convert(embedded, depth + 1, log, budget).writeTo(nestedStream);
                    nestedEml = nestedStream.toByteArray();
                } catch (Exception failure) {
                    log.error("Failed to convert embedded message: " + failure.getMessage());
                    nestedEml = ("Subject: Error converting nested message\r\n\r\n" + failure.getMessage())
                            .getBytes(StandardCharsets.UTF_8);
                }
                // Count the serialized nested EML against the aggregate byte cap (its own nested-attachment
                // bytes were already recorded during recursion; this also bounds the retained EML text and
                // its base64 expansion).
                if (budget.recordBytes(nestedEml.length)) {
                    log.error("Message attachments exceed " + AttachmentBudget.maxTotalMegabytes()
                            + " MB in aggregate (including nested messages); remaining attachments were skipped");
                    break;
                }
                var subject = safeString(safeSubject(embedded));
                var filename = uniqueEmbeddedName(subject.isBlank() ? "embedded" : subject, usedEmbeddedNames);
                log.info("Found embedded message attachment: " + filename);
                serializer.addEmbeddedMessage(filename, nestedEml);
            } else {
                var bytes = attachmentBytes(chunks, log);
                var method = readAttachmentMethod(chunks);
                if (bytes.length == 0 && method != null && method == 5) {
                    // ATTACH_EMBEDDED_MSG (5) that isEmbeddedMessageAttachment() could not route because
                    // the sub-storage is missing or unreadable; name the lost nested message rather than
                    // leaving only attachmentBytes's generic "carries no data" note.
                    log.error("Embedded message attachment has no readable storage; the nested message was lost");
                }
                if (budget.recordBytes(bytes.length)) {
                    log.error("Message attachments exceed " + AttachmentBudget.maxTotalMegabytes()
                            + " MB in aggregate (including nested messages); remaining attachments were skipped");
                    break;
                }
                var filename = pickFilename(chunks);
                if ((filename == null || filename.isBlank()) && chunks.getAttachmentDirectory() != null) {
                    // An OLE-embedded object (ATTACH_OLE) usually has no filename properties, only a
                    // display name; the rewrapped storage is an OLE2 compound file.
                    var displayName = chunkValue(chunks.getAttachDisplayName());
                    filename = (displayName == null || displayName.isBlank()
                                    ? "object"
                                    : EmlSerializer.sanitizeFilename(displayName))
                            + ".ole";
                }
                var mime = pickMimeType(chunks);
                var contentId = pickContentId(chunks);
                var contentLocation = chunkValue(chunks.getAttachContentLocation());
                // A Content-ID marks a part as an inline candidate; EmlSerializer demotes it to a
                // regular attachment unless an HTML body actually references the cid (see writeTo),
                // so an unreferenced cid never produces a stray multipart/related member.
                boolean isInline = contentId != null;

                log.info("Found attachment: " + filename + " (" + mime + ")");
                serializer.addAttachment(filename, mime, bytes, contentId, contentLocation, isInline);
            }
        }
    }

    /**
     * True only for a genuine embedded message. POI's {@code isEmbeddedMessage()} is merely "has a
     * sub-storage", which also matches an embedded OLE object (PR_ATTACH_METHOD 6, e.g. a pasted
     * Excel sheet); routing those through {@code getEmbeddedMessage()} destroyed them — the payload
     * was replaced by an "Error converting nested message" stub. The attach method decides when it is
     * stored; POI's storage heuristic remains the fallback for files that omit it.
     */
    private static boolean isEmbeddedMessageAttachment(AttachmentChunks chunks) {
        var method = readAttachmentMethod(chunks);
        if (method != null) {
            return method == 5 && chunks.getAttachmentDirectory() != null; // ATTACH_EMBEDDED_MSG
        }
        return chunks.isEmbeddedMessage();
    }

    /** PR_ATTACH_METHOD from the attachment's fixed-property stream, or {@code null} when absent. */
    private static Integer readAttachmentMethod(AttachmentChunks chunks) {
        for (var chunk : chunks.getAll()) {
            if (chunk instanceof PropertiesChunk propertiesChunk) {
                var values = propertiesChunk.getProperties().get(MAPIProperty.ATTACH_METHOD);
                if (values != null && !values.isEmpty() && values.get(0).getValue() instanceof Number number) {
                    return number.intValue();
                }
            }
        }
        return null;
    }

    /** Builds {@code <subject>.eml}, appending {@code " (2)"}, {@code " (3)"}… when an earlier sibling took the name. */
    private static String uniqueEmbeddedName(String subject, Set<String> usedNames) {
        var base = EmlSerializer.sanitizeFilename(subject);
        var filename = base + ".eml";
        var counter = 2;
        while (!usedNames.add(filename)) {
            filename = base + " (" + counter++ + ").eml";
        }
        return filename;
    }

    private static byte[] attachmentBytes(AttachmentChunks chunks, ConversionLog log) {
        var dataChunk = chunks.getAttachData();
        if (dataChunk != null && dataChunk.getValue() != null) {
            return dataChunk.getValue();
        }
        var dirChunk = chunks.getAttachmentDirectory();
        if (dirChunk != null && dirChunk.getDirectory() != null) {
            try (var fs = new POIFSFileSystem()) {
                EntryUtils.copyNodeRecursively(dirChunk.getDirectory(), fs.getRoot());
                var out = new ByteArrayOutputStream();
                fs.writeFilesystem(out);
                return out.toByteArray();
            } catch (Exception failure) {
                log.error("Could not extract OLE attachment data, emitting it empty: " + failure.getMessage());
                return new byte[0];
            }
        }
        log.error("Attachment carries no data, emitting it empty");
        return new byte[0];
    }

    private static String pickFilename(AttachmentChunks chunks) {
        var longName = chunkValue(chunks.getAttachLongFileName());
        if (longName != null && !longName.isBlank()) {
            return longName;
        }
        var shortName = chunkValue(chunks.getAttachFileName());
        if (shortName != null && !shortName.isBlank()) {
            return shortName;
        }
        var extension = chunkValue(chunks.getAttachExtension());
        if (extension != null && !extension.isBlank()) {
            return "attachment" + (extension.startsWith(".") ? extension : "." + extension);
        }
        return null;
    }

    private static String pickMimeType(AttachmentChunks chunks) {
        var mime = chunkValue(chunks.getAttachMimeTag());
        if (mime != null && !mime.isBlank()) {
            // Pass the stored PR_ATTACH_MIME_TAG through verbatim, parameters included: a charset= or
            // method= is the only decode hint a base64 attachment carries and must survive. The serializer
            // drops any name=/filename= parameter before appending its own name= (EmlSerializer
            // .dropNameParameters), so a stored `image/png; name="x"` no longer yields a duplicate
            // parameter (rfc2045 §5.1) while `text/plain; charset=koi8-r` keeps its charset.
            return mime.trim();
        }
        return "application/octet-stream";
    }

    private static String chunkValue(StringChunk chunk) {
        return chunk == null ? null : chunk.getValue();
    }

    private static String pickContentId(AttachmentChunks chunks) {
        // PR_ATTACH_CONTENT_ID is stored without angle brackets ([MS-OXCMSG] §2.2.2.5), but strip any a
        // sender wrote anyway so a stored "<foo@bar>" still matches the bare cid: URL form in the HTML
        // body and stays inline. Shared with the PST driver via EmlSerializer.normalizeContentId.
        return EmlSerializer.normalizeContentId(chunkValue(chunks.getAttachContentId()));
    }

    /** One MAPI mailbox identity — display name and address resolved from the same property family. */
    private record MailboxIdentity(String name, String email) {
        boolean hasEmail() {
            return email != null && !email.isBlank();
        }

        boolean isBlank() {
            return (name == null || name.isBlank()) && !hasEmail();
        }
    }

    /**
     * The actual transmitter: PR_SENDER_NAME (which is what POI's {@code getDisplayFrom} maps to)
     * paired with the sender's own address — PidTagSenderSmtpAddress (0x5D01) first, then the
     * IMCEA-encapsulated PR_SENDER_EMAIL_ADDRESS. POI has no constant for 0x5D01, and a
     * MAPIProperty.createCustom lookup key can never match a map entry (MAPIProperty does not
     * override equals/hashCode), so the chunk map is scanned by numeric property id.
     */
    private static MailboxIdentity resolveSenderIdentity(MAPIMessage message) {
        String name = null;
        try {
            name = message.getDisplayFrom();
        } catch (ChunkNotFoundException ignored) {
            // optional
        }
        if (name == null || name.isBlank()) {
            name = readMainString(message, MAPIProperty.SENDER_NAME);
        }
        var email = readMainStringById(message, 0x5D01);
        if (email == null || email.isBlank()) {
            email = readMainString(message, MAPIProperty.SENDER_EMAIL_ADDRESS);
            if (email != null && !email.isBlank()) {
                var addrType = readMainString(message, MAPIProperty.SENDER_ADDRTYPE);
                email = EmlSerializer.imceaEncapsulate(addrType, email);
            }
        }
        return new MailboxIdentity(name, email);
    }

    /**
     * The author (on-behalf-of) identity from PR_SENT_REPRESENTING_*: name 0x0042 paired with
     * PidTagSentRepresentingSmtpAddress (0x5D02) or the IMCEA-encapsulated
     * PR_SENT_REPRESENTING_EMAIL_ADDRESS. On a normal send Outlook stores the same values here as in
     * PR_SENDER_*.
     */
    private static MailboxIdentity resolveAuthorIdentity(MAPIMessage message) {
        var name = readMainString(message, MAPIProperty.SENT_REPRESENTING_NAME);
        var email = readMainStringById(message, 0x5D02);
        if (email == null || email.isBlank()) {
            email = readMainString(message, MAPIProperty.SENT_REPRESENTING_EMAIL_ADDRESS);
            if (email != null && !email.isBlank()) {
                var addrType = readMainString(message, MAPIProperty.SENT_REPRESENTING_ADDRTYPE);
                email = EmlSerializer.imceaEncapsulate(addrType, email);
            }
        }
        return new MailboxIdentity(name, email);
    }

    /**
     * Exports the MAPI properties that map onto standard headers and have no transport-header source
     * on sent/draft messages: threading, importance, sensitivity, reply-to, categories and the
     * read-receipt request — the same set the PST pipeline emits. {@link EmlSerializer} skips each
     * one when a stored transport-header block already carries the same header, so none can double up.
     */
    private static void populateMapiHeaders(
            MAPIMessage message, EmlSerializer serializer, MailboxIdentity from, Charset ansiCharset) {
        var importance = readMainLong(message, 0x0017); // PR_IMPORTANCE: 0 = low, 1 = normal, 2 = high
        if (importance != null && importance == 2) {
            serializer.addCustomHeader("Importance", "High");
            serializer.addCustomHeader("X-Priority", "1");
        } else if (importance != null && importance == 0) {
            serializer.addCustomHeader("Importance", "Low");
            serializer.addCustomHeader("X-Priority", "5");
        }
        var sensitivity = readMainLong(message, 0x0036); // PR_SENSITIVITY: 1-3 map to RFC 2156 values
        if (sensitivity != null) {
            var sensitivityLabel =
                    switch (sensitivity) {
                        case 1 -> "Personal";
                        case 2 -> "Private";
                        case 3 -> "Company-Confidential";
                        default -> null;
                    };
            if (sensitivityLabel != null) {
                serializer.addCustomHeader("Sensitivity", sensitivityLabel);
            }
        }
        var threadTopic = readMainString(message, MAPIProperty.CONVERSATION_TOPIC);
        if (threadTopic != null && !threadTopic.isBlank()) {
            serializer.addCustomHeader("Thread-Topic", threadTopic);
        }
        var conversationIndex = readMainBytesById(message, 0x0071); // PR_CONVERSATION_INDEX
        if (conversationIndex != null && conversationIndex.length > 0) {
            serializer.addCustomHeader("Thread-Index", Base64.getEncoder().encodeToString(conversationIndex));
        }
        // PR_REPLY_RECIPIENT_ENTRIES holds the same [MS-OXCDATA] FLATENTRYLIST a PST stores, so the
        // pst-parser's parser is reused; PR_REPLY_RECIPIENT_NAMES supplies display-name fallbacks.
        var replyEntries = readMainBytesById(message, 0x004F);
        if (replyEntries != null) {
            var replyNames = readMainStringById(message, 0x0050);
            var replyTo = new ArrayList<String>();
            for (Message.Recipient recipient : Message.parseReplyRecipients(
                    replyEntries, replyNames, ansiCharset, Message.AddressPreference.PREFER_SMTP)) {
                var formatted = EmlSerializer.formatAddress(recipient.name, recipient.email);
                if (!formatted.isBlank()) {
                    replyTo.add(formatted);
                }
            }
            if (!replyTo.isEmpty()) {
                serializer.addAddressHeader("Reply-To", String.join(", ", replyTo));
            }
        }
        var keywords = readNamedStrings(message, PS_PUBLIC_STRINGS, "Keywords", 0); // PidNameKeywords
        if (!keywords.isEmpty()) {
            serializer.addCustomHeader("Keywords", String.join(", ", keywords));
        }
        // PR_READ_RECEIPT_REQUESTED -> Disposition-Notification-To (RFC 8098), addressed to the author.
        if (Boolean.TRUE.equals(readMainBoolean(message, 0x0029)) && from.hasEmail()) {
            serializer.addAddressHeader(
                    "Disposition-Notification-To", EmlSerializer.formatAddress(from.name(), from.email()));
        }
    }

    private static String readMessageClass(MAPIMessage message) {
        var mainChunks = message.getMainChunks();
        if (mainChunks == null || mainChunks.getMessageClass() == null) {
            return null;
        }
        return mainChunks.getMessageClass().getValue();
    }

    /**
     * Hoists the stored S/MIME envelope of an {@code IPM.Note.SMIME*} message to the top level.
     * [MS-OXOSMIME] §2.2.1: such a message stores its complete original MIME content in a single
     * attachment — a clear-signed message as a full MIME entity (headers + multipart/signed body),
     * an opaque signed/encrypted one as a raw PKCS#7 blob ({@code smime.p7m}). Re-encoding either
     * through the regular body/attachment pipeline demotes the envelope to an opaque attachment and
     * makes the signature unverifiable. The byte-to-entity reasoning lives in the POI-free
     * {@link SmimeEntityHoist} shared with the PST path; this method only supplies the single
     * data-bearing attachment POI exposes. Returns {@code false} (the caller falls back to the
     * regular pipeline) when the message does not consist of exactly one such attachment.
     */
    private static boolean hoistSmimeEntity(MAPIMessage message, EmlSerializer serializer, ConversionLog log) {
        var raw = message.getAttachmentFiles();
        if (raw == null || raw.length != 1) {
            return false;
        }
        var chunks = raw[0];
        var dataChunk = chunks.getAttachData();
        if (chunks.getAttachmentDirectory() != null
                || dataChunk == null
                || dataChunk.getValue() == null
                || dataChunk.getValue().length == 0) {
            return false;
        }
        var entity = SmimeEntityHoist.hoist(
                dataChunk.getValue(), pickFilename(chunks), chunkValue(chunks.getAttachMimeTag()));
        serializer.setRawEntity(entity.contentType(), entity.transferEncoding(), entity.disposition(), entity.body());
        if (entity.fromMimeHeaders()) {
            log.info("S/MIME message: hoisted the stored MIME entity (" + entity.contentType() + ")");
        } else {
            log.info("S/MIME message: exported the stored PKCS#7 envelope as the message body (" + entity.contentType()
                    + ")");
        }
        return true;
    }

    /**
     * Attaches an {@code invite.ics} for calendar items and meeting messages, mirroring the PST
     * pipeline: start/end/location, the all-day flag, the event time zone and the recurrence pattern
     * are read through the named-property mapping ({@code __nameid}) that POI resolves via
     * {@link org.apache.poi.hsmf.datatypes.NameIdChunks#getPropertyTag}.
     */
    private static void populateCalendarInvite(
            MAPIMessage message,
            String messageClass,
            MailboxIdentity organizer,
            EmlSerializer serializer,
            ConversionLog log) {
        if (messageClass == null
                || !(messageClass.startsWith("IPM.Appointment") || messageClass.startsWith("IPM.Schedule.Meeting"))) {
            return;
        }
        var start = readNamedTime(message, PSETID_APPOINTMENT, 0x820D); // PidLidAppointmentStartWhole
        if (start == null) {
            // Without a real start time the invite would have to fabricate one; emit none instead.
            log.info("Skipping calendar invite: no start time stored");
            return;
        }
        var end = readNamedTime(message, PSETID_APPOINTMENT, 0x820E); // PidLidAppointmentEndWhole
        var location = readNamedString(message, PSETID_APPOINTMENT, 0x8208); // PidLidLocation
        var allDay = Boolean.TRUE.equals(readNamedBoolean(message, PSETID_APPOINTMENT, 0x8215));
        var timeZoneStruct = readNamedBytes(message, PSETID_APPOINTMENT, 0x8233); // PidLidTimeZoneStruct
        var timeZone = timeZoneStruct != null ? WindowsTimeZone.parse(timeZoneStruct) : null;
        AppointmentRecurrence.Pattern recurrence = null;
        var recurrenceBlob = readNamedBytes(message, PSETID_APPOINTMENT, 0x8216); // PidLidAppointmentRecur
        if (recurrenceBlob != null) {
            recurrence = AppointmentRecurrence.parse(recurrenceBlob);
            if (recurrence == null) {
                log.info("The appointment has a recurrence pattern this converter cannot map (malformed or"
                        + " non-Gregorian); the invite carries the first occurrence only");
            }
        }
        var attendees = visibleAttendees(message);
        var method = ICalendarGenerator.method(messageClass, !attendees.isEmpty());
        var organizerName = organizer.name();
        var organizerEmail = organizer.email();
        List<ICalendarGenerator.Attendee> eventAttendees = attendees;
        if ("REPLY".equals(method)) {
            // RFC 5546 §3.2.3: a meeting-response REPLY flows from the responding ATTENDEE to the
            // meeting ORGANIZER, carrying that attendee's PARTSTAT. In the MSG the responder is the
            // sender (the `organizer` identity here) and the meeting organizer is the To recipient, so
            // the two roles are swapped relative to a REQUEST and the PARTSTAT is attached to the
            // single responding attendee. (attendees is non-empty — method() returns REPLY only then.)
            // Prefer the To recipient rather than the first row so a Cc'd delegate is not mistaken for
            // the organizer.
            var details = message.getRecipientDetailsChunks();
            var meetingOrganizer = pickReplyMeetingOrganizer(details, attendees);
            organizerName = meetingOrganizer.name();
            organizerEmail = meetingOrganizer.email();
            eventAttendees = List.of(new ICalendarGenerator.Attendee(
                    organizer.name(), organizer.email(), ICalendarGenerator.responsePartStat(messageClass)));
        }
        var sequence = readNamedLong(message, PSETID_APPOINTMENT, 0x8201); // PidLidAppointmentSequence
        // PidLidCleanGlobalObjectId (PSETID_Meeting, LID 0x0023, PT_BINARY): the meeting's stable
        // identity that maps to the iCal UID ([MS-OXCICAL] §2.1.3.1.1.20.26), so a REQUEST/REPLY/CANCEL
        // of the same meeting share one UID. Absent on personal appointments, which then get a random UID.
        var cleanGlobalObjectId = readNamedBytes(message, PSETID_MEETING, 0x0023);
        var eventDetails = new ICalendarGenerator.EventDetails(
                method,
                start,
                end,
                location,
                safeString(safeSubject(message)),
                organizerName,
                organizerEmail,
                readBody(message::getTextBody, "plain text", log),
                eventAttendees,
                allDay,
                timeZone,
                recurrence,
                sequence != null ? sequence : 0,
                cleanGlobalObjectId);
        var ical = ICalendarGenerator.generate(eventDetails);
        serializer.addAttachment(
                "invite.ics",
                // Stamp method= with what generate() actually emitted: it downgrades to PUBLISH without a
                // resolvable organizer/start, and the MIME method= must equal the body METHOD (rfc6047 §2.4).
                "text/calendar; charset=UTF-8; method=" + ICalendarGenerator.effectiveMethod(eventDetails),
                ical.getBytes(StandardCharsets.UTF_8),
                null,
                false);
    }

    /** A vCard for an {@code IPM.Contact} item: names, organization, phones and Email1-3 — mirrors the PST pipeline. */
    private static void populateContactCard(MAPIMessage message, String messageClass, EmlSerializer serializer) {
        if (messageClass == null || !messageClass.startsWith("IPM.Contact")) {
            return;
        }
        var contact = new VCardGenerator.Contact()
                .displayName(readMainStringById(message, 0x3001)) // PR_DISPLAY_NAME
                .givenName(readMainStringById(message, 0x3A06))
                .surname(readMainStringById(message, 0x3A11))
                .middleName(readMainStringById(message, 0x3A44)) // PR_MIDDLE_NAME
                .namePrefix(readMainStringById(message, 0x3A45)) // PR_DISPLAY_NAME_PREFIX
                .nameSuffix(readMainStringById(message, 0x3A05)) // PR_GENERATION
                .company(readMainStringById(message, 0x3A16))
                .jobTitle(readMainStringById(message, 0x3A17))
                .phone("work", readMainStringById(message, 0x3A08))
                .phone("home", readMainStringById(message, 0x3A09))
                .phone("cell", readMainStringById(message, 0x3A1C));
        // PidLidEmail1EmailAddress / Email2 / Email3 ([MS-OXOCNTC] §2.2.1.2.3).
        for (var namedId : new int[] {0x8083, 0x8093, 0x80A3}) {
            contact.email(readNamedString(message, PSETID_ADDRESS, namedId));
        }
        serializer.addAttachment(
                "contact.vcf",
                "text/vcard; charset=UTF-8",
                VCardGenerator.generate(contact).getBytes(StandardCharsets.UTF_8),
                null,
                false);
    }

    /**
     * A VTODO for a task item: start/due dates, completion state and percent complete. A plain
     * {@code IPM.Task} is published; an assigned {@code IPM.TaskRequest} (or its accept/decline/update
     * response) carries the matching iTIP {@code METHOD} instead of being mislabeled as a plain task.
     */
    private static void populateTaskTodo(
            MAPIMessage message, String messageClass, EmlSerializer serializer, ConversionLog log) {
        if (messageClass == null || !messageClass.startsWith("IPM.Task")) {
            return;
        }
        var method = taskMethod(messageClass);
        var start = readNamedTime(message, PSETID_TASK, 0x8104); // PidLidTaskStartDate
        var due = readNamedTime(message, PSETID_TASK, 0x8105); // PidLidTaskDueDate
        var percent = readNamedDouble(message, PSETID_TASK, 0x8102); // PidLidPercentComplete
        var complete = readNamedBoolean(message, PSETID_TASK, 0x811C); // PidLidTaskComplete
        var completed = readNamedTime(message, PSETID_TASK, 0x810F); // PidLidTaskDateCompleted
        // RFC 5546 §3.4: a task REQUEST/REPLY carries an ORGANIZER and ATTENDEE(s). For a REQUEST the
        // ORGANIZER is the assigner (sender) and the ATTENDEE(s) the assignee recipients. For a REPLY the
        // roles swap (mirroring the meeting path): the ORGANIZER is the original assigner (the To
        // recipient) and the single ATTENDEE is the responding sender, carrying the accept/decline
        // PARTSTAT. A plain task keeps neither. effectiveTodoMethod downgrades to PUBLISH when the parties
        // a scheduling object needs are missing.
        String organizerName = null;
        String organizerEmail = null;
        List<ICalendarGenerator.Attendee> attendees = List.of();
        if ("REQUEST".equals(method)) {
            var assigner = resolveAuthorIdentity(message);
            if (assigner.email() == null || assigner.email().isBlank()) {
                assigner = resolveSenderIdentity(message);
            }
            organizerName = assigner.name();
            organizerEmail = assigner.email();
            attendees = visibleAttendees(message);
        } else if ("REPLY".equals(method)) {
            var responder = resolveAuthorIdentity(message);
            if (responder.email() == null || responder.email().isBlank()) {
                responder = resolveSenderIdentity(message);
            }
            var replyAttendees = visibleAttendees(message);
            if (!replyAttendees.isEmpty()) {
                var assigner = pickReplyMeetingOrganizer(message.getRecipientDetailsChunks(), replyAttendees);
                organizerName = assigner.name();
                organizerEmail = assigner.email();
                attendees = List.of(new ICalendarGenerator.Attendee(
                        responder.name(), responder.email(), ICalendarGenerator.taskResponsePartStat(messageClass)));
            }
            // else: no recipient identifies the assigner — leave participants empty to downgrade to PUBLISH.
        }
        var todo = ICalendarGenerator.generateTodo(
                safeString(safeSubject(message)),
                readBody(message::getTextBody, "plain text", log),
                start,
                due,
                percent,
                complete,
                completed,
                method,
                organizerName,
                organizerEmail,
                attendees);
        serializer.addAttachment(
                "task.ics",
                "text/calendar; charset=UTF-8; method="
                        + ICalendarGenerator.effectiveTodoMethod(method, organizerEmail, attendees),
                todo.getBytes(StandardCharsets.UTF_8),
                null,
                false);
    }

    /**
     * The visible ATTENDEE list (RFC 5546) for a meeting or assigned-task scheduling object: every
     * recipient with a resolvable address except BCC-class recipients, which are hidden from the other
     * participants and must not leak into a property they can all read.
     */
    private static List<ICalendarGenerator.Attendee> visibleAttendees(MAPIMessage message) {
        var attendees = new ArrayList<ICalendarGenerator.Attendee>();
        var details = message.getRecipientDetailsChunks();
        if (details != null) {
            var limit = Math.min(details.length, MAX_RECIPIENTS);
            for (var index = 0; index < limit; index++) {
                var type = readRecipientType(details[index]);
                if (type != null && type == EmlSerializer.RECIPIENT_TYPE_BCC) {
                    continue;
                }
                var address = resolveRecipientAddress(details[index]);
                if (address != null && !address.isBlank()) {
                    attendees.add(new ICalendarGenerator.Attendee(details[index].getRecipientName(), address));
                }
            }
        }
        return attendees;
    }

    /**
     * The iTIP method (RFC 5546 §3.4) for a task message class: {@code IPM.TaskRequest} is a
     * {@code REQUEST}, its {@code .Accept}/{@code .Decline}/{@code .Update} responses are
     * {@code REPLY}s, and a plain {@code IPM.Task} is {@code PUBLISH}ed. Distinguishing
     * {@code IPM.TaskRequest*} from {@code IPM.Task} here is what stops a task request — which has no
     * dot after {@code Task} — from being swallowed by a naive {@code startsWith("IPM.Task")} and
     * exported as a plain published task.
     */
    private static String taskMethod(String messageClass) {
        if (messageClass.startsWith("IPM.TaskRequest.Accept")
                || messageClass.startsWith("IPM.TaskRequest.Decline")
                || messageClass.startsWith("IPM.TaskRequest.Update")) {
            return "REPLY";
        }
        if (messageClass.startsWith("IPM.TaskRequest")) {
            return "REQUEST";
        }
        return "PUBLISH";
    }

    /**
     * All byte-chunk values stored under a named property — a {@code PT_MV_BINARY} property arrives in
     * POI as multiple {@link ByteChunk}s sharing one property id (the binary analogue of how
     * {@link #readNamedStrings} collects multiple {@link StringChunk}s), so the single-valued
     * {@link #readNamedBytes} would silently drop all but the first value.
     */
    private static byte[][] readNamedMultiBytes(MAPIMessage message, ClassID propertySet, long lid) {
        var propertyId = namedPropertyId(message, propertySet, null, lid);
        var mainChunks = message.getMainChunks();
        if (propertyId < 0 || mainChunks == null) {
            return new byte[0][];
        }
        var values = new ArrayList<byte[]>();
        for (var entry : mainChunks.getAll().entrySet()) {
            if (entry.getKey().id != propertyId) {
                continue;
            }
            for (var chunk : entry.getValue()) {
                if (chunk instanceof ByteChunk byteChunk && byteChunk.getValue() != null) {
                    values.add(byteChunk.getValue());
                }
            }
        }
        return values.toArray(new byte[0][]);
    }

    /**
     * Synthesizes the plain-text body of an {@code IPM.DistList}: one member per line, mirroring the
     * PST pipeline's {@code formatDistributionListMembers}. Members are decoded from
     * {@code PidLidDistributionListOneOffMembers} (which carries inline addresses), falling back to
     * {@code PidLidDistributionListMembers}. Returns {@code false} when nothing decodes, so the caller
     * falls back to the regular body pass.
     */
    private static boolean populateDistributionList(
            MAPIMessage message, EmlSerializer serializer, Charset ansiCharset) {
        var blobs = readNamedMultiBytes(message, PSETID_ADDRESS, 0x8054); // PidLidDistributionListOneOffMembers
        if (blobs.length == 0) {
            blobs = readNamedMultiBytes(message, PSETID_ADDRESS, 0x8055); // PidLidDistributionListMembers
        }
        var members = DistributionListMembers.parse(blobs, ansiCharset);
        if (members.isEmpty()) {
            return false;
        }
        var listing = new StringBuilder("Distribution list members:\r\n");
        for (var member : members) {
            // IMCEA-encapsulate a non-SMTP member address (an Exchange X.500/EX one-off or Address-Book
            // DN) before rendering, exactly as the PST distribution-list path does; an SMTP/addr-spec
            // value passes through imceaEncapsulate unchanged.
            var email = EmlSerializer.imceaEncapsulate(member.addressType(), member.email());
            var formatted = EmlSerializer.formatAddressPlain(member.name(), email);
            if (!formatted.isBlank()) {
                listing.append("- ").append(formatted).append("\r\n");
            }
        }
        serializer.addBody(listing.toString(), "text/plain; charset=UTF-8");
        return true;
    }

    /**
     * Replaces the message body with an RFC 6522 {@code multipart/report} reconstructed from the
     * report's MAPI properties: a delivery report ({@code REPORT.*.NDR}/{@code .DR}) yields a
     * {@code message/delivery-status} part (RFC 3464), a read receipt ({@code .IPNRN}/{@code .IPNNRN})
     * a {@code message/disposition-notification} part (RFC 8098). The class suffix selects the branch
     * and supplies {@code Action}/{@code Disposition}, which MAPI does not store verbatim.
     *
     * <p>For a delivery report the per-recipient fields are sourced from the failed recipient rather
     * than the bounce's own envelope: {@code Final-Recipient} (rfc3464 §2.3.2) is the address that
     * actually failed (the NDR's recipient-table entry), the {@code Status} {@code d.d.d} code
     * (rfc3464 §2.3.4) comes from the report's enhanced-status property, and the free-form
     * {@code PidTagSupplementaryInfo} text becomes the {@code Diagnostic-Code} (rfc3464 §2.3.6) — it
     * is human-readable transport text and must not be fed into the strictly-formatted {@code Status}.
     */
    private static boolean emitReport(MAPIMessage message, String messageClass, EmlSerializer serializer) {
        var deliveryReport = messageClass.endsWith(".NDR") || messageClass.endsWith(".DR");
        // Only NDR/DR delivery reports and IPNRN/IPNNRN read receipts carry a structured status. Any
        // other REPORT.* (delay/relay/etc.) is neither a DSN nor an MDN, so it must not be emitted as a
        // disposition-notification claiming the message was "displayed" (rfc8098 §3.2.6). Decline it and
        // let the caller fall back to the generic body.
        var readReceipt = messageClass.endsWith(".IPNRN") || messageClass.endsWith(".IPNNRN");
        if (!deliveryReport && !readReceipt) {
            return false;
        }
        var action = messageClass.endsWith(".NDR") ? "failed" : messageClass.endsWith(".DR") ? "delivered" : null;
        var dispositionType = messageClass.endsWith(".IPNNRN") ? "deleted" : "displayed";
        // The free-form supplementary info is the transport diagnostic, not a status code.
        var supplementaryInfo = readMainStringById(message, 0x0C1B); // PidTagSupplementaryInfo
        String status = null;
        String diagnosticCode = null;
        String finalRecipient = null;
        if (deliveryReport) {
            finalRecipient = reportFailedRecipient(message);
            status = reportStatusCode(message, supplementaryInfo, messageClass.endsWith(".NDR"));
            diagnosticCode = supplementaryInfo;
        } else {
            // rfc8098 §3.2.4: an MDN's Final-Recipient is the party who read the message and is issuing
            // the receipt — the receipt's own author/sender (PR_SENT_REPRESENTING_*/PR_SENDER_*), not its
            // To recipient (PidTagDisplayTo holds the original sender who requested the receipt).
            finalRecipient = resolveAuthorIdentity(message).email();
            if (finalRecipient == null || finalRecipient.isBlank()) {
                finalRecipient = resolveSenderIdentity(message).email();
            }
        }
        if (finalRecipient == null || finalRecipient.isBlank()) {
            // Last resort only: no per-recipient (DSN) or reader (MDN) address was available.
            finalRecipient = readMainStringById(message, 0x0E04); // PidTagDisplayTo
        }
        var info = new ReportGenerator.ReportInfo(
                deliveryReport,
                readMainStringById(message, 0x1001), // PidTagReportText
                readMainStringById(message, 0x6820), // PidTagReportingMessageTransferAgent
                finalRecipient,
                action,
                status,
                diagnosticCode,
                readMainStringById(message, 0x1046), // PidTagOriginalMessageId of the original
                dispositionType);
        var report = ReportGenerator.generate(info);
        serializer.setRawEntity(report.contentType(), null, null, report.body().getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /**
     * The address that actually failed (rfc3464 §2.3.2): the recipient-table entry of the NDR message
     * holding the failed recipient — not the bounce's own {@code PidTagDisplayTo}. A {@code RECIPIENT_TYPE_TO}
     * row is preferred, because an NDR's recipient table can also carry non-TO routing entries; the
     * first row with any resolvable address is the fallback. Returns {@code null} when the report stores
     * no recipient table or no resolvable address.
     */
    private static String reportFailedRecipient(MAPIMessage message) {
        var details = message.getRecipientDetailsChunks();
        if (details == null) {
            return null;
        }
        var limit = Math.min(details.length, MAX_RECIPIENTS);
        String firstWithAddress = null;
        for (var index = 0; index < limit; index++) {
            var address = resolveRecipientAddress(details[index]);
            if (address == null || address.isBlank()) {
                continue;
            }
            var type = readRecipientType(details[index]);
            if (type != null && type == EmlSerializer.RECIPIENT_TYPE_TO) {
                return address;
            }
            if (firstWithAddress == null) {
                firstWithAddress = address;
            }
        }
        return firstWithAddress;
    }

    /**
     * The DSN {@code Status} {@code d.d.d} code (rfc3464 §2.3.4) for this report: the enhanced-status
     * token embedded in {@code PidTagSupplementaryInfo} or {@code PidTagReportText}, else a class
     * default. Delegates to {@link ReportGenerator#statusCode} so MSG and PST derive it identically.
     */
    private static String reportStatusCode(MAPIMessage message, String supplementaryInfo, boolean failed) {
        return ReportGenerator.statusCode(failed, supplementaryInfo, readMainStringById(message, 0x1001));
    }

    /**
     * Whether a message class produced a specialized artifact above (S/MIME hoist, calendar invite,
     * vCard, VTODO, distribution-list body or report) or is a plain note/post. Anything else still
     * exported a generic EML, which the caller logs as a downgrade.
     */
    private static boolean hasSpecializedHandler(String messageClass) {
        return messageClass.equals("IPM")
                || messageClass.startsWith("IPM.Note")
                || messageClass.startsWith("IPM.Post")
                || messageClass.startsWith("IPM.Appointment")
                || messageClass.startsWith("IPM.Schedule.Meeting")
                || messageClass.startsWith("IPM.Contact")
                || messageClass.startsWith("IPM.Task")
                || messageClass.startsWith("IPM.DistList")
                || messageClass.startsWith("REPORT.");
    }

    /**
     * Resolves a named property (property set GUID + numeric or string name) to the file-local
     * transient property id, or -1 when the message has no {@code __nameid} mapping for it.
     */
    private static int namedPropertyId(MAPIMessage message, ClassID propertySet, String name, long lid) {
        var nameIdChunks = message.getNameIdChunks();
        if (nameIdChunks == null) {
            return -1;
        }
        var tag = nameIdChunks.getPropertyTag(propertySet, name, lid);
        return tag == 0 ? -1 : (int) tag;
    }

    private static Date readNamedTime(MAPIMessage message, ClassID propertySet, long lid) {
        var propertyId = namedPropertyId(message, propertySet, null, lid);
        return propertyId >= 0 && readFixedValueById(message, propertyId) instanceof Calendar calendar
                ? calendar.getTime()
                : null;
    }

    private static Boolean readNamedBoolean(MAPIMessage message, ClassID propertySet, long lid) {
        var propertyId = namedPropertyId(message, propertySet, null, lid);
        return propertyId >= 0 ? readMainBoolean(message, propertyId) : null;
    }

    private static Double readNamedDouble(MAPIMessage message, ClassID propertySet, long lid) {
        var propertyId = namedPropertyId(message, propertySet, null, lid);
        return propertyId >= 0 && readFixedValueById(message, propertyId) instanceof Number number
                ? number.doubleValue()
                : null;
    }

    private static Integer readNamedLong(MAPIMessage message, ClassID propertySet, long lid) {
        var propertyId = namedPropertyId(message, propertySet, null, lid);
        return propertyId >= 0 ? readMainLong(message, propertyId) : null;
    }

    private static byte[] readNamedBytes(MAPIMessage message, ClassID propertySet, long lid) {
        var propertyId = namedPropertyId(message, propertySet, null, lid);
        return propertyId >= 0 ? readMainBytesById(message, propertyId) : null;
    }

    private static String readNamedString(MAPIMessage message, ClassID propertySet, long lid) {
        var propertyId = namedPropertyId(message, propertySet, null, lid);
        return propertyId >= 0 ? readMainStringById(message, propertyId) : null;
    }

    /** All string values stored under a named property (a multi-valued property yields one chunk per value). */
    private static List<String> readNamedStrings(MAPIMessage message, ClassID propertySet, String name, long lid) {
        var propertyId = namedPropertyId(message, propertySet, name, lid);
        var mainChunks = message.getMainChunks();
        if (propertyId < 0 || mainChunks == null) {
            return List.of();
        }
        var values = new ArrayList<String>();
        for (var entry : mainChunks.getAll().entrySet()) {
            if (entry.getKey().id != propertyId) {
                continue;
            }
            for (var chunk : entry.getValue()) {
                if (chunk instanceof StringChunk stringChunk
                        && stringChunk.getValue() != null
                        && !stringChunk.getValue().isBlank()) {
                    values.add(stringChunk.getValue().trim());
                }
            }
        }
        return values;
    }

    /** The first byte-chunk value stored under the given property id, or {@code null}. */
    private static byte[] readMainBytesById(MAPIMessage message, int propertyId) {
        var mainChunks = message.getMainChunks();
        if (mainChunks == null) {
            return null;
        }
        for (var entry : mainChunks.getAll().entrySet()) {
            if (entry.getKey().id != propertyId) {
                continue;
            }
            for (var chunk : entry.getValue()) {
                if (chunk instanceof ByteChunk byteChunk && byteChunk.getValue() != null) {
                    return byteChunk.getValue();
                }
            }
        }
        return null;
    }

    /** The first fixed-size property value stored under the given id, or {@code null}. */
    private static Object readFixedValueById(MAPIMessage message, int propertyId) {
        var mainChunks = message.getMainChunks();
        if (mainChunks == null) {
            return null;
        }
        for (var entry : mainChunks.getProperties().entrySet()) {
            if (entry.getKey().id != propertyId) {
                continue;
            }
            var values = entry.getValue();
            if (values != null && !values.isEmpty()) {
                return values.get(0).getValue();
            }
        }
        return null;
    }

    private static Boolean readMainBoolean(MAPIMessage message, int propertyId) {
        return switch (readFixedValueById(message, propertyId)) {
            case Boolean value -> value;
            case Number value -> value.intValue() != 0;
            case null, default -> null;
        };
    }

    /**
     * The recipient's address: PR_SMTP_ADDRESS when present (POI exposes it as its own chunk — a map
     * lookup with a MAPIProperty.createCustom key can never match, no equals/hashCode), otherwise the
     * IMCEA-encapsulated raw PR_EMAIL_ADDRESS, or {@code null} when the entry stores no address at all.
     *
     * <p>The raw {@code getRecipientEmailChunk()} value is read deliberately instead of POI's
     * {@code getRecipientEmailAddress()}: when PR_EMAIL_ADDRESS holds an Exchange legacyDN the latter
     * returns only the substring after the first {@code /CN=}, dropping the mandatory {@code /O=}/{@code
     * /OU=} X.500 prefix and yielding a non-roundtrippable IMCEAEX address. The PST path encapsulates
     * the full DN (Message.resolveRecipientEmail), so reading the raw chunk keeps MSG and PST in step.
     */
    private static String resolveRecipientAddress(RecipientChunks chunks) {
        var smtpChunk = chunks.getRecipientSMTPChunk();
        var smtpAddress = smtpChunk == null ? null : smtpChunk.getValue();
        if (smtpAddress != null && !smtpAddress.isBlank()) {
            return smtpAddress;
        }
        var emailChunk = chunks.getRecipientEmailChunk();
        var address = emailChunk == null ? null : emailChunk.getValue();
        if (address == null || address.isBlank()) {
            return null;
        }
        return EmlSerializer.imceaEncapsulate(chunkValue(chunks.getDeliveryTypeChunk()), address);
    }

    private static void populateRecipients(MAPIMessage message, int wantedType, EmlSerializer serializer) {
        var details = message.getRecipientDetailsChunks();
        if (details != null && details.length > 0) {
            boolean found = false;
            var limit = Math.min(details.length, MAX_RECIPIENTS);
            for (var index = 0; index < limit; index++) {
                var chunks = details[index];
                // [MS-OXOMSG] §2.2.3.1 makes PidTagRecipientType mandatory on every recipient row; when
                // it is absent (a malformed .msg) default the row to To rather than dropping it, matching
                // the PST path (Message.parseRecipients defaults an untyped recipient to MAPI_TO) so the
                // address is still preserved in the output.
                var type = readRecipientType(chunks);
                var effectiveType = type != null ? type : EmlSerializer.RECIPIENT_TYPE_TO;
                if (effectiveType == wantedType) {
                    var name = chunks.getRecipientName();
                    var address = resolveRecipientAddress(chunks);
                    // Only treat the row as a usable recipient (and so suppress the PR_DISPLAY_* fallback)
                    // when it actually yields a name or address; a type-matching but empty row would
                    // otherwise leave the header blank while skipping the display-string fallback that
                    // could still populate it.
                    if ((name != null && !name.isBlank()) || (address != null && !address.isBlank())) {
                        serializer.addRecipient(wantedType, name, address);
                        found = true;
                    }
                }
            }
            if (found) return;
        }

        try {
            String fallback = null;
            if (wantedType == EmlSerializer.RECIPIENT_TYPE_TO) {
                fallback = message.getDisplayTo();
            } else if (wantedType == EmlSerializer.RECIPIENT_TYPE_CC) {
                fallback = message.getDisplayCC();
            } else if (wantedType == EmlSerializer.RECIPIENT_TYPE_BCC) {
                fallback = message.getDisplayBCC();
            }
            if (fallback != null && !fallback.isBlank()) {
                // PR_DISPLAY_TO/CC/BCC is MAPI's own semicolon-delimited display string; split on ";"
                // with surrounding whitespace absorbed. This is a last-resort fallback only reached when
                // the structured recipient table above is unavailable.
                for (String part : fallback.split("\\s*;\\s*")) {
                    part = part.trim();
                    if (part.isEmpty()) {
                        continue;
                    }
                    if (EmlSerializer.looksLikeSmtpAddress(part)) {
                        serializer.addRecipient(wantedType, null, part);
                    } else {
                        // A bare display name: leave the address empty so the serializer emits its
                        // explicit undisclosed@invalid placeholder instead of an unparseable
                        // "Name" <Name> angle-addr.
                        serializer.addRecipient(wantedType, part, null);
                    }
                }
            }
        } catch (ChunkNotFoundException ignored) {
        }
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static String safeSubject(MAPIMessage message) {
        try {
            return message.getSubject();
        } catch (ChunkNotFoundException ignored) {
            return null;
        }
    }

    private static Date safeDate(MAPIMessage message) {
        // RFC 5322 §3.6.1: the Date header is the origination time, "specifically not ... the time that
        // the message was actually transported". Prefer PR_CLIENT_SUBMIT_TIME and fall back to
        // PR_MESSAGE_DELIVERY_TIME, mirroring the PST pipeline (Message.getMessageDate). Reading the two
        // time properties directly also avoids POI getMessageDate()'s further fallback to the
        // modification/creation time, which corresponds to no Date: source.
        var submitDate = nonSentinelDate(readTimeProperty(message, MAPIProperty.CLIENT_SUBMIT_TIME));
        return submitDate != null
                ? submitDate
                : nonSentinelDate(readTimeProperty(message, MAPIProperty.MESSAGE_DELIVERY_TIME));
    }

    /**
     * Treats the FILETIME-0 sentinel (1601-01-01T00:00:00Z, i.e. {@code -11_644_473_600_000} ms) as
     * "no date", mirroring {@code Message.nonSentinelDate} on the PST side so a message whose
     * PR_CLIENT_SUBMIT_TIME is stored as 0 does not export a bogus {@code Date: ... 1 Jan 1601} header.
     */
    static Date nonSentinelDate(Date date) {
        return date != null && date.getTime() > -11_644_473_600_000L ? date : null;
    }

    private static Date readTimeProperty(MAPIMessage message, MAPIProperty property) {
        var mainChunks = message.getMainChunks();
        if (mainChunks == null) {
            return null;
        }
        var values = mainChunks.getProperties().get(property);
        if (values == null || values.isEmpty()) {
            return null;
        }
        var first = values.get(0);
        if (first instanceof PropertyValue.TimePropertyValue timeValue) {
            var calendar = timeValue.getValue();
            return calendar == null ? null : calendar.getTime();
        }
        return null;
    }

    private static String readMainString(MAPIMessage message, MAPIProperty property) {
        var mainChunks = message.getMainChunks();
        if (mainChunks == null) {
            return null;
        }
        return findStringChunk(mainChunks, property);
    }

    /**
     * Re-decodes the legacy ANSI strings of an MSG with the codepage the message actually declares,
     * fixing three blind spots in POI's {@link MAPIMessage#guess7BitEncoding()}:
     *
     * <ul>
     *   <li>POI deliberately discards a UTF-8 {@code PR_INTERNET_CPID} for the plain-text body (a
     *       quirky Outlook special case), leaving a genuinely-UTF-8 PT_STRING8 body decoded as the
     *       CP1252 default and mojibaked. {@code PidTagInternetCodepage} ([MS-OXPROPS]) is
     *       authoritative for the body even when it is 65001, so the body chunk is re-decoded with it.
     *   <li>POI's {@code set7BitEncoding} never visits the attachment chunks, so a non-Latin
     *       {@code PR_ATTACH_LONG_FILENAME} in an ANSI MSG keeps the CP1252 default. [MS-OXCMSG] §2.2.2:
     *       attachment name strings follow the message codepage, so they are re-decoded with it too.
     *   <li>POI decodes every other non-body PT_STRING8 chunk in the main store (Subject, display
     *       names, and named-property values such as PidLidLocation / PidLidEmail* that feed the
     *       iCal/vCard/DSN output) with {@code CodePageUtil.codepageToEncoding(PR_MESSAGE_CODEPAGE)} —
     *       the very call {@link #charsetForCodepage} overrides for the divergent code pages
     *       (1256/932/874/950). Without re-decoding them here, those fields keep POI's wrong charset
     *       even though the body is corrected. They are re-decoded with the corrected general charset —
     *       but only from {@code PR_MESSAGE_CODEPAGE}, exactly POI's source for these strings, never the
     *       {@code PR_INTERNET_CPID} body fallback (which would mis-decode a Subject when no message
     *       codepage is declared).
     *   <li>POI's {@code set7BitEncoding} never visits the recipient-table chunks either, so the To/Cc/Bcc
     *       {@code PR_DISPLAY_NAME}/{@code PR_EMAIL_ADDRESS} of an ANSI MSG keep POI's general charset —
     *       the very one {@link #charsetForCodepage} overrides for 1256/932/874/950. They are re-decoded
     *       with the corrected message codepage too, so recipient names match the Subject and the PST
     *       recipient-row decode instead of mojibaking.
     * </ul>
     *
     * Must run after {@code guess7BitEncoding()}. {@link StringChunk#set7BitEncoding} re-reads only
     * 7-bit (PT_STRING8) chunks from their immutable raw bytes, so Unicode properties are left
     * untouched and re-applying a charset is idempotent.
     */
    private static void applySourceCodepage(MAPIMessage message, ConversionLog log) {
        var mainChunks = message.getMainChunks();
        if (mainChunks == null) {
            return;
        }
        var bodyCharset =
                charsetForCodepage(readMainLong(message, MAPIProperty.INTERNET_CPID.id), "PR_INTERNET_CPID", log);
        // POI decodes the non-body main-store PT_STRING8 strings (Subject, named-property values) with
        // PR_MESSAGE_CODEPAGE alone — no INTERNET_CPID fallback — so the corrected re-decode below must
        // use the same source and run only when a message codepage is actually declared.
        var messageCodepageCharset =
                charsetForCodepage(readMainLong(message, MAPIProperty.MESSAGE_CODEPAGE.id), "PR_MESSAGE_CODEPAGE", log);
        // Attachment name strings follow the message codepage too; INTERNET_CPID is the documented
        // fallback there (preserves the prior behavior of the attachment loop).
        var attachmentCharset = messageCodepageCharset != null ? messageCodepageCharset : bodyCharset;

        if (bodyCharset != null) {
            var bodyChunks = mainChunks.getAll().get(MAPIProperty.BODY);
            if (bodyChunks != null) {
                for (var chunk : bodyChunks) {
                    if (chunk instanceof StringChunk bodyChunk) {
                        bodyChunk.set7BitEncoding(bodyCharset);
                    }
                }
            }
        }

        if (messageCodepageCharset != null) {
            // Re-decode the remaining non-body main-store PT_STRING8 strings with the corrected message
            // codepage, mirroring the scope of POI's set7BitEncoding. BODY/BODY_HTML are excluded so the
            // INTERNET_CPID body decode above stands.
            for (var entry : mainChunks.getAll().entrySet()) {
                int propertyId = entry.getKey().id;
                if (propertyId == MAPIProperty.BODY.id || propertyId == MAPIProperty.BODY_HTML.id) {
                    continue;
                }
                for (var chunk : entry.getValue()) {
                    if (chunk instanceof StringChunk stringChunk) {
                        stringChunk.set7BitEncoding(messageCodepageCharset);
                    }
                }
            }
        }

        if (attachmentCharset != null) {
            var attachments = message.getAttachmentFiles();
            if (attachments != null) {
                for (var attachment : attachments) {
                    for (var chunk : attachment.getAll()) {
                        if (chunk instanceof StringChunk stringChunk) {
                            stringChunk.set7BitEncoding(attachmentCharset);
                        }
                    }
                }
            }
        }

        if (messageCodepageCharset != null) {
            // POI's set7BitEncoding never re-decodes the recipient-table chunks, so re-decode the To/Cc/Bcc
            // PR_DISPLAY_NAME/PR_EMAIL_ADDRESS with the corrected message codepage — the same source and
            // scope POI used for them — keeping recipient names consistent with the Subject and with the
            // PST recipient-row decode for the 1256/932/874/950 code pages charsetForCodepage corrects.
            var recipients = message.getRecipientDetailsChunks();
            if (recipients != null) {
                for (var recipient : recipients) {
                    if (recipient == null) {
                        continue;
                    }
                    for (var chunk : recipient.getAll()) {
                        if (chunk instanceof StringChunk stringChunk) {
                            stringChunk.set7BitEncoding(messageCodepageCharset);
                        }
                    }
                }
            }
        }

        if (bodyCharset == null && attachmentCharset == null) {
            // Neither codepage property resolved: POI fell back to its CP1252 default for the
            // remaining PT_STRING8 strings. Surface it so a mojibaked legacy message is diagnosable.
            log.info("MSG declares no usable message codepage (PR_MESSAGE_CODEPAGE/PR_INTERNET_CPID); "
                    + "8-bit strings were decoded with the default Windows-1252 codepage");
        }
    }

    /** Maps a MAPI codepage id to a Java charset name via POI's table, or {@code null} when absent/unsupported. */
    private static String charsetForCodepage(Integer codepage, String source, ConversionLog log) {
        if (codepage == null) {
            return null;
        }
        if (codepage == 1256) {
            // POI 5.5.1 CodePageUtil.codepageToEncoding(1256, true) returns "Cp1255" (Hebrew) instead of
            // the Arabic charset — an isolated transcription typo in its javaLangFormat=true branch (the
            // false branch correctly yields "windows-1256"). Map 1256 explicitly so an Arabic ANSI MSG's
            // 8-bit body, attachment names and one-off strings are not decoded as windows-1255.
            return "windows-1256";
        }
        if (codepage == 932 || codepage == 874 || codepage == 950) {
            // POI's codepageToEncoding maps these Microsoft DBCS code pages to IBM-derived Java charsets
            // (932 -> "SJIS" = Shift_JIS, 874 -> "cp874" = x-IBM874, 950 -> "cp950" = x-IBM950), which
            // differ from the windows-* variants Outlook actually wrote in thousands of double-byte cells
            // (e.g. CP932 0x81 0x60 is U+FF5E FULLWIDTH TILDE but Shift_JIS yields U+301C WAVE DASH;
            // CP874 0x85 is U+2026 but x-IBM874 leaves it undefined). Pin them to the same Microsoft
            // charsets the pst-parser CodePages table uses so MSG and PST decode byte-identical ANSI
            // messages the same way instead of drifting; the IANA names are fallbacks for a stripped JRE.
            return switch (codepage) {
                case 932 -> firstSupportedCharsetName("windows-31j", "Shift_JIS");
                case 874 -> firstSupportedCharsetName("x-windows-874", "TIS-620");
                default -> firstSupportedCharsetName("x-windows-950", "Big5"); // 950
            };
        }
        try {
            return CodePageUtil.codepageToEncoding(codepage, true);
        } catch (UnsupportedEncodingException unsupported) {
            log.error("MSG " + source + " codepage " + codepage + " is not supported; "
                    + "its 8-bit strings were decoded with the default Windows-1252 codepage");
            return null;
        }
    }

    /**
     * First of the candidate charset names the running JRE supports, or {@code null} so the caller
     * falls back to the default windows-1252 decode. Mirrors pst-parser {@code CodePages.firstSupported}
     * so a stripped JRE missing a Microsoft charset degrades to the IANA variant rather than throwing.
     */
    private static String firstSupportedCharsetName(String... names) {
        for (var name : names) {
            if (Charset.isSupported(name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * The 8-bit charset for this message's legacy (non-Unicode) one-off strings — the code page
     * Outlook wrote reply-to entries and distribution-list member addresses in. Resolves
     * PR_MESSAGE_CODEPAGE, then PR_INTERNET_CPID, and falls back to windows-1252 ({@link #RTF_CHARSET})
     * when neither is present or supported, which preserves the previous behavior for messages that
     * declare no code page. Only matters where a one-off's MAE_UNICODE bit is clear; Unicode one-offs
     * are UTF-16LE regardless. Codepage diagnostics are left to {@link #applySourceCodepage} (which
     * already reports them for the ANSI messages this affects), so resolution here is silent.
     */
    private static Charset resolveMessageAnsiCharset(MAPIMessage message) {
        var name = charsetForCodepage(
                readMainLong(message, MAPIProperty.MESSAGE_CODEPAGE.id), "PR_MESSAGE_CODEPAGE", ConversionLog.NOOP);
        if (name == null) {
            name = charsetForCodepage(
                    readMainLong(message, MAPIProperty.INTERNET_CPID.id), "PR_INTERNET_CPID", ConversionLog.NOOP);
        }
        if (name != null) {
            try {
                return Charset.forName(name);
            } catch (RuntimeException ignored) {
                // An unsupported/illegal charset name from the codepage table — fall back to the default.
            }
        }
        return RTF_CHARSET;
    }

    private static String findStringChunk(Chunks mainChunks, MAPIProperty property) {
        var list = mainChunks.getAll().get(property);
        if (list == null || list.isEmpty()) {
            return null;
        }
        for (var chunk : list) {
            if (chunk instanceof StringChunk stringChunk) {
                return stringChunk.getValue();
            }
        }
        return null;
    }

    /**
     * The first string chunk stored under the given numeric property id, or {@code null}. Used for
     * properties POI has no {@link MAPIProperty} constant for: the chunk map's keys are POI-internal
     * instances, and {@code MAPIProperty} has no value-based {@code equals}/{@code hashCode}, so a
     * {@code createCustom} lookup key silently never matches — scanning by id is the only reliable
     * route.
     */
    private static String readMainStringById(MAPIMessage message, int propertyId) {
        var mainChunks = message.getMainChunks();
        if (mainChunks == null) {
            return null;
        }
        for (var entry : mainChunks.getAll().entrySet()) {
            if (entry.getKey().id != propertyId) {
                continue;
            }
            for (var chunk : entry.getValue()) {
                if (chunk instanceof StringChunk stringChunk) {
                    return stringChunk.getValue();
                }
            }
        }
        return null;
    }

    /**
     * The first numeric value of the given fixed-size property id, or {@code null}. Same
     * scan-by-id rationale as {@link #readMainStringById}.
     */
    private static Integer readMainLong(MAPIMessage message, int propertyId) {
        var mainChunks = message.getMainChunks();
        if (mainChunks == null) {
            return null;
        }
        for (var entry : mainChunks.getProperties().entrySet()) {
            if (entry.getKey().id != propertyId) {
                continue;
            }
            var values = entry.getValue();
            if (values != null && !values.isEmpty() && values.get(0).getValue() instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
    }

    /**
     * RFC 5546 §3.2.3: a meeting-response REPLY is addressed to the meeting ORGANIZER, which Outlook
     * stores as the response's primary (To) recipient. Prefer the first resolvable To-type recipient
     * over the first recipient row, so a Cc'd delegate on the response is not promoted to ORGANIZER.
     * Falls back to the first attendee when no To recipient carries an address.
     */
    private static ICalendarGenerator.Attendee pickReplyMeetingOrganizer(
            RecipientChunks[] details, List<ICalendarGenerator.Attendee> fallback) {
        if (details != null) {
            var limit = Math.min(details.length, MAX_RECIPIENTS);
            for (var index = 0; index < limit; index++) {
                var type = readRecipientType(details[index]);
                if (type != null && type == EmlSerializer.RECIPIENT_TYPE_TO) {
                    var address = resolveRecipientAddress(details[index]);
                    if (address != null && !address.isBlank()) {
                        return new ICalendarGenerator.Attendee(details[index].getRecipientName(), address);
                    }
                }
            }
        }
        return fallback.get(0);
    }

    private static Integer readRecipientType(RecipientChunks chunks) {
        var map = chunks.getProperties();
        if (map == null) {
            return null;
        }
        var values = map.get(MAPIProperty.RECIPIENT_TYPE);
        if (values == null || values.isEmpty()) {
            return null;
        }
        var first = values.get(0);
        Integer rawType;
        if (first instanceof PropertyValue.LongPropertyValue longValue) {
            var value = longValue.getValue();
            rawType = value == null ? null : value.intValue();
        } else if (first.getValue() instanceof Number number) {
            rawType = number.intValue();
        } else {
            try {
                rawType = Integer.parseInt(Objects.toString(first.getValue(), ""));
            } catch (NumberFormatException ignored) {
                rawType = null;
            }
        }
        // [MS-OXOMSG] §2.2.3.1: only the low bits carry the recipient class (MAPI_TO/CC/BCC); the high
        // bits are flags (e.g. 0x10000000 "already processed") that Exchange/transport set on resent and
        // saved sent/received items. Mask them off — otherwise a flagged "To" (0x10000001) matches none
        // of TO/CC/BCC and the row falls through to the PR_DISPLAY_TO/CC/BCC string fallback, dropping the
        // recipient's SMTP address and collapsing the To/Cc/Bcc split.
        return rawType == null ? null : rawType & 0x0FFFFFFF;
    }
}
