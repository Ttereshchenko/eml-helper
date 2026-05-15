package com.github.ttereshchenko.mailkit.psi;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Stateless parsing helpers used by both the parser and the PSI to interpret
 * header lines without owning any tree state.
 */
public final class EmlHeaderParsing {

    public static final String CONTENT_TYPE = "Content-Type";
    public static final String MESSAGE_RFC822 = "message/rfc822";
    public static final String MULTIPART_PREFIX = "multipart/";

    private EmlHeaderParsing() {}

    public static String headerName(String firstLine) {
        Objects.requireNonNull(firstLine, "firstLine");
        var colon = firstLine.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        var name = firstLine.substring(0, colon).trim();
        return name.isEmpty() ? null : name;
    }

    public static String joinValue(String firstLine, List<String> continuations) {
        Objects.requireNonNull(firstLine, "firstLine");
        Objects.requireNonNull(continuations, "continuations");
        var colon = firstLine.indexOf(':');
        var head = colon < 0 ? "" : firstLine.substring(colon + 1).strip();
        if (continuations.isEmpty()) {
            return head;
        }
        var joined = new StringBuilder(head);
        for (var line : continuations) {
            if (!joined.isEmpty()) {
                joined.append(' ');
            }
            joined.append(line.strip());
        }
        return joined.toString();
    }

    public static String mediaType(String contentTypeValue) {
        if (contentTypeValue == null || contentTypeValue.isEmpty()) {
            return null;
        }
        var semicolon = contentTypeValue.indexOf(';');
        var raw = (semicolon < 0 ? contentTypeValue : contentTypeValue.substring(0, semicolon)).trim();
        return raw.isEmpty() ? null : raw.toLowerCase(Locale.ROOT);
    }

    public static String mediaTypeParam(String contentTypeValue, String paramName) {
        if (contentTypeValue == null) {
            return null;
        }
        Objects.requireNonNull(paramName, "paramName");
        var semicolon = contentTypeValue.indexOf(';');
        if (semicolon < 0) {
            return null;
        }
        var params = contentTypeValue.substring(semicolon + 1);
        var target = paramName.toLowerCase(Locale.ROOT);
        var idx = 0;
        while (idx < params.length()) {
            while (idx < params.length() && Character.isWhitespace(params.charAt(idx))) {
                idx++;
            }
            var nameStart = idx;
            while (idx < params.length() && params.charAt(idx) != '=' && params.charAt(idx) != ';') {
                idx++;
            }
            var name = params.substring(nameStart, idx).trim().toLowerCase(Locale.ROOT);
            String value = null;
            if (idx < params.length() && params.charAt(idx) == '=') {
                idx++;
                while (idx < params.length() && Character.isWhitespace(params.charAt(idx))) {
                    idx++;
                }
                if (idx < params.length() && params.charAt(idx) == '"') {
                    idx++;
                    var unescaped = new StringBuilder();
                    while (idx < params.length() && params.charAt(idx) != '"') {
                        if (params.charAt(idx) == '\\' && idx + 1 < params.length()) {
                            unescaped.append(params.charAt(idx + 1));
                            idx += 2;
                        } else {
                            unescaped.append(params.charAt(idx));
                            idx++;
                        }
                    }
                    if (idx < params.length()) {
                        idx++;
                    }
                    value = unescaped.toString();
                } else {
                    var valStart = idx;
                    while (idx < params.length()
                            && params.charAt(idx) != ';'
                            && !Character.isWhitespace(params.charAt(idx))) {
                        idx++;
                    }
                    value = params.substring(valStart, idx);
                }
            }
            if (name.equals(target) && value != null) {
                return value;
            }
            while (idx < params.length() && params.charAt(idx) != ';') {
                idx++;
            }
            if (idx < params.length()) {
                idx++;
            }
        }
        return null;
    }

    public static boolean isMultipart(String contentTypeValue) {
        var media = mediaType(contentTypeValue);
        return media != null && media.startsWith(MULTIPART_PREFIX);
    }

    public static boolean isMessageRfc822(String contentTypeValue) {
        return MESSAGE_RFC822.equalsIgnoreCase(mediaType(contentTypeValue));
    }
}
