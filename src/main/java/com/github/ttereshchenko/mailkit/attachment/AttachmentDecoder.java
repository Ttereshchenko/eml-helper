package com.github.ttereshchenko.mailkit.attachment;

import java.io.ByteArrayOutputStream;
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
