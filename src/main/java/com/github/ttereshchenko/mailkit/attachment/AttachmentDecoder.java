package com.github.ttereshchenko.mailkit.attachment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

public final class AttachmentDecoder {

    private AttachmentDecoder() {}

    public static byte[] decode(String body, ContentTransferEncoding encoding) throws DecodingException {
        Objects.requireNonNull(encoding, "encoding");
        var source = body == null ? "" : body;
        return switch (encoding) {
            case BASE64 -> decodeBase64(source);
            case QUOTED_PRINTABLE -> decodeQuotedPrintable(source);
            case BIT_7, BIT_8, BINARY -> decodeIdentity(source);
        };
    }

    /**
     * Streams the decoded attachment straight to {@code out}, so the caller never has to hold the
     * whole decoded payload in memory at once. For BASE64 — the encoding that dominates large
     * attachments — bytes are piped through the JDK MIME decoder one buffer at a time, avoiding the
     * full {@code byte[]} that {@link #decode} would allocate. QP / identity bodies (typically small)
     * fall back to {@link #decode}. The caller owns {@code out} and is responsible for closing it.
     */
    public static void decodeTo(String body, ContentTransferEncoding encoding, OutputStream out)
            throws DecodingException, IOException {
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(out, "out");
        var source = body == null ? "" : body;
        if (encoding == ContentTransferEncoding.BASE64) {
            decodeBase64To(source, out);
        } else {
            out.write(decode(source, encoding));
        }
    }

    static void decodeBase64To(String body, OutputStream out) throws DecodingException, IOException {
        if (body.isEmpty()) {
            return;
        }
        // The MIME decoder ignores whitespace / non-alphabet characters inline, so we feed the raw
        // body straight through without first materializing a whitespace-stripped copy. Decode
        // failures surface from the wrapper as IOException / IllegalArgumentException — remap those
        // to DecodingException so a write failure on `out` (a real IOException) stays distinguishable.
        var decoder = Base64.getMimeDecoder().wrap(new AsciiCharInputStream(body));
        var buffer = new byte[8192];
        while (true) {
            int read;
            try {
                read = decoder.read(buffer);
            } catch (IOException | IllegalArgumentException cause) {
                throw new DecodingException("Invalid base64 content: " + cause.getMessage(), cause);
            }
            if (read < 0) {
                return;
            }
            out.write(buffer, 0, read);
        }
    }

    /**
     * Presents a {@link String} of ASCII base64 text as a byte {@link InputStream} without copying it
     * into a {@code byte[]} first. Each char maps to its low byte; non-ASCII chars (never valid in
     * base64) collapse to their low 8 bits, which the MIME decoder then ignores.
     */
    private static final class AsciiCharInputStream extends InputStream {
        private final String text;
        private int position;

        AsciiCharInputStream(String text) {
            this.text = text;
        }

        @Override
        public int read() {
            if (position >= text.length()) {
                return -1;
            }
            return text.charAt(position++) & 0xFF;
        }
    }

    static byte[] decodeBase64(String body) throws DecodingException {
        var stripped = body.replaceAll("[\\s]", "");
        if (stripped.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getMimeDecoder().decode(stripped);
        } catch (IllegalArgumentException cause) {
            throw new DecodingException("Invalid base64 content: " + cause.getMessage(), cause);
        }
    }

    static byte[] decodeQuotedPrintable(String body) throws DecodingException {
        var out = new ByteArrayOutputStream(body.length());
        var index = 0;
        var length = body.length();
        while (index < length) {
            var current = body.charAt(index);
            if (current == '=') {
                if (index + 1 < length && (body.charAt(index + 1) == '\n' || body.charAt(index + 1) == '\r')) {
                    // soft line break: =\n or =\r\n
                    index++;
                    if (index < length && body.charAt(index) == '\r') {
                        index++;
                    }
                    if (index < length && body.charAt(index) == '\n') {
                        index++;
                    }
                    continue;
                }
                if (index + 2 >= length) {
                    throw new DecodingException("Truncated quoted-printable escape at offset " + index);
                }
                var high = hexValue(body.charAt(index + 1));
                var low = hexValue(body.charAt(index + 2));
                if (high < 0 || low < 0) {
                    throw new DecodingException("Invalid quoted-printable escape at offset " + index);
                }
                out.write((high << 4) | low);
                index += 3;
            } else if (current == '\r' || current == '\n') {
                out.write(current);
                index++;
            } else if (current <= 0x7F) {
                out.write(current);
                index++;
            } else {
                // Non-ASCII char in a QP stream (technically non-conformant, but real MUAs emit it).
                // The IDE already decoded the file bytes to chars, so re-encode as UTF-8 to preserve
                // code points > U+00FF — ISO-8859-1 would collapse them to '?'.
                var bytes = String.valueOf(current).getBytes(StandardCharsets.UTF_8);
                out.writeBytes(bytes);
                index++;
            }
        }
        return out.toByteArray();
    }

    static byte[] decodeIdentity(String body) {
        return body.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int hexValue(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        return -1;
    }
}
