package com.github.ttereshchenko.mailkit.conversion.msg;

import com.github.ttereshchenko.mailkit.conversion.AppointmentRecurrence;
import com.github.ttereshchenko.mailkit.conversion.ConversionException;
import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import com.github.ttereshchenko.mailkit.conversion.EmlSerializer;
import com.github.ttereshchenko.mailkit.conversion.HtmlMetaCharset;
import com.github.ttereshchenko.mailkit.conversion.ICalendarGenerator;
import com.github.ttereshchenko.mailkit.conversion.RtfStripper;
import com.github.ttereshchenko.mailkit.conversion.VCardGenerator;
import com.github.ttereshchenko.mailkit.conversion.WindowsTimeZone;
import com.github.ttereshchenko.mailkit.pst.Message;
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

    // Named-property sets ([MS-OXPROPS] §1.3.2) resolved through the message's __nameid mapping.
    private static final ClassID PSETID_APPOINTMENT = new ClassID("{00062002-0000-0000-C000-000000000046}");
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

        populateMapiHeaders(message, serializer, from);

        var messageClass = readMessageClass(message);
        if (messageClass != null
                && messageClass.startsWith("IPM.Note.SMIME")
                && hoistSmimeEntity(message, serializer, log)) {
            // [MS-OXOSMIME] §2.2.1: the single attachment IS the original signed/encrypted MIME
            // entity, so it becomes the message's own top-level entity and the signature stays
            // verifiable. Bodies and other content are skipped — they live inside the hoisted entity.
            serializer.writeTo(writer);
            return;
        }

        populateBodies(message, serializer, log);
        populateCalendarInvite(message, messageClass, from, serializer, log);
        populateContactCard(message, messageClass, serializer);
        populateTaskTodo(message, messageClass, serializer, log);

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
            if (isEmbeddedMessageAttachment(chunks)) {
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
    private static void populateMapiHeaders(MAPIMessage message, EmlSerializer serializer, MailboxIdentity from) {
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
                    replyEntries, replyNames, RTF_CHARSET, Message.AddressPreference.PREFER_SMTP)) {
                var formatted = EmlSerializer.formatAddress(recipient.name, recipient.email);
                if (!formatted.isBlank()) {
                    replyTo.add(formatted);
                }
            }
            if (!replyTo.isEmpty()) {
                serializer.addCustomHeader("Reply-To", String.join(", ", replyTo));
            }
        }
        var keywords = readNamedStrings(message, PS_PUBLIC_STRINGS, "Keywords", 0); // PidNameKeywords
        if (!keywords.isEmpty()) {
            serializer.addCustomHeader("Keywords", String.join(", ", keywords));
        }
        // PR_READ_RECEIPT_REQUESTED -> Disposition-Notification-To (RFC 8098), addressed to the author.
        if (Boolean.TRUE.equals(readMainBoolean(message, 0x0029)) && from.hasEmail()) {
            serializer.addCustomHeader(
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
     * makes the signature unverifiable. Returns {@code false} (the caller falls back to the regular
     * pipeline) when the message does not consist of exactly one data-bearing attachment.
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
        var data = dataChunk.getValue();
        // ISO-8859-1 maps bytes 1:1 to chars, so a 7bit-canonicalized envelope (the S/MIME norm,
        // RFC 8551 §3.1.1) round-trips byte-identically through the UTF-8 output writer.
        var entity = new String(data, StandardCharsets.ISO_8859_1);
        var headerEnd = entityHeaderEnd(entity);
        if (headerEnd > 0) {
            var headerBlock = entity.substring(0, headerEnd);
            var contentType = entityHeaderValue(headerBlock, "Content-Type");
            if (contentType != null && !contentType.isBlank()) {
                var transferEncoding = entityHeaderValue(headerBlock, "Content-Transfer-Encoding");
                var bodyStart = headerEnd + (entity.startsWith("\r\n\r\n", headerEnd) ? 4 : 2);
                var body = normalizeToCrlf(entity.substring(Math.min(bodyStart, entity.length())));
                serializer.setRawEntity(contentType, transferEncoding, null, body);
                log.info("S/MIME message: hoisted the stored MIME entity (" + contentType + ")");
                return true;
            }
        }
        // No parseable entity headers: an opaque PKCS#7 blob becomes the message's own entity.
        var filename = pickFilename(chunks);
        filename = EmlSerializer.sanitizeFilename(filename == null || filename.isBlank() ? "smime.p7m" : filename);
        var mimeTag = chunkValue(chunks.getAttachMimeTag());
        var contentType = (mimeTag == null || mimeTag.isBlank() ? "application/pkcs7-mime" : mimeTag.trim())
                + "; name=\"" + filename + "\"";
        serializer.setRawEntity(
                contentType,
                "base64",
                "attachment; filename=\"" + filename + "\"",
                EmlSerializer.encodeBase64Wrapped(data));
        log.info("S/MIME message: exported the stored PKCS#7 envelope as the message body (" + contentType + ")");
        return true;
    }

    /** The end offset (exclusive) of a leading RFC 5322 header block, or -1 when the data has none. */
    private static int entityHeaderEnd(String entity) {
        var firstLineEnd = entity.indexOf('\n');
        if (firstLineEnd < 0) {
            return -1;
        }
        var firstLine = entity.substring(0, firstLineEnd).stripTrailing();
        var colon = firstLine.indexOf(':');
        if (colon <= 0) {
            return -1;
        }
        for (var index = 0; index < colon; index++) {
            var character = firstLine.charAt(index);
            if (character <= ' ' || character > '~') {
                return -1; // not a printable-ASCII header field name: raw binary, not a MIME entity
            }
        }
        var crlfEnd = entity.indexOf("\r\n\r\n");
        var lfEnd = entity.indexOf("\n\n");
        if (crlfEnd < 0) {
            return lfEnd;
        }
        return lfEnd < 0 ? crlfEnd : Math.min(crlfEnd, lfEnd);
    }

    /** The unfolded value of the named header inside a raw header block, or {@code null}. */
    private static String entityHeaderValue(String headerBlock, String name) {
        var unfolded = headerBlock.replace("\r\n", "\n").replaceAll("\n[ \t]+", " ");
        for (var line : unfolded.split("\n")) {
            var colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static String normalizeToCrlf(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
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
        var attendees = new ArrayList<ICalendarGenerator.Attendee>();
        var details = message.getRecipientDetailsChunks();
        if (details != null) {
            var limit = Math.min(details.length, MAX_RECIPIENTS);
            for (var index = 0; index < limit; index++) {
                var address = resolveRecipientAddress(details[index]);
                if (address != null && !address.isBlank()) {
                    attendees.add(new ICalendarGenerator.Attendee(details[index].getRecipientName(), address));
                }
            }
        }
        var method = ICalendarGenerator.method(messageClass, !attendees.isEmpty());
        var ical = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                method,
                start,
                end,
                location,
                safeString(safeSubject(message)),
                organizer.name(),
                organizer.email(),
                readBody(message::getTextBody, "plain text", log),
                attendees,
                allDay,
                timeZone,
                recurrence));
        serializer.addAttachment(
                "invite.ics",
                "text/calendar; charset=UTF-8; method=" + method,
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

    /** A VTODO for an {@code IPM.Task} item: start/due dates, completion state and percent complete. */
    private static void populateTaskTodo(
            MAPIMessage message, String messageClass, EmlSerializer serializer, ConversionLog log) {
        if (messageClass == null || !messageClass.startsWith("IPM.Task")) {
            return;
        }
        var start = readNamedTime(message, PSETID_TASK, 0x8104); // PidLidTaskStartDate
        var due = readNamedTime(message, PSETID_TASK, 0x8105); // PidLidTaskDueDate
        var percent = readNamedDouble(message, PSETID_TASK, 0x8102); // PidLidPercentComplete
        var complete = readNamedBoolean(message, PSETID_TASK, 0x811C); // PidLidTaskComplete
        var todo = ICalendarGenerator.generateTodo(
                safeString(safeSubject(message)),
                readBody(message::getTextBody, "plain text", log),
                start,
                due,
                percent,
                complete);
        serializer.addAttachment(
                "task.ics",
                "text/calendar; charset=UTF-8; method=PUBLISH",
                todo.getBytes(StandardCharsets.UTF_8),
                null,
                false);
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
     * IMCEA-encapsulated PR_EMAIL_ADDRESS, or {@code null} when the entry stores no address at all.
     */
    private static String resolveRecipientAddress(RecipientChunks chunks) {
        var smtpChunk = chunks.getRecipientSMTPChunk();
        var smtpAddress = smtpChunk == null ? null : smtpChunk.getValue();
        if (smtpAddress != null && !smtpAddress.isBlank()) {
            return smtpAddress;
        }
        var address = chunks.getRecipientEmailAddress();
        if (address == null) {
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
                var type = readRecipientType(chunks);
                if (type != null && type == wantedType) {
                    serializer.addRecipient(wantedType, chunks.getRecipientName(), resolveRecipientAddress(chunks));
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
