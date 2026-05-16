package com.github.ttereshchenko.mailkit.inspections.rules;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Decides whether a declared charset can losslessly decode a body. A null result
 * means "decodes cleanly"; otherwise the returned range pinpoints the first
 * undecodable byte and (in {@link Result#suggestion}) a charset that succeeds.
 */
public final class CharsetDecodeRule {

    /** Fallback charsets we try, in order, when the declared charset fails. */
    public static final List<String> FALLBACKS = List.of("UTF-8", "ISO-8859-1", "windows-1252");

    private CharsetDecodeRule() {}

    public record Result(LineRange invalidRange, String suggestion) {}

    public static Result check(byte[] bodyBytes, String declaredCharset) {
        Objects.requireNonNull(bodyBytes, "bodyBytes");
        var charset = resolveCharset(declaredCharset);
        if (charset == null) {
            return null;
        }
        var firstBad = firstDecodingError(bodyBytes, charset);
        if (firstBad < 0) {
            return null;
        }
        var suggestion = pickWorkingFallback(bodyBytes, charset);
        return new Result(new LineRange(firstBad, Math.min(firstBad + 1, bodyBytes.length)), suggestion);
    }

    public static int firstDecodingError(byte[] bodyBytes, Charset charset) {
        var decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var input = ByteBuffer.wrap(bodyBytes);
        var output = CharBuffer.allocate(Math.max(16, bodyBytes.length));
        var consumed = 0;
        while (input.hasRemaining()) {
            var positionBefore = input.position();
            var coderResult = decoder.decode(input, output, true);
            if (coderResult.isError()) {
                return consumed + (input.position() - positionBefore);
            }
            consumed = input.position();
            if (!output.hasRemaining()) {
                output = CharBuffer.allocate(output.capacity() * 2);
            }
        }
        var flush = decoder.flush(output);
        if (flush.isError()) {
            return consumed;
        }
        return -1;
    }

    private static Charset resolveCharset(String declared) {
        if (declared == null || declared.isBlank()) {
            return null;
        }
        try {
            return Charset.forName(declared.trim());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) {
            return null;
        }
    }

    private static String pickWorkingFallback(byte[] bodyBytes, Charset declared) {
        var declaredName = declared.name().toLowerCase(Locale.ROOT);
        for (var candidate : FALLBACKS) {
            if (candidate.equalsIgnoreCase(declaredName)) {
                continue;
            }
            var charset = resolveCharset(candidate);
            if (charset != null && firstDecodingError(bodyBytes, charset) < 0) {
                return candidate;
            }
        }
        return null;
    }
}
