package com.github.ttereshchenko.mailkit.comparison;

import com.github.ttereshchenko.mailkit.attachment.AttachmentDecoder;
import com.github.ttereshchenko.mailkit.attachment.AttachmentDetector;
import com.github.ttereshchenko.mailkit.attachment.ContentTransferEncoding;
import com.github.ttereshchenko.mailkit.attachment.DecodingException;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderBlock;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderParsing;
import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
import com.github.ttereshchenko.mailkit.psi.EmlNestedMessage;
import com.github.ttereshchenko.mailkit.psi.EmlPsiFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Renders a parsed EML message into a deterministic, decoded, human-readable canonical form so two
 * messages can be compared on <em>meaning</em> rather than wire bytes. Header values are RFC 2047
 * decoded and emitted in a canonical order; bodies are decoded out of base64 / quoted-printable and
 * their declared charset; attachments are reduced to a name/type/size/SHA-256 manifest; and the MIME
 * tree is rendered. Volatile, non-semantic noise (random multipart boundary tokens, transfer
 * encoding, header ordering, line endings) is normalized away so it does not surface as a difference.
 *
 * <p>Walks PSI, so it must be invoked inside a read action.
 */
public final class EmlNormalizer {

    private static final String HEADERS_BANNER = "═══ HEADERS ═══\n";
    private static final String BODY_BANNER = "═══ BODY ";
    private static final String ATTACHMENTS_BANNER = "═══ ATTACHMENTS ═══\n";
    private static final String STRUCTURE_BANNER = "═══ STRUCTURE ═══\n";

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";

    // Headers shown first (in this order); everything else follows alphabetically. Lower-cased keys.
    private static final List<String> HEADER_PRIORITY = List.of(
            "date",
            "from",
            "sender",
            "reply-to",
            "to",
            "cc",
            "bcc",
            "subject",
            "message-id",
            "in-reply-to",
            "references",
            "mime-version",
            "content-type");

    private static final Map<String, String> CANONICAL_NAME = Map.ofEntries(
            Map.entry("date", "Date"),
            Map.entry("from", "From"),
            Map.entry("sender", "Sender"),
            Map.entry("reply-to", "Reply-To"),
            Map.entry("to", "To"),
            Map.entry("cc", "Cc"),
            Map.entry("bcc", "Bcc"),
            Map.entry("subject", "Subject"),
            Map.entry("message-id", "Message-ID"),
            Map.entry("in-reply-to", "In-Reply-To"),
            Map.entry("references", "References"),
            Map.entry("mime-version", "MIME-Version"),
            Map.entry("content-type", "Content-Type"));

    // Pure transport detail: the body is shown already decoded, so the wire encoding is just noise
    // here, and keeping it would make two encodings of the same message (base64 vs quoted-printable)
    // compare as different.
    private static final String SKIP_HEADER = "content-transfer-encoding";

    // The multipart boundary token is a random per-message string; replace it so two structurally
    // identical messages with different generated boundaries do not diff on it.
    private static final Pattern BOUNDARY_PARAM =
            Pattern.compile("(?i)boundary\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^;\\s]+)");

    private EmlNormalizer() {}

    public static String normalize(EmlPsiFile file) {
        Objects.requireNonNull(file, "file");
        var text = file.getText();
        var out = new StringBuilder();
        appendMessage(out, file.getHeaderBlock(), file, text);
        appendStructure(out, file);
        return out.toString();
    }

    // Renders one message scope — the top-level file or an embedded message/rfc822 — then recurses into
    // the messages embedded directly within it. Without this, a journaled / forwarded message's real
    // headers and body (which live inside a message/rfc822 part) are never compared.
    private static void appendMessage(StringBuilder out, EmlHeaderBlock headerBlock, PsiElement scope, String text) {
        appendHeaders(out, headerBlock);
        appendBodies(out, headerBlock, scope, text);
        appendAttachments(out, scope);
        var index = 0;
        for (var nested : directNestedMessages(scope)) {
            index++;
            out.append('\n').append("═══ NESTED MESSAGE ").append(index).append(" ═══\n");
            appendMessage(out, nested.getHeaderBlock(), nested, text);
        }
    }

    private static void appendHeaders(StringBuilder out, EmlHeaderBlock block) {
        out.append(HEADERS_BANNER);
        if (block == null) {
            return;
        }
        var entries = new ArrayList<HeaderEntry>();
        var order = 0;
        for (var header : block.getHeaders()) {
            var name = header.getHeaderName();
            if (name != null && !name.isBlank()) {
                var lower = name.trim().toLowerCase(Locale.ROOT);
                if (!lower.equals(SKIP_HEADER)) {
                    var rawValue = header.getDecodedValue();
                    var value = rawValue == null ? "" : rawValue.strip();
                    if (lower.equals("content-type")) {
                        value = BOUNDARY_PARAM.matcher(value).replaceAll("boundary=\"<b>\"");
                    }
                    var priority = HEADER_PRIORITY.indexOf(lower);
                    entries.add(new HeaderEntry(
                            CANONICAL_NAME.getOrDefault(lower, name.trim()),
                            priority < 0 ? HEADER_PRIORITY.size() : priority,
                            lower,
                            value,
                            order));
                }
            }
            order++;
        }
        entries.sort(Comparator.comparingInt(HeaderEntry::priority)
                .thenComparing(HeaderEntry::sortKey)
                .thenComparingInt(HeaderEntry::order));
        for (var entry : entries) {
            out.append(entry.displayName()).append(": ").append(entry.value()).append('\n');
        }
    }

