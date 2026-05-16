package com.github.ttereshchenko.mailkit.inspections.rules;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detects lines exceeding the RFC 5322 §2.1.1 limit of 998 octets per line
 * (excluding the trailing CRLF). The rule works in UTF-8 octet space so a
 * multi-byte character that pushes the line past the limit is reported.
 */
public final class LineLengthRule {

    public static final int MAX_LINE_OCTETS = 998;

    private LineLengthRule() {}

    public static List<LineRange> findLongLines(String text) {
        Objects.requireNonNull(text, "text");
        var result = new ArrayList<LineRange>();
        var index = 0;
        var lineStart = 0;
        while (index <= text.length()) {
            var atEnd = index == text.length();
            var character = atEnd ? '\n' : text.charAt(index);
            if (character == '\n' || atEnd) {
                var contentEnd = index;
                if (contentEnd > lineStart && text.charAt(contentEnd - 1) == '\r') {
                    contentEnd--;
                }
                var line = text.substring(lineStart, contentEnd);
                if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_OCTETS) {
                    result.add(new LineRange(lineStart, contentEnd));
                }
                lineStart = index + 1;
            }
            index++;
        }
        return result;
    }
}
