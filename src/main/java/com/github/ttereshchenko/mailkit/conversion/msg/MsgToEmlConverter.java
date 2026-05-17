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

    private MsgToEmlConverter() {}

    public static String convert(InputStream msgStream) throws IOException, ChunkNotFoundException {
        Objects.requireNonNull(msgStream, "msgStream");
        var message = new MAPIMessage(msgStream);
        var result = convert(message, 0);
        var problemIndex = firstNonAsciiIndex(result);
        if (problemIndex >= 0) {
            throw new IllegalStateException("non-ASCII character in assembled EML at index " + problemIndex);
        }
        return result;
    }

    static String convert(MAPIMessage message, int depth) throws IOException, ChunkNotFoundException {
        if (depth > MAX_EMBEDDED_DEPTH) {
            throw new IOException("embedded message depth exceeded");
        }
        message.setReturnNullOnMissingChunk(true);

        var body = selectBody(message);
        var attachments = collectAttachments(message, depth);

        var headers = new StringBuilder();
        appendHeader(headers, "From", resolveSender(message));
        appendHeader(headers, "To", joinRecipients(message, RECIPIENT_TYPE_TO));
        appendHeader(headers, "Cc", joinRecipients(message, RECIPIENT_TYPE_CC));
        appendHeader(headers, "Subject", encodeHeaderIfNeeded(safeString(safeSubject(message))));
        var date = safeDate(message);
        if (date != null) {
            appendHeader(headers, "Date", formatRfc2822Date(date));
        }
        var messageId = readMainString(message, MAPIProperty.INTERNET_MESSAGE_ID);
        if (messageId != null && !messageId.isBlank()) {
            appendHeader(headers, "Message-ID", messageId.trim());
        }
        headers.append("MIME-Version: 1.0").append(CRLF);

        if (attachments.isEmpty()) {
            headers.append("Content-Type: ").append(body.contentType()).append(CRLF);
            headers.append("Content-Transfer-Encoding: base64").append(CRLF);
            headers.append(CRLF);
            headers.append(encodeBase64Wrapped(body.utf8Bytes()));
            return headers.toString();
        }

        var boundary = "MAILKIT_" + UUID.randomUUID().toString().replace("-", "");
        headers.append("Content-Type: multipart/mixed; boundary=\"")
                .append(boundary)
                .append('"')
                .append(CRLF);
        headers.append(CRLF);
        var output = new StringBuilder(headers);
        appendBoundary(output, boundary, false);
        output.append("Content-Type: ").append(body.contentType()).append(CRLF);
        output.append("Content-Transfer-Encoding: base64").append(CRLF);
        output.append(CRLF);
        output.append(encodeBase64Wrapped(body.utf8Bytes()));
        if (!output.toString().endsWith(CRLF)) {
            output.append(CRLF);
        }
        for (var part : attachments) {
            appendBoundary(output, boundary, false);
            output.append(part.headers());
            output.append(CRLF);
            output.append(part.encodedBody());
            if (!output.toString().endsWith(CRLF)) {
                output.append(CRLF);
            }
        }
        appendBoundary(output, boundary, true);
        return output.toString();
    }

    private static void appendBoundary(StringBuilder output, String boundary, boolean closing) {
        output.append("--").append(boundary);
        if (closing) {
            output.append("--");
        }
        output.append(CRLF);
    }

    private static void appendHeader(StringBuilder output, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        output.append(name).append(": ").append(value).append(CRLF);
    }

    private static Body selectBody(MAPIMessage message) {
        try {
            var html = message.getHtmlBody();
            if (html != null && !html.isEmpty()) {
                return new Body(html, "text/html; charset=UTF-8");
            }
        } catch (ChunkNotFoundException ignored) {
            // optional
        }
        try {
            var text = message.getTextBody();
            if (text != null && !text.isEmpty()) {
                return new Body(text, "text/plain; charset=UTF-8");
            }
        } catch (ChunkNotFoundException ignored) {
            // optional
        }
        try {
            var rtfText = message.getRtfBody();
            if (rtfText != null && !rtfText.isEmpty()) {
                var stripped = RtfStripper.strip(rtfText);
                if (!stripped.isEmpty()) {
                    return new Body(stripped, "text/plain; charset=UTF-8");
                }
            }
        } catch (ChunkNotFoundException ignored) {
            // optional
        }
        return new Body("", "text/plain; charset=UTF-8");
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
                nestedEml = convert(embedded, depth + 1);
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
