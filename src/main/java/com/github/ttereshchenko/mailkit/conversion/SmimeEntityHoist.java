package com.github.ttereshchenko.mailkit.conversion;

import java.nio.charset.StandardCharsets;

/**
 * Turns the stored S/MIME envelope of an {@code IPM.Note.SMIME*} / {@code IPM.Note.Secure*} message
 * into a top-level MIME entity that can be written verbatim through {@link EmlSerializer#setRawEntity}.
 *
 * <p>[MS-OXOSMIME] §2.2.1: such a message keeps its complete original MIME content in a single
 * attachment — a clear-signed message as a full MIME entity (headers + {@code multipart/signed}
 * body), an opaque signed/encrypted one as a raw PKCS#7 blob ({@code smime.p7m}). Re-encoding either
 * through the regular body/attachment pipeline demotes the envelope to an opaque attachment and makes
 * the signature unverifiable, so the stored bytes must become the message's own entity instead.
 *
 * <p>This helper is POI-free and shared by both the MSG and PST conversion paths: each path supplies
 * the raw attachment bytes plus the fallback filename / MIME tag it read from its own store, and this
 * class performs the byte-to-entity reasoning identically for both.
 */
public final class SmimeEntityHoist {

    /**
     * A hoisted top-level entity ready for {@link EmlSerializer#setRawEntity}.
     *
     * @param contentType the {@code Content-Type} header value (boundary/name included)
     * @param transferEncoding the {@code Content-Transfer-Encoding}, or {@code null} to omit it
     * @param disposition the {@code Content-Disposition}, or {@code null} to omit it
     * @param body the entity body bytes, written unmodified after the headers
     * @param fromMimeHeaders {@code true} when the bytes parsed as a full MIME entity (clear-signed),
     *     {@code false} when they were treated as an opaque PKCS#7 blob — lets the caller log which
     */
    public record HoistedEntity(
            String contentType, String transferEncoding, String disposition, byte[] body, boolean fromMimeHeaders) {}

    private SmimeEntityHoist() {}

    /**
     * Hoists {@code data} to a top-level entity. When the bytes begin with a parseable RFC 5322 header
     * block that carries a {@code Content-Type}, the entity is hoisted verbatim (the clear-signed
     * {@code multipart/signed} case); otherwise the bytes are an opaque PKCS#7 blob and become a
     * base64 {@code application/pkcs7-mime} (or the stored MIME tag) attachment-entity named after
     * {@code fallbackFilename}. {@code data} must be non-empty (callers gate on a single data-bearing
     * attachment first).
     */
    public static HoistedEntity hoist(byte[] data, String fallbackFilename, String fallbackMimeTag) {
        // ISO-8859-1 maps bytes 1:1 to chars, so the header scan and CRLF normalization operate on
        // chars without altering the byte values; re-encoding the result with ISO-8859-1 recovers the
        // exact body bytes. The body is then carried as byte[] and written verbatim by
        // EmlSerializer.writeTo(OutputStream), so an 8-bit clear-signed envelope (e.g. a multipart/
        // signed part declared 8bit) survives intact rather than being doubled by the UTF-8 encoder.
        var entity = new String(data, StandardCharsets.ISO_8859_1);
        var headerEnd = entityHeaderEnd(entity);
        if (headerEnd > 0) {
            var headerBlock = entity.substring(0, headerEnd);
            var contentType = entityHeaderValue(headerBlock, "Content-Type");
            if (contentType != null && !contentType.isBlank()) {
                var transferEncoding = entityHeaderValue(headerBlock, "Content-Transfer-Encoding");
                var bodyStart = headerEnd + (entity.startsWith("\r\n\r\n", headerEnd) ? 4 : 2);
                var body = normalizeToCrlf(entity.substring(Math.min(bodyStart, entity.length())))
                        .getBytes(StandardCharsets.ISO_8859_1);
                return new HoistedEntity(contentType, transferEncoding, null, body, true);
            }
        }
        // No parseable entity headers: an opaque PKCS#7 blob becomes the message's own entity.
        var filename = EmlSerializer.sanitizeFilename(
                fallbackFilename == null || fallbackFilename.isBlank() ? "smime.p7m" : fallbackFilename);
        var contentType = (fallbackMimeTag == null || fallbackMimeTag.isBlank()
                        ? "application/pkcs7-mime"
                        : fallbackMimeTag.trim())
                + "; name=\"" + filename + "\"";
        return new HoistedEntity(
                contentType,
                "base64",
                "attachment; filename=\"" + filename + "\"",
                EmlSerializer.encodeBase64Wrapped(data).getBytes(StandardCharsets.US_ASCII),
                false);
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
                // Defense-in-depth: drop any residual bare CR/LF so a crafted stored header value cannot
                // inject a header field even if hoisted somewhere that does not fold. Today
                // EmlSerializer.setRawEntity already neutralizes this, but the helper stays self-contained.
                return line.substring(colon + 1)
                        .replace('\r', ' ')
                        .replace('\n', ' ')
                        .trim();
            }
        }
        return null;
    }

    private static String normalizeToCrlf(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
    }
}
