package com.github.ttereshchenko.mailkit.conversion.msg;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * RTF helpers for the MSG converter. {@link #strip} is a best-effort RTF → plain text fallback used
 * when an MSG has neither an HTML nor a plain text body; {@link #deEncapsulateHtml} recovers HTML from
 * HTML-encapsulated RTF (MS-OXRTFEX). Neither is a faithful RTF renderer.
 */
final class RtfStripper {

    private static final Charset DEFAULT_RTF_CHARSET = Charset.forName("windows-1252");
    private static final Pattern ANSICPG_PATTERN = Pattern.compile("\\\\ansicpg(\\d+)");
    private static final Set<String> SKIPPED_GROUP_DESTINATIONS =
            Set.of("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "object");

    private RtfStripper() {}

    static String strip(String rtfText) {
        Objects.requireNonNull(rtfText, "rtfText");
        if (rtfText.isEmpty()) {
            return "";
        }
        var source = rtfText;
        var charset = resolveCharset(source);
        var output = new StringBuilder(source.length());
        var skipDepth = 0;
        var unicodeSkip = 1;
        var index = 0;
        while (index < source.length()) {
            var character = source.charAt(index);
            if (character == '\\') {
                if (index + 1 >= source.length()) {
                    index++;
                    continue;
                }
                var next = source.charAt(index + 1);
                if (next == '\\' || next == '{' || next == '}') {
                    if (skipDepth == 0) {
                        output.append(next);
                    }
                    index += 2;
                    continue;
                }
                if (next == '*') {
                    index += 2;
                    skipDepth = Math.max(skipDepth, 1);
                    continue;
                }
                if (next == '\'') {
                    var hex = source.substring(index + 2, Math.min(index + 4, source.length()));
                    if (hex.length() == 2) {
                        try {
                            var value = Integer.parseInt(hex, 16);
                            if (skipDepth == 0) {
                                var bytes = new byte[] {(byte) value};
                                output.append(new String(bytes, charset));
                            }
                            index += 4;
                            continue;
                        } catch (NumberFormatException ignored) {
                            index += 2;
                            continue;
                        }
                    }
                    index += 2;
                    continue;
                }
                if (next == 'u'
                        && index + 2 < source.length()
                        && (source.charAt(index + 2) == '-' || Character.isDigit(source.charAt(index + 2)))) {
                    var start = index + 2;
                    var end = start;
                    if (source.charAt(end) == '-') {
                        end++;
                    }
                    while (end < source.length() && Character.isDigit(source.charAt(end))) {
                        end++;
                    }
                    try {
                        var codepoint = Integer.parseInt(source.substring(start, end));
                        if (codepoint < 0) {
                            codepoint += 0x10000;
                        }
                        // Guard the full Unicode range, not just the lower bound: a Unicode control word may
                        // carry an in-int-range but out-of-range value (e.g. 2000000), which appendCodePoint
                        // rejects with an IllegalArgumentException that would otherwise escape the converter.
                        if (skipDepth == 0 && Character.isValidCodePoint(codepoint)) {
                            output.appendCodePoint(codepoint);
                        }
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                    index = end;
                    // A control word's numeric argument may be followed by a single delimiting space.
                    if (index < source.length() && source.charAt(index) == ' ') {
                        index++;
                    }
                    // Skip the uc-many ANSI fallback characters that follow each Unicode escape (default 1).
                    index = skipUnicodeFallback(source, index, unicodeSkip);
                    continue;
                }
                if (Character.isLetter(next)) {
                    var start = index + 1;
                    var end = start;
                    while (end < source.length() && Character.isLetter(source.charAt(end))) {
                        end++;
                    }
                    var name = source.substring(start, end);
                    var paramStart = end;
                    if (end < source.length() && (source.charAt(end) == '-' || Character.isDigit(source.charAt(end)))) {
                        if (source.charAt(end) == '-') {
                            end++;
                        }
                        while (end < source.length() && Character.isDigit(source.charAt(end))) {
                            end++;
                        }
                    }
                    var hadParam = end > paramStart;
                    if (name.equals("uc") && hadParam) {
                        try {
                            unicodeSkip = Math.max(0, Integer.parseInt(source.substring(paramStart, end)));
                        } catch (NumberFormatException ignored) {
                            // keep the current skip count
                        }
                    }
                    var replacement = controlReplacement(name, hadParam);
                    if (skipDepth == 0 && replacement != null) {
                        output.append(replacement);
                    }
                    if (SKIPPED_GROUP_DESTINATIONS.contains(name)) {
                        skipDepth = Math.max(skipDepth, 1);
                    }
                    index = end;
                    if (index < source.length() && source.charAt(index) == ' ') {
                        index++;
                    }
                    continue;
                }
                index += 2;
                continue;
            }
            if (character == '{') {
                if (skipDepth > 0) {
                    skipDepth++;
                }
                index++;
                continue;
            }
            if (character == '}') {
                if (skipDepth > 0) {
                    skipDepth--;
                }
                index++;
                continue;
            }
            if (character == '\r' || character == '\n') {
                index++;
                continue;
            }
            if (skipDepth == 0) {
                output.append(character);
            }
            index++;
        }
        return output.toString().trim();
    }

    /** True if the RTF is HTML-encapsulated (RTF-to-HTML, MS-OXRTFEX) rather than ordinary rich text. */
    static boolean isHtmlEncapsulated(String rtfText) {
        return rtfText != null && rtfText.contains("\\fromhtml");
    }

    /**
     * De-encapsulates HTML wrapped in RTF (MS-OXRTFEX): honors {@code \\htmlrtf}/{@code \\htmlrtf0}
     * toggling, extracts <code>{\*\htmltag ...}</code> runs, and decodes {@code \\'hh} / {@code \\uN}
     * escapes. Mirrors the PST converter's de-encapsulation so HTML-encapsulated MSG bodies are
     * recovered as text/html instead of leaking literal tags into a stripped-to-plain fallback.
     */
    static String deEncapsulateHtml(String rtfText) {
        Objects.requireNonNull(rtfText, "rtfText");
        var charset = resolveCharset(rtfText);
        var html = new StringBuilder(rtfText.length());
        var index = 0;
        var inHtmlRtf = false;
        while (index < rtfText.length()) {
            if (rtfText.startsWith("\\htmlrtf0", index)) {
                inHtmlRtf = false;
                index += 9;
                if (index < rtfText.length() && rtfText.charAt(index) == ' ') index++;
                continue;
            } else if (rtfText.startsWith("\\htmlrtf", index)) {
                inHtmlRtf = true;
                index += 8;
                if (index < rtfText.length() && rtfText.charAt(index) == ' ') index++;
                continue;
            }

            if (rtfText.startsWith("{\\*\\htmltag", index)) {
                var end = rtfText.indexOf('}', index);
                if (end != -1) {
                    var tag = rtfText.substring(index + 11, end).trim();
                    tag = tag.replaceFirst("^\\d+\\s*", "");
                    if (!tag.equals("\\par") && !tag.matches("\\d+")) {
                        html.append(tag);
                    } else if (tag.equals("\\par")) {
                        html.append("\r\n");
                    }
                    index = end + 1;
                    continue;
                }
            }
            if (inHtmlRtf) {
                index++;
                continue;
            }
            var character = rtfText.charAt(index);
            if (character == '{' || character == '}') {
                index++;
                continue;
            }
            if (character == '\\') {
                if (index + 3 < rtfText.length() && rtfText.charAt(index + 1) == '\'') {
                    var hex = rtfText.substring(index + 2, index + 4);
                    try {
                        var bytes = new byte[] {(byte) Integer.parseInt(hex, 16)};
                        html.append(new String(bytes, charset));
                    } catch (NumberFormatException ignored) {
                        // malformed \'hh escape — skip this byte
                    }
                    index += 4;
                    continue;
                }
                if (index + 2 < rtfText.length()
                        && rtfText.charAt(index + 1) == 'u'
                        && (Character.isDigit(rtfText.charAt(index + 2)) || rtfText.charAt(index + 2) == '-')) {
                    var endNum = index + 2;
                    if (rtfText.charAt(endNum) == '-') endNum++;
                    while (endNum < rtfText.length() && Character.isDigit(rtfText.charAt(endNum))) endNum++;
                    try {
                        short codePoint = Short.parseShort(rtfText.substring(index + 2, endNum));
                        html.append((char) codePoint);
                    } catch (NumberFormatException ignored) {
                        // malformed \\uN escape — skip this code point
                    }
                    index = endNum;
                    // skip the substitute character that follows the Unicode escape
                    if (index < rtfText.length() && rtfText.charAt(index) == ' ') index++;
                    else if (index + 3 < rtfText.length()
                            && rtfText.charAt(index) == '\\'
                            && rtfText.charAt(index + 1) == '\'') index += 4;
                    else if (index < rtfText.length() && rtfText.charAt(index) == '?') index++;
                    continue;
                }
                var nextSpace = rtfText.indexOf(' ', index);
                var nextSlash = rtfText.indexOf('\\', index + 1);
                var nextBrace = rtfText.indexOf('{', index + 1);
                var nextClose = rtfText.indexOf('}', index + 1);

                var end = rtfText.length();
                if (nextSpace != -1) end = Math.min(end, nextSpace);
                if (nextSlash != -1) end = Math.min(end, nextSlash);
                if (nextBrace != -1) end = Math.min(end, nextBrace);
                if (nextClose != -1) end = Math.min(end, nextClose);

                index = end;
                if (index < rtfText.length() && rtfText.charAt(index) == ' ') index++; // skip trailing space
                continue;
            }
            html.append(character);
            index++;
        }
        return html.toString().trim();
    }

    private static Charset resolveCharset(String rtf) {
        var matcher = ANSICPG_PATTERN.matcher(rtf);
        if (!matcher.find()) {
            return DEFAULT_RTF_CHARSET;
        }
        var codePage = matcher.group(1);
        if ("65001".equals(codePage)) {
            return StandardCharsets.UTF_8;
        }
        // windows-<cp> covers 1250-1258/874; Cp<cp> covers the DBCS pages (932/936/949/950).
        for (var candidate : new String[] {"windows-" + codePage, "Cp" + codePage}) {
            try {
                return Charset.forName(candidate);
            } catch (RuntimeException ignored) {
                // try the next alias, then fall back below
            }
        }
        return DEFAULT_RTF_CHARSET;
    }

    /**
     * Skips the {@code count} ANSI fallback "characters" that trail a {@code \\u} escape, honoring the
     * current {@code \\uc} value. Each fallback may be a literal char, a {@code \\'hh} hex byte, or a
     * control word/symbol; a group brace ends the skip.
     */
    private static int skipUnicodeFallback(String source, int index, int count) {
        var skipped = 0;
        while (skipped < count && index < source.length()) {
            var character = source.charAt(index);
            if (character == '{' || character == '}') {
                break;
            }
            if (character == '\\' && index + 1 < source.length()) {
                var next = source.charAt(index + 1);
                if (next == '\'') {
                    index = Math.min(source.length(), index + 4);
                } else if (Character.isLetter(next)) {
                    index += 2;
                    while (index < source.length() && Character.isLetter(source.charAt(index))) {
                        index++;
                    }
                    if (index < source.length()
                            && (source.charAt(index) == '-' || Character.isDigit(source.charAt(index)))) {
                        if (source.charAt(index) == '-') {
                            index++;
                        }
                        while (index < source.length() && Character.isDigit(source.charAt(index))) {
                            index++;
                        }
                    }
                    if (index < source.length() && source.charAt(index) == ' ') {
                        index++;
                    }
                } else {
                    index += 2;
                }
            } else {
                index++;
            }
            skipped++;
        }
        return index;
    }

    private static String controlReplacement(String name, boolean hadParam) {
        return switch (name) {
            case "par", "line", "pard" -> "\n";
            case "tab" -> "\t";
            case "lquote", "rquote" -> "'";
            case "ldblquote", "rdblquote" -> "\"";
            case "emdash" -> "—";
            case "endash" -> "–";
            case "bullet" -> "•";
            default -> null;
        };
    }
}
