package com.github.ttereshchenko.mailkit.conversion.msg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.apache.poi.hsmf.MAPIMessage;
import org.apache.poi.hsmf.datatypes.AttachmentChunks;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.apache.poi.hsmf.datatypes.MAPIProperty;
import org.apache.poi.hsmf.datatypes.PropertyValue;
import org.apache.poi.hsmf.datatypes.RecipientChunks;
import org.apache.poi.hsmf.datatypes.StringChunk;
import org.apache.poi.hsmf.exceptions.ChunkNotFoundException;

public final class MsgToEmlConverter {

    private static final String CRLF = "\r\n";
    private static final int MAX_EMBEDDED_DEPTH = 16;
    private static final int RECIPIENT_TYPE_TO = 1;
    private static final int RECIPIENT_TYPE_CC = 2;
    private static final int RECIPIENT_TYPE_BCC = 3;

    private MsgToEmlConverter() {}

    public static void convert(InputStream msgStream, java.io.OutputStream outStream)
            throws IOException, ChunkNotFoundException {
        Objects.requireNonNull(msgStream, "msgStream");
        Objects.requireNonNull(outStream, "outStream");
        try (var message = new MAPIMessage(msgStream)) {
            var encoder = StandardCharsets.US_ASCII
                    .newEncoder()
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
            var writer = new java.io.OutputStreamWriter(outStream, encoder);
            convert(message, 0, writer);
            writer.flush();
        }
    }

    static void convert(MAPIMessage message, int depth, java.io.Writer writer)
            throws IOException, ChunkNotFoundException {
        if (depth > MAX_EMBEDDED_DEPTH) {
            throw new IOException("embedded message depth exceeded");
        }
        message.setReturnNullOnMissingChunk(true);

        var bodies = extractBodies(message);
        var attachments = collectAttachments(message, depth);

        var transportHeaders = readMainString(message, MAPIProperty.TRANSPORT_MESSAGE_HEADERS);
        if (transportHeaders != null && !transportHeaders.isBlank()) {
            writeFilteredTransportHeaders(writer, transportHeaders);
        } else {
            var synthesized = new java.util.ArrayList<String>();

            var from = resolveSender(message);
            if (from != null && !from.isBlank()) synthesized.add("From");
            appendHeader(writer, "From", from);

            var toAddress = joinRecipients(message, RECIPIENT_TYPE_TO);
            if (toAddress != null && !toAddress.isBlank()) synthesized.add("To");
            appendHeader(writer, "To", toAddress);

            var ccAddress = joinRecipients(message, RECIPIENT_TYPE_CC);
            if (ccAddress != null && !ccAddress.isBlank()) synthesized.add("Cc");
            appendHeader(writer, "Cc", ccAddress);

            var bcc = joinRecipients(message, RECIPIENT_TYPE_BCC);
            if (bcc != null && !bcc.isBlank()) synthesized.add("Bcc");
            appendHeader(writer, "Bcc", bcc);

            var subject = encodeHeaderIfNeeded(safeString(safeSubject(message)));
            if (subject != null && !subject.isBlank()) synthesized.add("Subject");
            appendHeader(writer, "Subject", subject);

            var date = safeDate(message);
            if (date != null) {
                synthesized.add("Date");
                appendHeader(writer, "Date", formatRfc2822Date(date));
            }

            var messageId = readMainString(message, MAPIProperty.INTERNET_MESSAGE_ID);
            if (messageId != null && !messageId.isBlank()) {
                synthesized.add("Message-ID");
                appendHeader(writer, "Message-ID", messageId.trim());
            }

            if (!synthesized.isEmpty()) {
                appendHeader(writer, "X-MailKit-Synthesized-Headers", String.join(", ", synthesized));
            }

            writer.append("MIME-Version: 1.0").append(CRLF);
        }

        if (attachments.isEmpty() && bodies.size() == 1) {
            var body = bodies.getFirst();
            writer.append("Content-Type: ").append(body.contentType()).append(CRLF);
            writer.append("Content-Transfer-Encoding: base64").append(CRLF);
            writer.append(CRLF);
            writer.append(encodeBase64Wrapped(body.utf8Bytes()));
            return;
        }

        var rootBoundary = "MAILKIT_" + UUID.randomUUID().toString().replace("-", "");
        writer.append("Content-Type: multipart/mixed; boundary=\"")
                .append(rootBoundary)
                .append('"')
                .append(CRLF);
        writer.append(CRLF);

        if (!bodies.isEmpty()) {
            appendBoundary(writer, rootBoundary, false);
            if (bodies.size() == 1) {
                var body = bodies.getFirst();
                writer.append("Content-Type: ").append(body.contentType()).append(CRLF);
                writer.append("Content-Transfer-Encoding: base64").append(CRLF);
                writer.append(CRLF);
                var encodedBody = encodeBase64Wrapped(body.utf8Bytes());
                writer.append(encodedBody);
                if (!encodedBody.endsWith(CRLF)) {
                    writer.append(CRLF);
                }
            } else {
                var altBoundary = "MAILKIT_ALT_" + UUID.randomUUID().toString().replace("-", "");
                writer.append("Content-Type: multipart/alternative; boundary=\"")
                        .append(altBoundary)
                        .append('"')
                        .append(CRLF);
                writer.append(CRLF);
                for (var body : bodies) {
                    appendBoundary(writer, altBoundary, false);
                    writer.append("Content-Type: ").append(body.contentType()).append(CRLF);
                    writer.append("Content-Transfer-Encoding: base64").append(CRLF);
                    writer.append(CRLF);
                    var encodedBody = encodeBase64Wrapped(body.utf8Bytes());
                    writer.append(encodedBody);
                    if (!encodedBody.endsWith(CRLF)) {
                        writer.append(CRLF);
                    }
                }
                appendBoundary(writer, altBoundary, true);
            }
        }

        for (var part : attachments) {
            appendBoundary(writer, rootBoundary, false);
            writer.append(part.headers());
            writer.append(CRLF);
            writer.append(part.encodedBody());
            if (!part.encodedBody().endsWith(CRLF)) {
                writer.append(CRLF);
            }
        }
        appendBoundary(writer, rootBoundary, true);
    }