    private static void appendBodies(StringBuilder out, EmlHeaderBlock headerBlock, PsiElement scope, String text) {
        var parts = scopeParts(scope);
        if (parts.isEmpty()) {
            var contentType = headerValue(headerBlock, CONTENT_TYPE);
            var media = EmlHeaderParsing.mediaType(contentType);
            if (media == null || media.startsWith("text/")) {
                appendBody(out, media == null ? "text/plain" : media, contentType, headerBlock, scope, text);
            }
            return;
        }
        for (var part : parts) {
            if (!isLeaf(part)) {
                continue;
            }
            var block = part.getHeaderBlock();
            if (block == null) {
                continue;
            }
            var contentType = headerValue(block, CONTENT_TYPE);
            var media = EmlHeaderParsing.mediaType(contentType);
            if (media == null) {
                media = "text/plain";
            }
            if (!media.equals("text/plain") && !media.equals("text/html")) {
                continue;
            }
            // A text part carrying a filename / attachment disposition is shown in the manifest, not here.
            if (AttachmentDetector.detect(part).isPresent()) {
                continue;
            }
            appendBody(out, media, contentType, block, part, text);
        }
    }

    private static void appendBody(
            StringBuilder out, String media, String contentType, EmlHeaderBlock block, PsiElement owner, String text) {
        var charset = resolveCharset(contentType);
        var encoding = ContentTransferEncoding.parse(headerValue(block, CONTENT_TRANSFER_ENCODING));
        var raw = sliceBody(text, block, owner);
        byte[] bytes;
        try {
            bytes = AttachmentDecoder.decode(raw, encoding);
        } catch (DecodingException ignored) {
            // Malformed transfer encoding: fall back to the raw octets so a difference still shows.
            bytes = raw.getBytes(StandardCharsets.ISO_8859_1);
        }
        var decoded = normalizeLineEndings(new String(bytes, charset));
        out.append('\n')
                .append(BODY_BANNER)
                .append(media)
                .append(" (")
                .append(charset.name().toLowerCase(Locale.ROOT))
                .append(") ═══\n")
                .append(decoded);
        if (!decoded.isEmpty()) {
            out.append('\n');
        }
    }

    private static void appendAttachments(StringBuilder out, PsiElement scope) {
        var lines = new ArrayList<String>();
        for (var part : scopeParts(scope)) {
            // An embedded message/rfc822 is rendered as its own NESTED MESSAGE section, not as an opaque
            // attachment blob.
            if (PsiTreeUtil.getChildOfType(part, EmlNestedMessage.class) != null) {
                continue;
            }
            var info = AttachmentDetector.detect(part).orElse(null);
            if (info == null) {
                continue;
            }
            var block = part.getHeaderBlock();
            var media = EmlHeaderParsing.mediaType(block == null ? null : headerValue(block, CONTENT_TYPE));
            if (media == null) {
                media = "application/octet-stream";
            }
            byte[] bytes;
            try {
                bytes = AttachmentDecoder.decode(info.rawBody(), info.encoding());
            } catch (DecodingException ignored) {
                bytes = info.rawBody().getBytes(StandardCharsets.ISO_8859_1);
            }
            lines.add(info.filename() + " | " + media + " | " + bytes.length + " bytes | sha256=" + sha256Hex(bytes));
        }
        if (lines.isEmpty()) {
            return;
        }
        lines.sort(Comparator.naturalOrder());
        out.append('\n').append(ATTACHMENTS_BANNER);
        for (var line : lines) {
            out.append(line).append('\n');
        }
    }

    private static void appendStructure(StringBuilder out, EmlPsiFile file) {
        out.append('\n').append(STRUCTURE_BANNER);
        var media = EmlHeaderParsing.mediaType(headerValue(file.getHeaderBlock(), CONTENT_TYPE));
        var children = directChildren(file);
        if (children.isEmpty()) {
            out.append(media == null ? "text/plain" : media).append('\n');
            return;
        }
        out.append(media == null ? "multipart/mixed" : media).append('\n');
        for (var child : children) {
            appendStructureNode(out, child, 1);
        }
    }

