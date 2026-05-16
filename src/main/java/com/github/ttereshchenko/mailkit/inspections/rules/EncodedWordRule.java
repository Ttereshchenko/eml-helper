package com.github.ttereshchenko.mailkit.inspections.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Detects byte ranges in a header value that contain non-ASCII characters
 * outside an RFC 2047 encoded-word region. Structured headers (From, To, Cc,
 * Subject, etc.) must wrap non-ASCII text in {@code =?charset?Q|B?text?=}.
 */
public final class EncodedWordRule {

    public static final Set<String> STRUCTURED_HEADERS = Set.of(
            "from",
            "to",
            "cc",
            "bcc",
            "reply-to",
            "sender",
            "subject",
            "resent-from",
            "resent-to",
            "resent-cc",
            "resent-bcc",
            "resent-sender",
            "resent-reply-to");

    private EncodedWordRule() {}

    public static boolean isStructured(String headerName) {
        return headerName != null && STRUCTURED_HEADERS.contains(headerName.toLowerCase(java.util.Locale.ROOT));
    }

    public static List<LineRange> findUnencodedNonAscii(String headerValue) {
        Objects.requireNonNull(headerValue, "headerValue");
        var result = new ArrayList<LineRange>();
        var idx = 0;
        while (idx < headerValue.length()) {
            if (startsEncodedWord(headerValue, idx)) {
                idx = skipEncodedWord(headerValue, idx);
                continue;
            }
            var character = headerValue.charAt(idx);
            if (character > 0x7F) {
                var runStart = idx;
                while (idx < headerValue.length()
                        && headerValue.charAt(idx) > 0x7F
                        && !startsEncodedWord(headerValue, idx)) {
                    idx++;
                }
                result.add(new LineRange(runStart, idx));
            } else {
                idx++;
            }
        }
        return result;
    }

    private static boolean startsEncodedWord(String text, int pos) {
        return pos + 1 < text.length() && text.charAt(pos) == '=' && text.charAt(pos + 1) == '?';
    }

    private static int skipEncodedWord(String text, int pos) {
        var question = pos + 2;
        var endMarker = text.indexOf("?=", question);
        if (endMarker < 0) {
            return text.length();
        }
        return endMarker + 2;
    }
}
