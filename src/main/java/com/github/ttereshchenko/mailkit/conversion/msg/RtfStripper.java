package com.github.ttereshchenko.mailkit.conversion.msg;

import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Set;

/**
 * Best-effort RTF → plain text fallback used only when an MSG has neither an HTML nor a plain text
 * body. Strips groups, control words, and common escapes; not a faithful RTF renderer.
 */
final class RtfStripper {

    private static final Charset RTF_CHARSET = Charset.forName("windows-1252");
    private static final Set<String> SKIPPED_GROUP_DESTINATIONS =
            Set.of("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "object");

    private RtfStripper() {}

    static String strip(String rtfText) {
        Objects.requireNonNull(rtfText, "rtfText");
        if (rtfText.isEmpty()) {
            return "";
        }
        var source = rtfText;
        var output = new StringBuilder(source.length());
        var skipDepth = 0;
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
                                output.append(new String(bytes, RTF_CHARSET));
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
                        if (skipDepth == 0 && codepoint >= 0) {
                            output.appendCodePoint(codepoint);
                        }
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                    index = end;
                    if (index < source.length() && source.charAt(index) == '?') {
                        index++;
                    } else if (index < source.length() && source.charAt(index) == ' ') {
                        index++;
                    } else if (index < source.length()) {
                        index++;
                    }
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