    private static void appendStructureNode(StringBuilder out, PsiElement element, int depth) {
        var indent = "  ".repeat(depth);
        if (element instanceof EmlMimePart part) {
            var block = part.getHeaderBlock();
            var media = EmlHeaderParsing.mediaType(block == null ? null : headerValue(block, CONTENT_TYPE));
            out.append(indent).append(media == null ? "text/plain" : media);
            var attachment = AttachmentDetector.detect(part).orElse(null);
            if (attachment != null) {
                out.append(" \"").append(attachment.filename()).append('"');
            }
            out.append('\n');
            for (var child : directChildren(part)) {
                appendStructureNode(out, child, depth + 1);
            }
        } else if (element instanceof EmlNestedMessage nested) {
            // The enclosing part/file already labels this message/rfc822; render the nested message's
            // own body structure one level in rather than repeating the message/rfc822 label.
            var media = EmlHeaderParsing.mediaType(headerValue(nested.getHeaderBlock(), CONTENT_TYPE));
            out.append(indent).append(media == null ? "text/plain" : media).append('\n');
            for (var child : directChildren(nested)) {
                appendStructureNode(out, child, depth + 1);
            }
        }
    }

    private static List<PsiElement> directChildren(PsiElement element) {
        var result = new ArrayList<PsiElement>();
        var parts = PsiTreeUtil.getChildrenOfType(element, EmlMimePart.class);
        if (parts != null) {
            result.addAll(Arrays.asList(parts));
        }
        var nested = PsiTreeUtil.getChildrenOfType(element, EmlNestedMessage.class);
        if (nested != null) {
            result.addAll(Arrays.asList(nested));
        }
        result.sort(Comparator.comparingInt(node -> node.getTextRange().getStartOffset()));
        return result;
    }

    // MIME parts belonging to one message scope: descendants of `scope` that are not inside a deeper
    // embedded message (those are handled by that message's own section).
    private static List<EmlMimePart> scopeParts(PsiElement scope) {
        var parts = new ArrayList<EmlMimePart>();
        collectScopeParts(scope, parts);
        return parts;
    }

    private static void collectScopeParts(PsiElement element, List<EmlMimePart> out) {
        for (var child : element.getChildren()) {
            if (child instanceof EmlNestedMessage) {
                continue;
            }
            if (child instanceof EmlMimePart part) {
                out.add(part);
            }
            collectScopeParts(child, out);
        }
    }

    // The message/rfc822 messages embedded directly in this scope (first level only; deeper ones are
    // reached by recursing into each returned message).
    private static List<EmlNestedMessage> directNestedMessages(PsiElement scope) {
        var nested = new ArrayList<EmlNestedMessage>();
        collectDirectNested(scope, nested);
        return nested;
    }

    private static void collectDirectNested(PsiElement element, List<EmlNestedMessage> out) {
        for (var child : element.getChildren()) {
            if (child instanceof EmlNestedMessage nested) {
                out.add(nested);
                continue;
            }
            collectDirectNested(child, out);
        }
    }

    private static boolean isLeaf(EmlMimePart part) {
        return PsiTreeUtil.getChildrenOfType(part, EmlMimePart.class) == null
                && PsiTreeUtil.getChildOfType(part, EmlNestedMessage.class) == null;
    }

    private static String sliceBody(String text, EmlHeaderBlock block, PsiElement owner) {
        if (block == null) {
            return "";
        }
        var start = block.getTextRange().getEndOffset();
        var end = owner.getTextRange().getEndOffset();
        if (start < 0 || start >= end) {
            return "";
        }
        var slice = text.substring(Math.min(start, text.length()), Math.min(end, text.length()));
        return stripLeadingBlankLine(slice);
    }

    private static String stripLeadingBlankLine(String body) {
        var index = 0;
        if (index < body.length() && body.charAt(index) == '\r') {
            index++;
        }
        if (index < body.length() && body.charAt(index) == '\n') {
            index++;
        }
        return body.substring(index);
    }

    private static String normalizeLineEndings(String value) {
        var unified = value.replace("\r\n", "\n").replace('\r', '\n');
        var end = unified.length();
        while (end > 0 && unified.charAt(end - 1) == '\n') {
            end--;
        }
        return unified.substring(0, end);
    }

    private static Charset resolveCharset(String contentType) {
        var name = EmlHeaderParsing.mediaTypeParam(contentType, "charset");
        if (name != null && !name.isBlank()) {
            try {
                return Charset.forName(name.trim());
            } catch (RuntimeException ignored) {
                // Unknown or illegal charset name — fall back to UTF-8.
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String headerValue(EmlHeaderBlock block, String name) {
        if (block == null) {
            return null;
        }
        var header = block.findHeader(name);
        return header == null ? null : header.getDecodedValue();
    }

    private static String sha256Hex(byte[] data) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(data);
            var hex = new StringBuilder(hash.length * 2);
            for (var octet : hash) {
                hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
                hex.append(Character.forDigit(octet & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 not available", impossible);
        }
    }

    private record HeaderEntry(String displayName, int priority, String sortKey, String value, int order) {}
}
