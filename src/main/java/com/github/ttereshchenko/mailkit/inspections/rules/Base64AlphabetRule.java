package com.github.ttereshchenko.mailkit.inspections.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detects characters in a base64-encoded body that are outside the RFC 4648
 * alphabet ({@code A–Z a–z 0–9 + / =}). Whitespace and CR/LF are tolerated.
 */
public final class Base64AlphabetRule {

    private Base64AlphabetRule() {}

    public static int firstInvalid(String body) {
        Objects.requireNonNull(body, "body");
        for (var idx = 0; idx < body.length(); idx++) {
            if (!isAlphabetOrWhitespace(body.charAt(idx))) {
                return idx;
            }
        }
        return -1;
    }

    public static List<LineRange> invalidRuns(String body) {
        Objects.requireNonNull(body, "body");
        var result = new ArrayList<LineRange>();
        var idx = 0;
        while (idx < body.length()) {
            if (!isAlphabetOrWhitespace(body.charAt(idx))) {
                var start = idx;
                while (idx < body.length() && !isAlphabetOrWhitespace(body.charAt(idx))) {
                    idx++;
                }
                result.add(new LineRange(start, idx));
            } else {
                idx++;
            }
        }
        return result;
    }

    private static boolean isAlphabetOrWhitespace(char character) {
        if (Character.isWhitespace(character)) {
            return true;
        }
        if (character >= 'A' && character <= 'Z') {
            return true;
        }
        if (character >= 'a' && character <= 'z') {
            return true;
        }
        if (character >= '0' && character <= '9') {
            return true;
        }
        return character == '+' || character == '/' || character == '=';
    }
}
