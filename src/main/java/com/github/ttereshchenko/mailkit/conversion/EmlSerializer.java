package com.github.ttereshchenko.mailkit.conversion;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class EmlSerializer {
    private static final String CRLF = "\r\n";
    // RFC 5322 §2.1.1: a header line must never exceed 998 characters (excluding CRLF).
    private static final int MAX_HEADER_LINE_LENGTH = 998;
    public static final int RECIPIENT_TYPE_TO = 1;
    public static final int RECIPIENT_TYPE_CC = 2;
    public static final int RECIPIENT_TYPE_BCC = 3;

    private String subject;
    private String senderName;
    private String senderEmail;
    private String transmitterName;
    private String transmitterEmail;
    private Date date;
    private String messageId;
    private String transportHeaders;
    private Integer scl;

    public void setScl(Integer scl) {
        this.scl = scl;
    }

    public String getSubject() {
        return subject;
    }

    private final List<Recipient> recipients = new ArrayList<>();
    private final List<Body> bodies = new ArrayList<>();
    private final List<Attachment> attachments = new ArrayList<>();
    // Insertion-ordered so repeated conversions of the same message emit identical header order.
    private final Map<String, List<String>> customHeaders = new LinkedHashMap<>();

    public EmlSerializer() {}

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public void setSender(String name, String email) {
        this.senderName = name;
        this.senderEmail = email;
    }

    /**
     * Sets the actual transmitter when it differs from the author in {@link #setSender}: RFC 5322
     * §3.6.2 maps an on-behalf-of message to {@code From:} (author) plus {@code Sender:}
     * (transmitter, this pair).
     */
    public void setTransmitter(String name, String email) {
        this.transmitterName = name;
        this.transmitterEmail = email;
    }

    public void addRecipient(int type, String name, String email) {
        recipients.add(new Recipient(type, name, email));
    }

    public void setDate(Date date) {
        this.date = date;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public void setTransportHeaders(String transportHeaders) {
        this.transportHeaders = transportHeaders;
    }

    public void addBody(String text, String contentType) {
        if (text != null && !text.isEmpty()) {
            bodies.add(new Body(text, contentType));
        }
    }

    public void addCustomHeader(String name, String value) {
        if (name != null && !name.isBlank() && value != null) {
            customHeaders.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
    }

    public void addAttachment(
            String filename, String mimeType, byte[] data, String contentId, String contentLocation, boolean isInline) {
        if (data != null) {
            attachments.add(new Attachment(filename, mimeType, data, null, contentId, contentLocation, isInline));
        }
    }

    public void addAttachment(String filename, String mimeType, byte[] data, String contentId, boolean isInline) {
        addAttachment(filename, mimeType, data, contentId, null, isInline);
    }

    public void addEmbeddedMessage(String filename, String nestedEml) {
        if (nestedEml != null && !nestedEml.isEmpty()) {
            attachments.add(new Attachment(filename, "message/rfc822", null, nestedEml, null, null, false));
        }
    }

    public void writeTo(Writer writer) throws IOException {
        // Route all output through a newline-tracking wrapper so the multipart boundary delimiter can
        // own its leading CRLF (RFC 2046) without doubling the trailing CRLF that parts already emit.
        var out = new NewlineTrackingWriter(writer);

        // Header names (lowercased) that an original transport-header block already provided, so we
        // do not duplicate them when backfilling below.
        var present = new HashSet<String>();
        if (transportHeaders != null && !transportHeaders.isBlank()) {
            writeFilteredTransportHeaders(out, transportHeaders, present);
        }

        // Synthesize each essential header the transport block did not supply. With no transport
        // headers at all this fills in the full set; with a partial block it backfills only what is
        // missing (RFC 5322 §3.6) instead of silently dropping our resolved From/To/Subject/Date.
        var synthesized = new ArrayList<String>();

        if (!present.contains("from")) {
            var from = formatAddress(senderName, senderEmail);
            if (from != null && !from.isBlank()) {
                synthesized.add("From");
                appendHeader(out, "From", from);
            }
        }

        if (!present.contains("sender")) {
            var transmitter = formatAddress(transmitterName, transmitterEmail);
            if (!transmitter.isBlank()) {
                synthesized.add("Sender");
                appendHeader(out, "Sender", transmitter);
            }
        }

        if (!present.contains("to")) {
            var toAddress = joinRecipients(RECIPIENT_TYPE_TO);
            if (toAddress != null && !toAddress.isBlank()) {
                synthesized.add("To");
                appendHeader(out, "To", toAddress);
            }
        }

        if (!present.contains("cc")) {
            var ccAddress = joinRecipients(RECIPIENT_TYPE_CC);
            if (ccAddress != null && !ccAddress.isBlank()) {
                synthesized.add("Cc");
                appendHeader(out, "Cc", ccAddress);
            }
        }

        if (!present.contains("bcc")) {
            var bcc = joinRecipients(RECIPIENT_TYPE_BCC);
            if (bcc != null && !bcc.isBlank()) {
                synthesized.add("Bcc");
                appendHeader(out, "Bcc", bcc);
            }
        }

        if (!present.contains("subject")) {
            var subj = encodeHeaderIfNeeded(subject == null ? "" : subject);
            if (subj != null && !subj.isBlank()) {
                synthesized.add("Subject");
                appendHeader(out, "Subject", subj);
            }
        }

        if (!present.contains("date")) {
            // RFC 5322 §3.6 makes Date mandatory. Fall back to the conversion time when the source
            // message carries neither a delivery time nor a client-submit time.
            synthesized.add("Date");
            appendHeader(out, "Date", formatRfc2822Date(date != null ? date : new Date()));
        }

        if (!present.contains("message-id") && messageId != null && !messageId.isBlank()) {
            synthesized.add("Message-ID");
            appendHeader(out, "Message-ID", angleBracketed(messageId.trim()));
        }

        // Independent of Message-ID synthesis — a transport block carrying its own Message-ID must
        // not suppress the spam-confidence header.
        if (scl != null && !present.contains("x-ms-exchange-organization-scl")) {
            appendHeader(out, "X-MS-Exchange-Organization-SCL", String.valueOf(scl));
        }

        if (!synthesized.isEmpty()) {
            appendHeader(out, "X-MailKit-Synthesized-Headers", String.join(", ", synthesized));
        }

        for (var entry : customHeaders.entrySet()) {
            String headerName = entry.getKey();
            if (!present.contains(headerName.toLowerCase(Locale.ROOT))) {
                for (String value : entry.getValue()) {
                    if (value == null || value.isBlank()) {
                        // An empty field body is legal (RFC 5322 §2.2) and meaningful — e.g. the
                        // X-MS-Journal-Report marker on Exchange journal reports carries no value —
                        // so emit the bare field instead of dropping it like appendHeader would.
                        out.append(headerName).append(':').append(CRLF);
                    } else {
                        appendHeader(out, headerName, encodeHeaderIfNeeded(value));
                    }
                }
            }
        }

        // Always re-emit MIME-Version: the transport block's own MIME-Version is filtered out along
        // with the original Content-Type/CTE (the serializer re-encodes the body structure itself),
        // so honoring `present` here used to leave the generated message with no MIME-Version at all.
        out.append("MIME-Version: 1.0").append(CRLF);

        bodies.sort((body1, body2) -> {
            int rank1 =
                    body1.contentType.contains("text/plain") ? 1 : (body1.contentType.contains("text/html") ? 3 : 2);
            int rank2 =
                    body2.contentType.contains("text/plain") ? 1 : (body2.contentType.contains("text/html") ? 3 : 2);
            return Integer.compare(rank1, rank2);
        });

        // Inline parts (Content-ID / disposition inline) belong with the body in a multipart/related
        // subtree (RFC 2387) so that cid: references resolve; everything else is a mixed attachment.
        var relatedParts = new ArrayList<Attachment>();
        var mixedParts = new ArrayList<Attachment>();
        for (var part : attachments) {
            if (part.isInline()
                    || (part.contentId() != null && !part.contentId().isBlank())) {
                relatedParts.add(part);
            } else {
                mixedParts.add(part);
            }
        }

        if (attachments.isEmpty()) {
            appendBodyEntity(out);
            return;
        }

        if (mixedParts.isEmpty()) {
            // Only inline parts: the whole message is a multipart/related.
            appendRelated(out, relatedParts);
            return;
        }

        var rootBoundary = uniqueBoundary("MAILKIT_");
        out.append("Content-Type: multipart/mixed; boundary=\"")
                .append(rootBoundary)
                .append('"')
                .append(CRLF);
        out.append(CRLF);

        if (!bodies.isEmpty() || !relatedParts.isEmpty()) {
            appendBoundary(out, rootBoundary, false);
            if (!relatedParts.isEmpty()) {
                appendRelated(out, relatedParts);
            } else {
                appendBodyEntity(out);
            }
        }

        for (var part : mixedParts) {
            appendBoundary(out, rootBoundary, false);
            appendAttachmentPart(out, part);
        }
        appendBoundary(out, rootBoundary, true);
    }

    /** Writes the message body as a single entity or a multipart/alternative, with no leading boundary. */
    private void appendBodyEntity(Writer writer) throws IOException {
        if (bodies.size() <= 1) {
            var body = bodies.isEmpty() ? new Body("", "text/plain; charset=UTF-8") : bodies.get(0);
            writer.append("Content-Type: ").append(body.contentType).append(CRLF);
            writer.append("Content-Transfer-Encoding: quoted-printable").append(CRLF);
            writer.append(CRLF);
            writer.append(quotedPrintableEncode(body.text));
        } else {
            var altBoundary = uniqueBoundary("MAILKIT_ALT_");
            writer.append("Content-Type: multipart/alternative; boundary=\"")
                    .append(altBoundary)
                    .append('"')
                    .append(CRLF);
            writer.append(CRLF);
            for (var body : bodies) {
                appendBoundary(writer, altBoundary, false);
                writer.append("Content-Type: ").append(body.contentType).append(CRLF);
                writer.append("Content-Transfer-Encoding: quoted-printable").append(CRLF);
                writer.append(CRLF);
                writer.append(quotedPrintableEncode(body.text));
            }
            appendBoundary(writer, altBoundary, true);
        }
    }

    /** Writes a multipart/related entity: the body followed by the inline parts. */
    private void appendRelated(Writer writer, List<Attachment> relatedParts) throws IOException {
        var relatedBoundary = uniqueBoundary("MAILKIT_REL_");
        writer.append("Content-Type: multipart/related; boundary=\"")
                .append(relatedBoundary)
                .append("\"; type=\"")
                // RFC 2387 §3.1 makes the root part's media type a required parameter.
                .append(rootBodyMimeType())
                .append('"')
                .append(CRLF);
        writer.append(CRLF);
        appendBoundary(writer, relatedBoundary, false);
        appendBodyEntity(writer);
        for (var part : relatedParts) {
            appendBoundary(writer, relatedBoundary, false);
            appendAttachmentPart(writer, part);
        }
        appendBoundary(writer, relatedBoundary, true);
    }

    /** The media type of the entity {@link #appendBodyEntity} will write as the multipart/related root. */
    private String rootBodyMimeType() {
        if (bodies.size() > 1) {
            return "multipart/alternative";
        }
        if (bodies.isEmpty()) {
            return "text/plain";
        }
        var contentType = bodies.get(0).contentType;
        var parameterStart = contentType.indexOf(';');
        return (parameterStart < 0 ? contentType : contentType.substring(0, parameterStart)).trim();
    }

    private static void appendAttachmentPart(Writer writer, Attachment part) throws IOException {
        writer.append(part.headers());
        writer.append(CRLF);
        writer.append(part.encodedBody());
        if (!part.encodedBody().endsWith(CRLF)) {
            writer.append(CRLF);
        }
    }

    private String joinRecipients(int wantedType) {
        var addresses = new ArrayList<String>();
        for (var recipient : recipients) {
            if (recipient.type == wantedType) {
                var addr = formatAddress(recipient.name, recipient.email);
                if (!addr.isBlank()) {
                    addresses.add(addr);
                }
            }
        }
        return String.join(", ", addresses);
    }

    /** Wraps a Message-ID in the angle brackets RFC 5322 §3.6.4 requires when the stored value lacks them. */
    private static String angleBracketed(String messageId) {
        var bracketed = messageId;
        if (!bracketed.startsWith("<")) {
            bracketed = "<" + bracketed;
        }
        if (!bracketed.endsWith(">")) {
            bracketed = bracketed + ">";
        }
        return bracketed;
    }

    static String quotedPrintableEncode(String text) {
        if (text == null) return "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder();
        int lineLen = 0;
        for (int index = 0; index < bytes.length; index++) {
            int val = bytes[index] & 0xFF;
            boolean printable = (val >= 33 && val <= 126 && val != 61) || val == 9 || val == 32;
            if (val == 10 || val == 13) {
                // Every line-break flavour — LF, CRLF (consume the pair) and lone CR — emits a hard
                // break; silently dropping a lone CR used to join its two lines into one.
                if (val == 13 && index + 1 < bytes.length && bytes[index + 1] == 10) {
                    index++;
                }

                escapeTrailingWhitespace(builder, lineLen);
                builder.append("\r\n");
                lineLen = 0;
                continue;
            }
            if (printable) {
                if (lineLen >= 75) {
                    builder.append("=\r\n");
                    lineLen = 0;
                }
                builder.append((char) val);
                lineLen++;
            } else {
                if (lineLen >= 73) {
                    builder.append("=\r\n");
                    lineLen = 0;
                }
                builder.append(String.format("=%02X", val));
                lineLen += 3;
            }
        }
        if (lineLen > 0) {
            escapeTrailingWhitespace(builder, lineLen);
            builder.append("\r\n");
        }
        return builder.toString();
    }

    /**
     * A line must not end in raw space/tab (RFC 2045 §6.7 rule 3): re-encode a trailing one as
     * {@code =20}/{@code =09}. Replacing one char with three can push a full line past the 76-char
     * limit, so soft-wrap first when the remainder would not fit.
     */
    private static void escapeTrailingWhitespace(StringBuilder builder, int lineLen) {
        if (lineLen <= 0) {
            return;
        }
        char lastChar = builder.charAt(builder.length() - 1);
        if (lastChar != ' ' && lastChar != '\t') {
            return;
        }
        builder.setLength(builder.length() - 1);
        if (lineLen > 74) {
            builder.append("=\r\n");
        }
        builder.append(String.format("=%02X", (int) lastChar));
    }

    public static String formatAddress(String name, String email) {
        var trimmedEmail = sanitizeAddress(email);
        var trimmedName = name == null ? "" : name.trim();
        if (trimmedEmail.isEmpty()) {
            if (trimmedName.isEmpty()) {
                return "";
            }
            trimmedEmail = "undisclosed@invalid";
        }
        if (trimmedName.isEmpty()) {
            return "<" + trimmedEmail + ">";
        }
        if (isPureAscii(trimmedName)) {
            return "\"" + trimmedName.replace("\\", "\\\\").replace("\"", "\\\"") + "\" <" + trimmedEmail + ">";
        }
        return encodeHeaderIfNeeded(trimmedName) + " <" + trimmedEmail + ">";
    }

    /**
     * Strips characters an addr-spec can never legitimately contain — CR, LF, and the angle brackets
     * {@code <}/{@code >} that {@link #formatAddress} supplies itself. Without this, a crafted PST
     * address such as {@code "a@b\r\nBcc: victim"} would split the header line, or a stray {@code >}
     * would break out of the {@code <...>} wrapper.
     */
    private static String sanitizeAddress(String email) {
        if (email == null) {
            return "";
        }
        var builder = new StringBuilder(email.length());
        for (var index = 0; index < email.length(); index++) {
            var character = email.charAt(index);
            if (character != '\r' && character != '\n' && character != '<' && character != '>') {
                builder.append(character);
            }
        }
        return builder.toString().trim();
    }

    public static String encodeHeaderIfNeeded(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (isPureAscii(value)) {
            return value;
        }
        var builder = new StringBuilder();
        int loopIndex = 0;
        while (loopIndex < value.length()) {
            int end = Math.min(value.length(), loopIndex + 10);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
                end++;
            }
            String chunk = value.substring(loopIndex, end);
            var encoded = Base64.getEncoder().encodeToString(chunk.getBytes(StandardCharsets.UTF_8));
            if (builder.length() > 0) {
                builder.append("\r\n ");
            }
            builder.append("=?UTF-8?B?").append(encoded).append("?=");
            loopIndex = end;
        }
        return builder.toString();
    }

    public static String formatRfc2822Date(Date date) {
        Objects.requireNonNull(date, "date");
        var formatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
        formatter.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return formatter.format(date);
    }

    // Kept in sync with Message.imceaEncapsulate in the standalone pst-parser library (which cannot
    // depend on this module); change both together.
    public static String imceaEncapsulate(String addrType, String address) {
        if (address == null || address.isBlank()) return address;
        // Only a value that actually parses as local@domain may pass through unencapsulated: an
        // Exchange X.500 DN such as /O=ORG/CN=USER@HOST contains "@" yet is not an addr-spec, and
        // emitting it raw produces an unparseable From/To header.
        if (looksLikeSmtpAddress(address)) return address;
        var resolvedType = addrType;
        if (resolvedType == null || resolvedType.isBlank()) {
            if (!address.startsWith("/")) {
                return address;
            }
            // An X.500 DN with no recorded address type is still an Exchange address; encapsulate it
            // the way Exchange itself would (IMCEAEX-...).
            resolvedType = "EX";
        }
        if (resolvedType.equalsIgnoreCase("SMTP")) return address;

        StringBuilder builder = new StringBuilder("IMCEA");
        builder.append(resolvedType.toUpperCase(Locale.ROOT)).append("-");

        for (int i = 0; i < address.length(); i++) {
            char chr = address.charAt(i);
            if ((chr >= 'a' && chr <= 'z') || (chr >= 'A' && chr <= 'Z') || (chr >= '0' && chr <= '9') || chr == '-') {
                builder.append(chr);
            } else if (chr == '/') {
                builder.append('_');
            } else {
                builder.append(String.format("_x%04X_", (int) chr));
            }
        }
        // ".invalid" (RFC 2606) marks the encapsulated address as synthesized — a real Exchange
        // deployment would use its own accepted domain, which the source store does not record.
        builder.append("@invalid");
        return builder.toString();
    }

    /**
     * True when the value plausibly parses as an SMTP addr-spec: a single {@code @} separating
     * non-empty halves, with no whitespace/control characters, X.500 DN separators, or angle
     * brackets. Kept deliberately loose otherwise — the goal is to reject values that would render
     * an address header unparseable, not to validate RFC 5321 syntax.
     */
    public static boolean looksLikeSmtpAddress(String address) {
        var atIndex = address.indexOf('@');
        if (atIndex <= 0 || atIndex != address.lastIndexOf('@') || atIndex == address.length() - 1) {
            return false;
        }
        for (var index = 0; index < address.length(); index++) {
            var character = address.charAt(index);
            if (character <= ' ' || character == '/' || character == '<' || character == '>') {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds a Content-Type/Content-Disposition filename parameter. ASCII names use the quoted form;
     * non-ASCII names use RFC 2231 extended notation ({@code filename*=UTF-8''...}) because RFC 2047
     * encoded-words are forbidden inside a quoted-string parameter value (RFC 2047 §5).
     */
    static String filenameParameter(String key, String filename) {
        // The filename comes from attacker-controlled MSG/PST attachment properties. In the quoted
        // ASCII form below a raw CR/LF would terminate the header line and inject arbitrary headers
        // into the generated EML, so control characters are neutralized first (the RFC 2231 path
        // %-encodes them anyway, but keep both paths identical).
        filename = filename.replaceAll("[\\u0000-\\u001F\\u007F]", "_");
        if (isPureAscii(filename)) {
            var escaped = filename.replace("\\", "\\\\").replace("\"", "\\\"");
            return key + "=\"" + escaped + "\"";
        }
        var encodedBytes = filename.getBytes(StandardCharsets.UTF_8);
        var builder = new StringBuilder();
        int chunkIndex = 0;
        int byteIndex = 0;

        while (byteIndex < encodedBytes.length) {
            int end = Math.min(encodedBytes.length, byteIndex + 20);
            while (end < encodedBytes.length && (encodedBytes[end] & 0xC0) == 0x80) {
                end++;
            }
            if (chunkIndex > 0) {
                builder.append(";\r\n ");
            }
            builder.append(key).append('*').append(chunkIndex).append("*=");
            if (chunkIndex == 0) {
                builder.append("UTF-8''");
            }
            for (int i = byteIndex; i < end; i++) {
                int value = encodedBytes[i] & 0xFF;
                if (isRfc2231AttributeChar(value)) {
                    builder.append((char) value);
                } else {
                    builder.append('%').append(String.format("%02X", value));
                }
            }
            byteIndex = end;
            chunkIndex++;
        }
        return builder.toString();
    }

    private static boolean isRfc2231AttributeChar(int value) {
        return (value >= '0' && value <= '9')
                || (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || "!#$&+-.^_`|~".indexOf(value) >= 0;
    }

    public static String sanitizeFilename(String name) {
        var trimmed = name == null ? "" : name.trim();
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

    public static String encodeBase64Wrapped(byte[] payload) {
        var encoded = Base64.getMimeEncoder(76, CRLF.getBytes(StandardCharsets.US_ASCII))
                .encodeToString(payload);
        return encoded + CRLF;
    }

    public static boolean isPureAscii(String value) {
        for (var index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character > 0x7F || (character < 0x20 && character != '\t')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sanitizes an attachment MIME type sourced from PR_ATTACH_MIME_TAG_W. Control characters (CR/LF
     * in particular) are dropped so the Content-Type value cannot terminate the header and inject the
     * lines that follow; an empty result falls back to {@code application/octet-stream}.
     */
    private static String sanitizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "application/octet-stream";
        }
        var builder = new StringBuilder(mimeType.length());
        for (var index = 0; index < mimeType.length(); index++) {
            var character = mimeType.charAt(index);
            if (character >= 0x20 && character != 0x7F) {
                builder.append(character);
            }
        }
        var result = builder.toString().trim();
        return result.isEmpty() ? "application/octet-stream" : result;
    }

    /** True for the composite media types (multipart/*, message/*) that cannot carry a base64 leaf body. */
    private static boolean isCompositeMimeType(String mimeType) {
        var slash = mimeType.indexOf('/');
        if (slash < 0) {
            return false;
        }
        var primaryType = mimeType.substring(0, slash).trim();
        return primaryType.equalsIgnoreCase("multipart") || primaryType.equalsIgnoreCase("message");
    }

    /**
     * Sanitizes a Content-ID sourced from PR_ATTACH_CONTENT_ID_W. The caller wraps the result in
     * {@code <...>}, so angle brackets, whitespace, and control characters (CR/LF) are stripped to
     * keep a crafted value from breaking out of the brackets or splitting the header.
     */
    private static String sanitizeContentId(String contentId) {
        var builder = new StringBuilder(contentId.length());
        for (var index = 0; index < contentId.length(); index++) {
            var character = contentId.charAt(index);
            if (character > 0x20 && character != 0x7F && character != '<' && character != '>') {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private static void writeFilteredTransportHeaders(Writer writer, String transportHeaders, Set<String> present)
            throws IOException {
        var lines = transportHeaders.split("\\r?\\n");
        String currentHeader = null;
        for (var line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            if (Character.isWhitespace(line.charAt(0))) {
                if (currentHeader != null && !isFilteredHeader(currentHeader)) {
                    appendRawHeaderLine(writer, line);
                }
            } else {
                var colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    currentHeader = line.substring(0, colonIndex).trim();
                    // Record every header the block declares (even filtered ones) so callers know
                    // which essential headers are already present and need no synthesizing.
                    present.add(currentHeader.toLowerCase(Locale.ROOT));
                    if (!isFilteredHeader(currentHeader)) {
                        appendRawHeaderLine(writer, line);
                    }
                } else {
                    currentHeader = null;
                }
            }
        }
    }

    /**
     * Emits one original transport-header line verbatim, except that a line longer than the
     * RFC 5322 §2.1.1 hard limit of 998 characters is re-folded at whitespace so the passthrough
     * cannot produce an unparseable message. A line with no foldable whitespace is left overlong
     * rather than corrupted by an arbitrary split.
     */
    private static void appendRawHeaderLine(Writer writer, String line) throws IOException {
        while (line.length() > MAX_HEADER_LINE_LENGTH) {
            int breakPos = -1;
            for (int candidate = MAX_HEADER_LINE_LENGTH; candidate > 0; candidate--) {
                char character = line.charAt(candidate);
                if (character == ' ' || character == '\t') {
                    breakPos = candidate;
                    break;
                }
            }
            if (breakPos <= 0) {
                break;
            }
            writer.append(line, 0, breakPos).append(CRLF);
            // Keep the whitespace at the start of the continuation: that is what makes the fold
            // valid RFC 5322 §2.2.3 folding instead of a new header field.
            line = line.substring(breakPos);
        }
        writer.append(line).append(CRLF);
    }

    private static boolean isFilteredHeader(String name) {
        return name.equalsIgnoreCase("Content-Type")
                || name.equalsIgnoreCase("Content-Transfer-Encoding")
                || name.equalsIgnoreCase("MIME-Version");
    }

    /**
     * Builds a multipart boundary guaranteed not to occur inside any body text (RFC 2046 §5.1.1). A
     * random 128-bit UUID already makes a collision statistically impossible; this scan turns that into a
     * deterministic guarantee. Base64-encoded attachment bodies cannot contain the boundary (the base64
     * alphabet has no {@code '_'}), so only the quoted-printable body texts and any embedded
     * {@code message/rfc822} (raw 8bit) parts need checking.
     */
    private String uniqueBoundary(String prefix) {
        String boundary;
        do {
            boundary = prefix + UUID.randomUUID().toString().replace("-", "");
        } while (appearsInBodies(boundary));
        return boundary;
    }

    private boolean appearsInBodies(String candidate) {
        for (var body : bodies) {
            if (body.text != null && body.text.contains(candidate)) {
                return true;
            }
        }
        // Base64 attachment bodies cannot contain the boundary, but an embedded message/rfc822 part is
        // written as raw 8bit EML and could, so scan those too for the deterministic guarantee to hold.
        for (var attachment : attachments) {
            if (attachment.nestedEml() != null && attachment.nestedEml().contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static void appendBoundary(Writer output, String boundary, boolean closing) throws IOException {
        // RFC 2046: the delimiter is CRLF "--" boundary CRLF. Ensure exactly one CRLF precedes it —
        // reuse the preceding part's trailing CRLF when it already ended a line, otherwise insert the
        // delimiter's own — so we neither drop a line nor add a spurious blank line.
        if (output instanceof NewlineTrackingWriter tracking && !tracking.atLineStart()) {
            output.append(CRLF);
        }
        output.append("--").append(boundary);
        if (closing) {
            output.append("--");
        }
        output.append(CRLF);
    }

    /**
     * Writer wrapper that remembers whether the last character written was a line feed. Lets the
     * multipart boundary delimiter own its leading CRLF (RFC 2046): it inserts one only when the
     * preceding content did not already end a line, so a part's trailing CRLF is reused rather than
     * doubled into a spurious blank line, and a part that did not end in CRLF is still delimited.
     */
    private static final class NewlineTrackingWriter extends FilterWriter {
        private char lastChar = '\n';

        NewlineTrackingWriter(Writer out) {
            super(out);
        }

        boolean atLineStart() {
            return lastChar == '\n';
        }

        @Override
        public void write(int character) throws IOException {
            super.write(character);
            lastChar = (char) character;
        }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            super.write(buffer, offset, length);
            if (length > 0) {
                lastChar = buffer[offset + length - 1];
            }
        }

        @Override
        public void write(String string, int offset, int length) throws IOException {
            super.write(string, offset, length);
            if (length > 0) {
                lastChar = string.charAt(offset + length - 1);
            }
        }
    }

    private static void appendHeader(Writer output, String name, String value) throws IOException {
        if (value == null || value.isBlank()) {
            return;
        }
        String headerLine = name + ": " + value;
        // A header field body may contain a line break only as folding immediately followed by WSP
        // (RFC 5322 §2.2.3). Normalize every CR/LF to a single LF, then re-emit each segment with CRLF
        // and force every continuation to begin with WSP. That keeps legitimate folding (the "\r\n "
        // that encodeHeaderIfNeeded inserts) intact while turning an injected "\r\nBcc: ..." from an
        // attacker-controlled PST value into a harmless folded continuation rather than a new header.
        String[] lines = headerLine.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index > 0 && (line.isEmpty() || (line.charAt(0) != ' ' && line.charAt(0) != '\t'))) {
                line = " " + line;
            }
            while (line.length() > 78) {
                int breakPos = -1;
                for (int j = 78; j > 0; j--) {
                    if (line.charAt(j) == ' ' || line.charAt(j) == '\t') {
                        breakPos = j;
                        break;
                    }
                }
                if (breakPos == -1) {
                    for (int j = 78; j < line.length(); j++) {
                        if (line.charAt(j) == ' ' || line.charAt(j) == '\t') {
                            breakPos = j;
                            break;
                        }
                    }
                }
                if (breakPos <= 0) {
                    break;
                }
                output.append(line.substring(0, breakPos)).append("\r\n");
                line = line.substring(breakPos);
                if (line.length() > 0 && line.charAt(0) != ' ' && line.charAt(0) != '\t') {
                    line = " " + line;
                }
            }
            output.append(line).append("\r\n");
        }
    }

    /**
     * Removes CR and LF from a header name or value so attacker-controlled content cannot terminate
     * the current header field and inject a following one (RFC 5322 §2.2.3). Folding is reintroduced
     * by {@link #appendHeader} where it is needed; legitimate folded input collapses to a single
     * space-separated line and is re-folded there.
     */
    private static String stripLineBreaks(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('\r') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        var builder = new StringBuilder(value.length());
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character != '\r' && character != '\n') {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private record Recipient(int type, String name, String email) {}

    private record Body(String text, String contentType) {
        byte[] utf8Bytes() {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    private record Attachment(
            String filename,
            String mimeType,
            byte[] data,
            String nestedEml,
            String contentId,
            String contentLocation,
            boolean isInline) {
        String headers() {
            var headers = new StringBuilder();
            // mimeType / contentId / contentLocation come straight from attacker-controlled PST string
            // properties (PR_ATTACH_MIME_TAG_W, PR_ATTACH_CONTENT_ID_W) and are written raw here rather
            // than through appendHeader, so each is sanitized of CR/LF (and the bracket/control chars
            // that would let it break out) before it reaches the output.
            var safeMimeType = sanitizeMimeType(mimeType);
            if (nestedEml == null && isCompositeMimeType(safeMimeType)) {
                // A stored multipart/* or message/* MIME tag on an opaque base64 payload would emit a
                // structurally invalid part: RFC 2045 §6.4 forbids base64 on composite types, and a
                // multipart Content-Type without a boundary parameter is unparseable. (Real embedded
                // messages take the nestedEml path above and keep their message/rfc822 type.)
                safeMimeType = "application/octet-stream";
            }
            headers.append("Content-Type: ").append(safeMimeType);
            if (filename != null) {
                headers.append("; ").append(filenameParameter("name", filename));
            }
            headers.append(CRLF);
            if (nestedEml != null) {
                headers.append("Content-Transfer-Encoding: 8bit").append(CRLF);
            } else {
                headers.append("Content-Transfer-Encoding: base64").append(CRLF);
            }
            if (filename != null) {
                headers.append("Content-Disposition: ")
                        .append(isInline ? "inline" : "attachment")
                        .append("; ")
                        .append(filenameParameter("filename", filename))
                        .append(CRLF);
            } else {
                headers.append("Content-Disposition: ")
                        .append(isInline ? "inline" : "attachment")
                        .append(CRLF);
            }
            if (contentId != null && !contentId.isBlank()) {
                String cleanId = sanitizeContentId(contentId);
                if (!cleanId.isEmpty()) {
                    headers.append("Content-ID: <").append(cleanId).append(">").append(CRLF);
                }
            }
            if (contentLocation != null && !contentLocation.isBlank()) {
                headers.append("Content-Location: ")
                        .append(stripLineBreaks(contentLocation).trim())
                        .append(CRLF);
            }
            return headers.toString();
        }

        String encodedBody() {
            if (nestedEml != null) {
                return nestedEml;
            }
            return encodeBase64Wrapped(data);
        }
    }
}
