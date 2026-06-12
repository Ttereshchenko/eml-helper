package com.github.ttereshchenko.mailkit.conversion.msg;

import com.github.ttereshchenko.mailkit.conversion.ConversionException;
import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.conversion.EmlSerializer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertyValue;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.hsmf.exceptions.ChunkNotFoundException;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

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

    // A genuine (non-HTML-encapsulated) RTF body is preserved as a body.rtf attachment in this
    // charset — the LzFu decode POI performs is a lossless windows-1252 round-trip. Mirrors the PST
    // converter's body.rtf export.
    private static final Charset RTF_CHARSET = Charset.forName("windows-1252");

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
        // UTF-8 (matching the PST path): a stored transport-header block may legitimately carry
        // non-ASCII bytes. A US-ASCII encoder with CodingErrorAction.REPORT aborts the whole
        // conversion on the first such byte, which is a common real-world failure.
        var writer = new OutputStreamWriter(outStream, StandardCharsets.UTF_8);
        convert(message, 0, writer, log);
        writer.flush();
    }

    private static ConversionException wrap(Exception failure) {
        // POI surfaces malformed input as its own checked ChunkNotFoundException, various unchecked
        // exceptions (e.g. RecordFormatException), and parse-level IOExceptions (NotOLE2FileException).
        // Collapse them into one domain type so the conversion API has a clean exception boundary.
        var detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return new ConversionException("Failed to convert MSG to EML: " + detail, failure);
    }

    static void convert(MAPIMessage message, int depth, Writer writer, ConversionLog log)
            throws IOException, ChunkNotFoundException {
        if (depth > MAX_EMBEDDED_DEPTH) {
            // Mirror the PST converter: emit a stub rather than throwing, so an over-deep nested
            // message truncates gracefully instead of failing the entire parent conversion.
            var stub = new EmlSerializer();
            stub.setSubject("Nested Message Limit Exceeded");
            stub.addBody("The maximum nested message depth was reached.", "text/plain; charset=UTF-8");
            stub.writeTo(writer);
            return;
        }
        message.setReturnNullOnMissingChunk(true);
        if (message.has7BitEncodingStrings()) {
            // Legacy ANSI MSG: string chunks are stored as 8-bit bytes whose codepage lives in
            // PR_MESSAGE_CODEPAGE / PR_INTERNET_CPID / the headers charset. Without this detection POI
            // decodes every string as windows-1252, mojibaking Cyrillic/CJK subjects and bodies.
            message.guess7BitEncoding();
        }

        var details = message.getRecipientDetailsChunks();
        if (details != null && details.length > MAX_RECIPIENTS) {
            log.error("Message has " + details.length + " recipients; exporting only the first " + MAX_RECIPIENTS);
        }

        var serializer = new EmlSerializer();

        var transportHeaders = readMainString(message, MAPIProperty.TRANSPORT_MESSAGE_HEADERS);
        if (transportHeaders != null && !transportHeaders.isBlank()) {
            serializer.setTransportHeaders(transportHeaders);
        }

        serializer.setSender(resolveSenderName(message), resolveSenderEmail(message));
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
        var inReplyTo = readMainString(message, MAPIProperty.IN_REPLY_TO_ID);
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            serializer.addCustomHeader("In-Reply-To", inReplyTo.trim());
        }
        var references = readMainString(message, MAPIProperty.INTERNET_REFERENCES);
        if (references != null && !references.isBlank()) {
            serializer.addCustomHeader("References", references.trim());
        }

        // PidTagContentFilterSpamConfidenceLevel. POI has no constant for it, and a
        // MAPIProperty.createCustom lookup key can never match a map entry (MAPIProperty does not
        // override equals/hashCode), so the property table is scanned by numeric id instead.
        var spamConfidenceLevel = readMainLong(message, 0x4076);
        if (spamConfidenceLevel != null) {
            serializer.setScl(spamConfidenceLevel);
        }

        populateBodies(message, serializer, log);
        // An appointment's subject/body/date are exported as a normal email above. We deliberately do
        // NOT synthesize an invite.ics here: POI/HSMF exposes no reliable named-property API to read the
        // appointment's start/end/location (PidLidAppointmentStartWhole etc.), so the only invite we
        // could build would carry placeholder DTSTART/DTEND ("now") and no LOCATION — a structurally
        // valid but semantically wrong calendar entry. Emitting nothing is more honest than emitting that.

        populateAttachments(message, depth, serializer, log);

        serializer.writeTo(writer);
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
            serializer.addBody(html, "text/html; charset=UTF-8");
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
                serializer.addBody(recovered, "text/html; charset=UTF-8");
            } else {
                serializer.addAttachment("body.rtf", "application/rtf", rtfText.getBytes(RTF_CHARSET), null, false);
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
        serializer.addAttachment("body.rtf", "application/rtf", rtfText.getBytes(RTF_CHARSET), null, false);
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

    private static void populateAttachments(MAPIMessage message, int depth, EmlSerializer serializer, ConversionLog log)
            throws IOException {
        var raw = message.getAttachmentFiles();
        if (raw == null || raw.length == 0) {
            return;
        }
        var usedEmbeddedNames = new HashSet<String>();
        for (var chunks : raw) {
            if (chunks.isEmbeddedMessage()) {
                var embedded = chunks.getEmbeddedMessage();
                String nestedEml;
                try {
                    var stringWriter = new StringWriter();
                    convert(embedded, depth + 1, stringWriter, log);
                    nestedEml = stringWriter.toString();
                } catch (Exception failure) {
                    log.error("Failed to convert embedded message: " + failure.getMessage());
                    nestedEml = "Subject: Error converting nested message\r\n\r\n" + failure.getMessage();
                }
                var subject = safeString(safeSubject(embedded));
                var filename = uniqueEmbeddedName(subject.isBlank() ? "embedded" : subject, usedEmbeddedNames);
                log.info("Found embedded message attachment: " + filename);
                serializer.addEmbeddedMessage(filename, nestedEml);
            } else {
                var bytes = attachmentBytes(chunks, log);
                var filename = pickFilename(chunks);
                var mime = pickMimeType(chunks);
                var contentId = pickContentId(chunks);
                boolean isInline = contentId != null;

                log.info("Found attachment: " + filename + " (" + mime + ")");
                serializer.addAttachment(filename, mime, bytes, contentId, isInline);
            }
        }
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
            return mime;
        }
        return "application/octet-stream";
    }

    private static String chunkValue(StringChunk chunk) {
        return chunk == null ? null : chunk.getValue();
    }

    private static String pickContentId(AttachmentChunks chunks) {
        var contentId = chunkValue(chunks.getAttachContentId());
        if (contentId == null || contentId.isBlank()) {
            return null;
        }
        var trimmed = contentId.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String resolveSenderName(MAPIMessage message) {
        String name = null;
        try {
            name = message.getDisplayFrom();
        } catch (ChunkNotFoundException ignored) {
            // optional
        }
        if (name == null || name.isBlank()) {
            name = readMainString(message, MAPIProperty.SENDER_NAME);
        }
        return name;
    }

    private static String resolveSenderEmail(MAPIMessage message) {
        // PidTagSenderSmtpAddress (0x5D01) / PidTagSentRepresentingSmtpAddress (0x5D02). POI has no
        // constants for these, and a MAPIProperty.createCustom lookup key can never match a map entry
        // (MAPIProperty does not override equals/hashCode), so the chunk map is scanned by numeric
        // property id instead.
        var email = readMainStringById(message, 0x5D01);
        if (email == null || email.isBlank()) {
            email = readMainStringById(message, 0x5D02);
        }
        if (email == null || email.isBlank()) {
            email = readMainString(message, MAPIProperty.SENDER_EMAIL_ADDRESS);
            if (email != null && !email.isBlank()) {
                var addrType = readMainString(message, MAPIProperty.SENDER_ADDRTYPE);
                email = EmlSerializer.imceaEncapsulate(addrType, email);
            }
        }
        if (email == null || email.isBlank()) {
            email = readMainString(message, MAPIProperty.SENT_REPRESENTING_EMAIL_ADDRESS);
            if (email != null && !email.isBlank()) {
                var addrType = readMainString(message, MAPIProperty.SENT_REPRESENTING_ADDRTYPE);
                email = EmlSerializer.imceaEncapsulate(addrType, email);
            }
        }
        return email;
    }

    private static void populateRecipients(MAPIMessage message, int wantedType, EmlSerializer serializer) {
        var details = message.getRecipientDetailsChunks();
        if (details != null && details.length > 0) {
            boolean found = false;
            var limit = Math.min(details.length, MAX_RECIPIENTS);
            for (var index = 0; index < limit; index++) {
                var chunks = details[index];
                var type = readRecipientType(chunks);
                if (type != null && type == wantedType) {
                    String address = chunks.getRecipientEmailAddress();

                    // PR_SMTP_ADDRESS, exposed by POI as its own chunk. (A map lookup with a
                    // MAPIProperty.createCustom key can never match — no equals/hashCode — and
                    // getRecipientEmailAddress already prefers this chunk; reading it explicitly
                    // keeps the SMTP-vs-encapsulation decision below visible.)
                    var smtpChunk = chunks.getRecipientSMTPChunk();
                    var smtpAddress = smtpChunk == null ? null : smtpChunk.getValue();

                    if (smtpAddress != null && !smtpAddress.isBlank()) {
                        address = smtpAddress;
                    } else if (address != null) {
                        String addrType = chunkValue(chunks.getDeliveryTypeChunk());
                        address = EmlSerializer.imceaEncapsulate(addrType, address);
                    }
                    serializer.addRecipient(wantedType, chunks.getRecipientName(), address);
                    found = true;
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
        var deliveryDate = readTimeProperty(message, MAPIProperty.MESSAGE_DELIVERY_TIME);
        if (deliveryDate != null) {
            return deliveryDate;
        }
        try {
            var calendar = message.getMessageDate();
            return calendar == null ? null : calendar.getTime();
        } catch (ChunkNotFoundException ignored) {
            return null;
        }
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
        if (first instanceof PropertyValue.LongPropertyValue longValue) {
            var raw = longValue.getValue();
            return raw == null ? null : raw.intValue();
        }
        var raw = first.getValue();
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(Objects.toString(raw, ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
