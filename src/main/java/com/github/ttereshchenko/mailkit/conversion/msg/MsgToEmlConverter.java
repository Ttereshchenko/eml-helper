package com.github.ttereshchenko.mailkit.conversion.msg;

import com.github.ttereshchenko.mailkit.conversion.ConversionConsoleService;
import com.github.ttereshchenko.mailkit.conversion.ConversionException;
import com.github.ttereshchenko.mailkit.conversion.EmlSerializer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertyValue;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.hsmf.datatypes.Types;
import org.apache.poi.hsmf.exceptions.ChunkNotFoundException;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

public final class MsgToEmlConverter {

    // Kept in step with PstToEmlConverter.MAX_EMBEDDED_DEPTH so both pipelines truncate alike.
    private static final int MAX_EMBEDDED_DEPTH = 10;

    private MsgToEmlConverter() {}

    public static void convert(InputStream msgStream, OutputStream outStream, ConversionConsoleService console)
            throws ConversionException {
        Objects.requireNonNull(msgStream, "msgStream");
        Objects.requireNonNull(outStream, "outStream");
        try (var message = new MAPIMessage(msgStream)) {
            // UTF-8 (matching the PST path): a stored transport-header block may legitimately carry
            // non-ASCII bytes. A US-ASCII encoder with CodingErrorAction.REPORT aborts the whole
            // conversion on the first such byte, which is a common real-world failure.
            var writer = new OutputStreamWriter(outStream, StandardCharsets.UTF_8);
            convert(message, 0, writer, console);
            writer.flush();
        } catch (ChunkNotFoundException | IOException | RuntimeException failure) {
            // POI surfaces malformed input as its own checked ChunkNotFoundException, various unchecked
            // exceptions (e.g. RecordFormatException), and parse-level IOExceptions (NotOLE2FileException).
            // Collapse them into one domain type so the conversion API has a clean exception boundary.
            var detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            throw new ConversionException("Failed to convert MSG to EML: " + detail, failure);
        }
    }

    static void convert(MAPIMessage message, int depth, Writer writer, ConversionConsoleService console)
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

        var details = message.getRecipientDetailsChunks();
        if (details != null && details.length > 2048) {
            throw new IOException("Recipient limit exceeded (max 2048)");
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

        var sclProp = MAPIProperty.createCustom(0x4076, Types.LONG, "SPAM_CONFIDENCE_LEVEL");
        var sclChunks = message.getMainChunks().getProperties().get(sclProp);
        if (sclChunks != null && !sclChunks.isEmpty()) {
            var val = sclChunks.get(0).getValue();
            if (val instanceof Number n) {
                serializer.setScl(n.intValue());
            }
        }

        populateBodies(message, serializer, console);
        // An appointment's subject/body/date are exported as a normal email above. We deliberately do
        // NOT synthesize an invite.ics here: POI/HSMF exposes no reliable named-property API to read the
        // appointment's start/end/location (PidLidAppointmentStartWhole etc.), so the only invite we
        // could build would carry placeholder DTSTART/DTEND ("now") and no LOCATION — a structurally
        // valid but semantically wrong calendar entry. Emitting nothing is more honest than emitting that.

        populateAttachments(message, depth, serializer, console);

        serializer.writeTo(writer);
    }

    private static void populateBodies(
            MAPIMessage message, EmlSerializer serializer, ConversionConsoleService console) {
        boolean hasPlain = false;
        boolean hasHtml = false;
        try {
            var text = message.getTextBody();
            if (text != null && !text.isEmpty()) {
                serializer.addBody(text, "text/plain; charset=UTF-8");
                hasPlain = true;
            }
        } catch (ChunkNotFoundException ignored) {
        }
        try {
            var html = message.getHtmlBody();
            if (html != null && !html.isEmpty()) {
                serializer.addBody(html, "text/html; charset=UTF-8");
                hasHtml = true;
            }
        } catch (ChunkNotFoundException ignored) {
        }
        try {
            var rtfText = message.getRtfBody();
            if (rtfText != null && !rtfText.isEmpty()) {
                serializer.addBody(rtfText, "text/rtf; charset=UTF-8");
                if (RtfStripper.isHtmlEncapsulated(rtfText)) {
                    if (!hasHtml) {
                        var recovered = RtfStripper.deEncapsulateHtml(rtfText);
                        if (!recovered.isBlank()) {
                            serializer.addBody(recovered, "text/html; charset=UTF-8");
                        }
                    }
                } else if (!hasPlain) {
                    var stripped = RtfStripper.strip(rtfText);
                    if (!stripped.isEmpty()) {
                        serializer.addBody(stripped, "text/plain; charset=UTF-8");
                    }
                }
            }
        } catch (ChunkNotFoundException ignored) {
        }
    }

