package com.github.ttereshchenko.mailkit.inspections.rules;

import java.util.Objects;

/**
 * Detects whether the {@code boundary=} parameter of a Content-Type header value
 * is unquoted while containing characters that RFC 2045 §5.1 requires to be quoted
 * (whitespace or any tspecial except '_').
 *
 * <p>The result, if non-null, carries the absolute offsets of the boundary token
 * inside the input string so the caller can build a precise highlight range.
 */
public final class BoundaryQuotingRule {

    /** Characters from the RFC 2045 tspecials set, plus whitespace. Any of these forces quoting. */
    private static final String TSPECIALS = "()<>@,;:\\\"/[]?= \t";

    private BoundaryQuotingRule() {}

    public record UnquotedBoundary(String value, int valueStart, int valueEnd) {}

    public static UnquotedBoundary scan(String contentTypeValue) {
        if (contentTypeValue == null) {
            return null;
        }
        var semicolon = contentTypeValue.indexOf(';');
        if (semicolon < 0) {
            return null;
        }
        var idx = semicolon + 1;
        while (idx < contentTypeValue.length()) {
            while (idx < contentTypeValue.length() && Character.isWhitespace(contentTypeValue.charAt(idx))) {
                idx++;
            }
            var nameStart = idx;
            while (idx < contentTypeValue.length()
                    && contentTypeValue.charAt(idx) != '='
                    && contentTypeValue.charAt(idx) != ';') {
                idx++;
            }
            var name = contentTypeValue.substring(nameStart, idx).trim().toLowerCase(java.util.Locale.ROOT);
            if (idx < contentTypeValue.length() && contentTypeValue.charAt(idx) == '=') {
                idx++;
                while (idx < contentTypeValue.length() && Character.isWhitespace(contentTypeValue.charAt(idx))) {
                    idx++;
                }
                var quoted = idx < contentTypeValue.length() && contentTypeValue.charAt(idx) == '"';
                if (quoted) {
                    idx++;
                    while (idx < contentTypeValue.length() && contentTypeValue.charAt(idx) != '"') {
                        if (contentTypeValue.charAt(idx) == '\\' && idx + 1 < contentTypeValue.length()) {
                            idx += 2;
                        } else {
                            idx++;
                        }
                    }
                    if (idx < contentTypeValue.length()) {
                        idx++;
                    }
                } else {
                    var valStart = idx;
                    while (idx < contentTypeValue.length()
                            && contentTypeValue.charAt(idx) != ';'
                            && contentTypeValue.charAt(idx) != '\r'
                            && contentTypeValue.charAt(idx) != '\n') {
                        idx++;
                    }
                    var valEnd = idx;
                    while (valEnd > valStart
                            && (contentTypeValue.charAt(valEnd - 1) == ' '
                                    || contentTypeValue.charAt(valEnd - 1) == '\t')) {
                        valEnd--;
                    }
                    var raw = contentTypeValue.substring(valStart, valEnd);
                    if ("boundary".equals(name) && requiresQuoting(raw)) {
                        return new UnquotedBoundary(raw, valStart, valEnd);
                    }
                }
            }
            while (idx < contentTypeValue.length() && contentTypeValue.charAt(idx) != ';') {
                idx++;
            }
            if (idx < contentTypeValue.length()) {
                idx++;
            }
        }
        return null;
    }

    public static boolean requiresQuoting(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            return true;
        }
        for (var pos = 0; pos < value.length(); pos++) {
            if (TSPECIALS.indexOf(value.charAt(pos)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