    private static void writeFilteredTransportHeaders(java.io.Writer writer, String transportHeaders)
            throws IOException {
        var lines = transportHeaders.split("\\r?\\n");
        String currentHeader = null;
        for (var line : lines) {
            if (line.isEmpty()) {
                continue; // Skip empty lines in header block
            }
            if (Character.isWhitespace(line.charAt(0))) {
                if (currentHeader != null && !isFilteredHeader(currentHeader)) {
                    writer.append(line).append(CRLF);
                }
            } else {
                var colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    currentHeader = line.substring(0, colonIndex).trim();
                    if (!isFilteredHeader(currentHeader)) {
                        writer.append(line).append(CRLF);
                    }
                } else {
                    currentHeader = null; // Malformed header
                }
            }
        }
        writer.append("MIME-Version: 1.0").append(CRLF);
    }

    private static boolean isFilteredHeader(String name) {
        return name.equalsIgnoreCase("Content-Type")
                || name.equalsIgnoreCase("Content-Transfer-Encoding")
                || name.equalsIgnoreCase("MIME-Version");
    }

    private static void appendBoundary(java.io.Writer output, String boundary, boolean closing) throws IOException {
        output.append("--").append(boundary);
        if (closing) {
            output.append("--");
        }
        output.append(CRLF);
    }

    private static void appendHeader(java.io.Writer output, String name, String value) throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        output.append(name).append(": ").append(value).append(CRLF);
    }

    private static List<Body> extractBodies(MAPIMessage message) {
        var bodies = new ArrayList<Body>(3);
        try {
            var text = message.getTextBody();
            if (text != null && !text.isEmpty()) {
                bodies.add(new Body(text, "text/plain; charset=UTF-8"));
            }
        } catch (ChunkNotFoundException ignored) {
            // optional
        }
        try {
            var html = message.getHtmlBody();
            if (html != null && !html.isEmpty()) {
                bodies.add(new Body(html, "text/html; charset=UTF-8"));
            }
        } catch (ChunkNotFoundException ignored) {
            // optional
        }
        try {
            var rtfText = message.getRtfBody();
            if (rtfText != null && !rtfText.isEmpty()) {
                var stripped = RtfStripper.strip(rtfText);
                if (!stripped.isEmpty()) {
                    if (bodies.stream().noneMatch(body -> body.contentType().startsWith("text/plain"))) {
                        bodies.add(new Body(stripped, "text/plain; charset=UTF-8"));
                    }
                }
            }
        } catch (ChunkNotFoundException ignored) {
            // optional
        }
        if (bodies.isEmpty()) {
            bodies.add(new Body("", "text/plain; charset=UTF-8"));
        }
        return bodies;
    }

    private static List<EmittedPart> collectAttachments(MAPIMessage message, int depth) throws IOException {
        var raw = message.getAttachmentFiles();
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        var emitted = new ArrayList<EmittedPart>(raw.length);
        for (var chunks : raw) {
            emitted.add(buildAttachmentPart(chunks, depth));
        }
        return emitted;
    }

    private static EmittedPart buildAttachmentPart(AttachmentChunks chunks, int depth) throws IOException {
        if (chunks.isEmbeddedMessage()) {
            var embedded = chunks.getEmbeddedMessage();
            String nestedEml;
            try {
                var stringWriter = new java.io.StringWriter();
                convert(embedded, depth + 1, stringWriter);
                nestedEml = stringWriter.toString();
            } catch (ChunkNotFoundException failure) {
                throw new IOException("Could not read embedded message: " + failure.getMessage(), failure);
            }
            var subject = safeString(safeSubject(embedded));
            var filename = sanitizeFilename(subject.isBlank() ? "embedded" : subject) + ".eml";
            var headers = new StringBuilder();
            headers.append("Content-Type: message/rfc822").append(CRLF);
            headers.append("Content-Transfer-Encoding: 7bit").append(CRLF);
            headers.append("Content-Disposition: attachment; filename=\"")
                    .append(encodeFilename(filename))
                    .append('"')
                    .append(CRLF);
            return new EmittedPart(headers.toString(), nestedEml);
        }

        var bytes = attachmentBytes(chunks);
        var filename = pickFilename(chunks);
        var mime = pickMimeType(chunks);
        var headers = new StringBuilder();
        headers.append("Content-Type: ").append(mime);
        if (filename != null) {
            headers.append("; name=\"").append(encodeFilename(filename)).append('"');
        }
        headers.append(CRLF);
        headers.append("Content-Transfer-Encoding: base64").append(CRLF);
        if (filename != null) {
            headers.append("Content-Disposition: attachment; filename=\"")
                    .append(encodeFilename(filename))
                    .append('"')
                    .append(CRLF);
        } else {
            headers.append("Content-Disposition: attachment").append(CRLF);
        }
        return new EmittedPart(headers.toString(), encodeBase64Wrapped(bytes));
    }

    private static byte[] attachmentBytes(AttachmentChunks chunks) {
        var dataChunk = chunks.getAttachData();
        if (dataChunk == null) {
            return new byte[0];
        }
        var value = dataChunk.getValue();
        return value == null ? new byte[0] : value;
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

    private static String resolveSender(MAPIMessage message) {
        String name = null;
        try {
            name = message.getDisplayFrom();
        } catch (ChunkNotFoundException ignored) {
            // optional
        }
        if (name == null || name.isBlank()) {
            name = readMainString(message, MAPIProperty.SENDER_NAME);
        }
        var email = readMainString(message, MAPIProperty.SENDER_EMAIL_ADDRESS);
        if (email == null || email.isBlank()) {
            email = readMainString(message, MAPIProperty.SENT_REPRESENTING_EMAIL_ADDRESS);
        }
        return formatAddress(name, email);
    }

    private static String joinRecipients(MAPIMessage message, int wantedType) {
        var details = message.getRecipientDetailsChunks();
        if (details == null || details.length == 0) {
            return "";
        }
        var addresses = new ArrayList<String>();
        for (var chunks : details) {
            var type = readRecipientType(chunks);
            if (type != null && type == wantedType) {
                addresses.add(formatAddress(chunks.getRecipientName(), chunks.getRecipientEmailAddress()));
            }
        }
        return String.join(", ", addresses);
    }

    static String formatAddress(String name, String email) {
        var trimmedEmail = email == null ? "" : email.trim();
        var trimmedName = name == null ? "" : name.trim();
        if (trimmedEmail.isEmpty()) {
            return encodeHeaderIfNeeded(trimmedName);
        }
        if (trimmedName.isEmpty()) {
            return "<" + trimmedEmail + ">";
        }
        if (isPureAscii(trimmedName)) {
            return "\"" + trimmedName.replace("\\", "\\\\").replace("\"", "\\\"") + "\" <" + trimmedEmail + ">";
        }
        return encodeHeaderIfNeeded(trimmedName) + " <" + trimmedEmail + ">";
    }

    static String encodeHeaderIfNeeded(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (isPureAscii(value)) {
            return value;
        }
        var encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return "=?UTF-8?B?" + encoded + "?=";
    }

    static String formatRfc2822Date(Date date) {
        Objects.requireNonNull(date, "date");
        var formatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
        return formatter.format(date);
    }

    private static String encodeFilename(String filename) {
        return encodeHeaderIfNeeded(filename);
    }

    private static String sanitizeFilename(String name) {
        var trimmed = name.trim();
        var builder = new StringBuilder(trimmed.length());
        for (var index = 0; index < trimmed.length(); index++) {
            var character = trimmed.charAt(index);
            if (character < 0x20 || "\\/:*?\"<>|".indexOf(character) >= 0) {
                builder.append('_');
            } else {
                builder.append(character);
            }
        }
        var result = builder.toString().trim();
        return result.isEmpty() ? "embedded" : result;
    }

    private static String encodeBase64Wrapped(byte[] payload) {
        var encoded = Base64.getMimeEncoder(76, CRLF.getBytes(StandardCharsets.US_ASCII))
                .encodeToString(payload);
        return encoded + CRLF;
    }

    static boolean isPureAscii(String value) {
        for (var index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static int firstNonAsciiIndex(String text) {
        for (var index = 0; index < text.length(); index++) {
            if (text.charAt(index) > 0x7F) {
                return index;
            }
        }
        return -1;
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

    private record Body(String text, String contentType) {
        byte[] utf8Bytes() {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    private record EmittedPart(String headers, String encodedBody) {}
}
