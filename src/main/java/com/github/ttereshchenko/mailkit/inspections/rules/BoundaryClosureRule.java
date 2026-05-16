package com.github.ttereshchenko.mailkit.inspections.rules;

import java.util.Objects;

/**
 * Detects multipart parts that declare a {@code boundary=X} but never close
 * with the matching {@code --X--} terminator. Scans the part body line-by-line
 * for an exact match (RFC 2046 §5.1.1 requires the closing delimiter to be on
 * its own line, optionally followed by transport padding).
 */
public final class BoundaryClosureRule {

    private BoundaryClosureRule() {}

    public static boolean hasClosingMarker(String body, String boundary) {
        Objects.requireNonNull(boundary, "boundary");
        if (body == null) {
            return false;
        }
        var marker = "--" + boundary + "--";
        var lineStart = 0;
        while (lineStart <= body.length()) {
            var newline = body.indexOf('\n', lineStart);
            var lineEnd = newline < 0 ? body.length() : newline;
            var contentEnd = lineEnd;
            if (contentEnd > lineStart && body.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            var line = body.substring(lineStart, contentEnd);
            if (matches(line, marker)) {
                return true;
            }
            if (newline < 0) {
                break;
            }
            lineStart = newline + 1;
        }
        return false;
    }

    private static boolean matches(String line, String marker) {
        if (!line.startsWith(marker)) {
            return false;
        }
        for (var idx = marker.length(); idx < line.length(); idx++) {
            var character = line.charAt(idx);
            if (character != ' ' && character != '\t') {
                return false;
            }
        }
        return true;
    }
}