    private static void populateAttachments(
            MAPIMessage message, int depth, EmlSerializer serializer, ConversionConsoleService console)
            throws IOException {
        var raw = message.getAttachmentFiles();
        if (raw == null || raw.length == 0) {
            return;
        }
        for (var chunks : raw) {
            if (chunks.isEmbeddedMessage()) {
                var embedded = chunks.getEmbeddedMessage();
                String nestedEml;
                try {
                    var stringWriter = new StringWriter();
                    convert(embedded, depth + 1, stringWriter, console);
                    nestedEml = stringWriter.toString();
                } catch (Exception failure) {
                    if (console != null) {
                        console.info(
                                ConversionConsoleService.Tab.MSG,
                                "Failed to convert embedded message: " + failure.getMessage());
                    }
                    nestedEml = "Subject: Error converting nested message\r\n\r\n" + failure.getMessage();
                }
                var subject = safeString(safeSubject(embedded));
                var filename = EmlSerializer.sanitizeFilename(subject.isBlank() ? "embedded" : subject) + ".eml";
                if (console != null)
                    console.info(ConversionConsoleService.Tab.MSG, "Found embedded message attachment: " + filename);
                serializer.addEmbeddedMessage(filename, nestedEml);
            } else {
                var bytes = attachmentBytes(chunks);
                var filename = pickFilename(chunks);
                var mime = pickMimeType(chunks);
                var contentId = pickContentId(chunks);
                boolean isInline = contentId != null;

                if (console != null)
                    console.info(ConversionConsoleService.Tab.MSG, "Found attachment: " + filename + " (" + mime + ")");
                serializer.addAttachment(filename, mime, bytes, contentId, isInline);
            }
        }
    }

    private static byte[] attachmentBytes(AttachmentChunks chunks) {
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
            } catch (Exception ignored) {
            }
        }
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
        var senderSmtpAddress = MAPIProperty.createCustom(0x5D01, Types.ASCII_STRING, "SENDER_SMTP_ADDRESS");
        var sentRepresentingSmtpAddress =
                MAPIProperty.createCustom(0x5D02, Types.ASCII_STRING, "SENT_REPRESENTING_SMTP_ADDRESS");

        var email = readMainString(message, senderSmtpAddress);
        if (email == null || email.isBlank()) {
            email = readMainString(message, sentRepresentingSmtpAddress);
        }
        if (email == null || email.isBlank()) {
            email = readMainString(message, MAPIProperty.SENDER_EMAIL_ADDRESS);
            if (email != null && !email.isBlank()) {
                String addrType = readMainString(message, MAPIProperty.SENDER_ADDRTYPE);
                email = EmlSerializer.imceaEncapsulate(addrType, email);
            }
        }
        if (email == null || email.isBlank()) {
            email = readMainString(message, MAPIProperty.SENT_REPRESENTING_EMAIL_ADDRESS);
            if (email != null && !email.isBlank()) {
                String addrType = readMainString(message, MAPIProperty.SENT_REPRESENTING_ADDRTYPE);
                email = EmlSerializer.imceaEncapsulate(addrType, email);
            }
        }
        return email;
    }

    private static void populateRecipients(MAPIMessage message, int wantedType, EmlSerializer serializer) {
        var details = message.getRecipientDetailsChunks();
        if (details != null && details.length > 0) {
            boolean found = false;
            for (var chunks : details) {
                var type = readRecipientType(chunks);
                if (type != null && type == wantedType) {
                    String address = chunks.getRecipientEmailAddress();

                    var smtpAddressProp = MAPIProperty.createCustom(0x39FE, Types.ASCII_STRING, "SMTP_ADDRESS");
                    var chunkList = chunks.getProperties().get(smtpAddressProp);
                    String smtpAddress = null;
                    if (chunkList != null && !chunkList.isEmpty()) {
                        var val = chunkList.get(0).getValue();
                        if (val instanceof String s) smtpAddress = s;
                    }

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
                    if (!part.isEmpty()) {
                        serializer.addRecipient(wantedType, part, part);
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
