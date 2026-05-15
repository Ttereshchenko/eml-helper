package com.github.ttereshchenko.mailkit.psi;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Decodes RFC 2047 encoded-words: <code>=?charset?Q|B?text?=</code>.
 * Adjacent encoded words separated only by whitespace concatenate (whitespace consumed).
 */
public final class EmlEncodedWord {

    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?\\s]+)\\?([QqBb])\\?([^?]*)\\?=");

    private EmlEncodedWord() {}

    public static String decode(String input) {
        Objects.requireNonNull(input, "input");
        if (!input.contains("=?")) {
            return input;
        }

        var matcher = ENCODED_WORD.matcher(input);
        var out = new StringBuilder(input.length());
        var lastEnd = 0;
        var prevWasEncoded = false;

        while (matcher.find()) {
            var between = input.substring(lastEnd, matcher.start());
            if (!(prevWasEncoded && between.chars().allMatch(Character::isWhitespace))) {
                out.append(between);
            }

            var decoded = decodeWord(matcher.group(1), matcher.group(2), matcher.group(3));
            if (decoded != null) {
                out.append(decoded);
                prevWasEncoded = true;
            } else {
                out.append(matcher.group(0));
                prevWasEncoded = false;
            }
            lastEnd = matcher.end();
        }
        out.append(input, lastEnd, input.length());
        return out.toString();
    }

    private static String decodeWord(String charsetName, String encoding, String text) {
        try {
            var charset = Charset.forName(charsetName);
            var bytes = encoding.equalsIgnoreCase("Q")
                    ? decodeQ(text)
                    : Base64.getDecoder().decode(text);
            return new String(bytes, charset);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static byte[] decodeQ(String text) {
        var out = new ByteArrayOutputStream(text.length());
        for (var idx = 0; idx < text.length(); idx++) {
            var character = text.charAt(idx);
            switch (character) {
                case '_' -> out.write(0x20);
                case '=' -> {
                    if (idx + 2 >= text.length()) {
                        throw new IllegalArgumentException("truncated Q-encoding");
                    }
                    var highNibble = Character.digit(text.charAt(idx + 1), 16);
                    var lowNibble = Character.digit(text.charAt(idx + 2), 16);
                    if (highNibble < 0 || lowNibble < 0) {
                        throw new IllegalArgumentException("invalid hex in Q-encoding");
                    }
                    out.write((highNibble << 4) | lowNibble);
                    idx += 2;
                }
                default -> out.write((byte) character);
            }
        }
        return out.toByteArray();
    }
}
