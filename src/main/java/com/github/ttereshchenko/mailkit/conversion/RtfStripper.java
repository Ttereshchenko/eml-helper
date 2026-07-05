package com.github.ttereshchenko.mailkit.conversion;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * RTF helpers for the MSG converter. {@link #strip} is a best-effort RTF → plain text fallback used
 * when an MSG has neither an HTML nor a plain text body; {@link #deEncapsulateHtml} recovers HTML from
 * HTML-encapsulated RTF (MS-OXRTFEX). Neither is a faithful RTF renderer.
 */
public final class RtfStripper {

    private static final Charset DEFAULT_RTF_CHARSET = Charset.forName("windows-1252");
    private static final Pattern ANSICPG_PATTERN = Pattern.compile("\\\\ansicpg(\\d+)");
    private static final Set<String> SKIPPED_GROUP_DESTINATIONS =
            Set.of("fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "object");

    private RtfStripper() {}

    public static String strip(String rtfText) {
        Objects.requireNonNull(rtfText, "rtfText");
        if (rtfText.isEmpty()) {
            return "";
        }
        var source = rtfText;
        var charset = resolveCharset(source);
        var output = new StringBuilder(source.length());
        var skipDepth = 0;
        var unicodeSkip = 1;
        // The RTF "uc" control word (the Unicode fallback-skip count) is a group-scoped character
        // property: a uc value set inside {...} must revert at the closing brace. Save it on '{' and
        // restore on '}' (mirrors deEncapsulateHtml) so a per-group uc count does not leak out and
        // mis-skip the ANSI fallback of later Unicode escapes.
        var groupState = new ArrayDeque<Integer>();
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
                    // Decode the whole run of consecutive \'hh escapes together: a DBCS or UTF-8
                    // (\ansicpg65001) code page stores one character as several adjacent bytes, so
                    // decoding each \'hh in isolation would mojibake every multibyte character.
                    var run = decodeHexRun(source, index, charset);
                    if (skipDepth == 0) {
                        output.append(run.text());
                    }
                    index = run.nextIndex();
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
                    var replacement = controlReplacement(name);
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
                    // \binN introduces exactly N bytes of raw binary picture data (RTF spec /
                    // [MS-OXRTFCP] §2.1.3.1.5): those bytes are NOT RTF and must be consumed wholesale,
                    // otherwise they leak into the plain-text fallback and can also contain stray
                    // backslashes/braces that desynchronise the parser. A negative or unparsable count
                    // is treated as zero.
                    if (name.equals("bin") && hadParam) {
                        try {
                            var binaryLength = Integer.parseInt(source.substring(paramStart, end));
                            if (binaryLength > 0) {
                                index = Math.min(source.length(), index + binaryLength);
                            }
                        } catch (NumberFormatException ignored) {
                            // no countable binary payload — skip nothing
                        }
                    }
                    continue;
                }
                // Control symbols that denote visible whitespace: `\~` is a non-breaking space and `\_` a
                // non-breaking hyphen ([MS-OXRTFEX]/RTF spec §Special Characters). Emitting nothing for them
                // welds the surrounding words ("Mr.\~Smith" -> "Mr.Smith", "page\~1" -> "page1"); emit the
                // Unicode character they denote instead. `\-` (optional hyphen) is invisible and stays
                // dropped, as does every other unhandled control symbol.
                if (next == '~') {
                    if (skipDepth == 0) {
                        output.append(' ');
                    }
                    index += 2;
                    continue;
                }
                if (next == '_') {
                    if (skipDepth == 0) {
                        output.append('‑');
                    }
                    index += 2;
                    continue;
                }
                index += 2;
                continue;
            }
            if (character == '{') {
                groupState.push(unicodeSkip);
                if (skipDepth > 0) {
                    skipDepth++;
                }
                index++;
                continue;
            }
            if (character == '}') {
                if (!groupState.isEmpty()) {
                    unicodeSkip = groupState.pop();
                }
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
    public static boolean isHtmlEncapsulated(String rtfText) {
        return rtfText != null && rtfText.contains("\\fromhtml");
    }

    /**
     * True if the RTF is {@code \fromtext}-encapsulated plain text ([MS-OXRTFEX] §2.1.3.1): a copy of the
     * message's plain-text body re-wrapped as RTF, carrying no rich formatting of its own. Mirrors the PST
     * parser's {@code Message.isEncapsulationRtf}, which treats {@code \fromtext} the same as {@code \fromhtml}.
     */
    public static boolean isTextEncapsulated(String rtfText) {
        return rtfText != null && rtfText.contains("\\fromtext");
    }

    public static String deEncapsulateHtml(String rtfText) {
        Objects.requireNonNull(rtfText, "rtfText");
        var charset = resolveCharset(rtfText);
        var html = new StringBuilder(rtfText.length());
        var index = 0;
        var inHtmlRtf = false;
        var unicodeSkip = 1;
        // RTF group state ({inHtmlRtf, unicodeSkip}) is saved on '{' and restored on '}' so a toggle or
        // \\ucN set inside a group does not leak past its closing brace (RTF spec / MS-OXRTFEX grouping).
        var groupState = new ArrayDeque<int[]>();
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

            // \binN is followed by N bytes of raw binary data (RTF spec / [MS-OXRTFCP] §2.1.3.1.5):
            // the payload is not RTF and must be consumed wholesale, or it leaks into the recovered
            // HTML and any stray brace byte in it pops the group stack and desyncs all later output.
            // Handled before the brace/suppression branches below so it also applies inside \htmlrtf
            // runs. Mirrors strip() and the PST fork Message.extractHtmlFromRtf.
            var afterBin = skipBin(rtfText, index);
            if (afterBin != index) {
                index = afterBin;
                continue;
            }

            if (rtfText.startsWith("{\\*\\htmltag", index)) {
                index = appendHtmlTag(rtfText, index, charset, html, unicodeSkip);
                continue;
            }
            // Any other {\*\…} ignorable destination (\mhtmltag, \generator, \fldinst, \datafield, …)
            // carries no recovered HTML and must be skipped whole (RTF spec / [MS-OXRTF]: an
            // unrecognized \* destination group is dropped). Without this, the generic control-word
            // skip below swallows only the \* and control words and then appends the destination's
            // literal text as HTML — e.g. the \mhtmltag paired with each \htmltag leaks a duplicate
            // <img>/<a> tag. strip() suppresses these via skipDepth; here we skip to the close brace.
            if (rtfText.startsWith("{\\*\\", index)) {
                index = skipIgnorableGroup(rtfText, index);
                continue;
            }
            // The fonttbl/colortbl/stylesheet/info header tables and the {\pict}/{\object} picture and
            // embedded-object groups are RTF infrastructure, not de-encapsulated HTML ([MS-OXRTFEX]
            // section 2.1.3.1: only htmltag destinations and the non-htmlrtf character runs carry HTML).
            // They are not the {\*\…} ignorable destinations the skip above handles, so without dropping
            // them whole the generic control-word skip below eats only the keyword and then leaks the
            // group's literal text (font face names, the colortbl ';' separators, style names, the
            // picture's hex/\bin payload) into the body. The PST fork (Message.extractHtmlFromRtf via
            // isNonRenderableGroupStart) skips the same set.
            if (rtfText.startsWith("{\\fonttbl", index)
                    || rtfText.startsWith("{\\colortbl", index)
                    || rtfText.startsWith("{\\stylesheet", index)
                    || rtfText.startsWith("{\\info", index)
                    || rtfText.startsWith("{\\pict", index)
                    || rtfText.startsWith("{\\object", index)) {
                index = skipIgnorableGroup(rtfText, index);
                continue;
            }
            var character = rtfText.charAt(index);
            // Maintain the group-state stack even inside an \htmlrtf-suppressed run so it stays balanced;
            // a '{' saves the current state and the matching '}' restores it.
            if (character == '{') {
                groupState.push(new int[] {inHtmlRtf ? 1 : 0, unicodeSkip});
                index++;
                continue;
            }
            if (character == '}') {
                if (!groupState.isEmpty()) {
                    var restored = groupState.pop();
                    inHtmlRtf = restored[0] != 0;
                    unicodeSkip = restored[1];
                }
                index++;
                continue;
            }
            if (inHtmlRtf) {
                index++;
                continue;
            }
            if (character == '\\') {
                if (index + 1 < rtfText.length() && rtfText.charAt(index + 1) == '\'') {
                    // Decode the whole run of consecutive \'hh escapes together so multibyte
                    // (DBCS / UTF-8) characters are not split into per-byte U+FFFD / '?' mojibake.
                    var run = decodeHexRun(rtfText, index, charset);
                    html.append(run.text());
                    index = run.nextIndex();
                    continue;
                }
                // \\ucN sets how many fallback characters trail each \\uN escape; it must be matched
                // before the \\uN branch because the two control words share the "\\u" prefix.
                if (rtfText.startsWith("\\uc", index)
                        && index + 3 < rtfText.length()
                        && Character.isDigit(rtfText.charAt(index + 3))) {
                    var end = index + 3;
                    while (end < rtfText.length() && Character.isDigit(rtfText.charAt(end))) end++;
                    try {
                        unicodeSkip = Math.max(0, Integer.parseInt(rtfText.substring(index + 3, end)));
                    } catch (NumberFormatException ignored) {
                        // keep the current skip count
                    }
                    index = end;
                    if (index < rtfText.length() && rtfText.charAt(index) == ' ') index++;
                    continue;
                }
                if (index + 2 < rtfText.length()
                        && rtfText.charAt(index + 1) == 'u'
                        && (Character.isDigit(rtfText.charAt(index + 2)) || rtfText.charAt(index + 2) == '-')) {
                    var endNum = index + 2;
                    if (rtfText.charAt(endNum) == '-') endNum++;
                    while (endNum < rtfText.length() && Character.isDigit(rtfText.charAt(endNum))) endNum++;
                    try {
                        var codePoint = Integer.parseInt(rtfText.substring(index + 2, endNum));
                        if (codePoint < 0) {
                            // \\uN carries a signed 16-bit value; normalize like strip() does.
                            codePoint += 0x10000;
                        }
                        if (Character.isValidCodePoint(codePoint)) {
                            html.appendCodePoint(codePoint);
                        }
                    } catch (NumberFormatException ignored) {
                        // malformed \\uN escape — skip this code point
                    }
                    index = endNum;
                    // A control word's numeric argument may be followed by a single delimiting space.
                    if (index < rtfText.length() && rtfText.charAt(index) == ' ') index++;
                    // Skip the \\uc-many ANSI fallback characters; honoring the declared count keeps a
                    // literal (non-'?') fallback from leaking into the output as a duplicate.
                    index = skipUnicodeFallback(rtfText, index, unicodeSkip);
                    continue;
                }
                if (index + 1 < rtfText.length() && !Character.isLetter(rtfText.charAt(index + 1))) {
                    // A backslash followed by a non-letter is an RTF control symbol (\~ \_ \- \| …) or an
                    // escaped literal (\\ \{ \}). Per the RTF grammar it is exactly two characters wide and
                    // carries NO delimiter, so it must not fall into the generic control-word scan below —
                    // that scan runs forward to the next space/brace/backslash and would over-run into the
                    // following literal text, deleting it. Emit the escaped literal for \\ \{ \} (they carry
                    // real HTML body characters, exactly like strip()); drop the other symbols. The \'hh
                    // run is already handled above, so it never reaches here.
                    var symbol = rtfText.charAt(index + 1);
                    if (symbol == '\\' || symbol == '{' || symbol == '}') {
                        html.append(symbol);
                    }
                    index += 2;
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
            if (character == '\r' || character == '\n') {
                // Raw CR/LF in the RTF stream is physical line wrapping, not content; real breaks are
                // \par / \line. Outlook's \fromhtml writer hard-wraps text runs, so appending these would
                // splice stray breaks into the recovered HTML body and diverge from the PST fork
                // (Message.extractHtmlFromRtf) and the sibling strip()/appendHtmlTag loops, which all drop them.
                index++;
                continue;
            }
            html.append(character);
            index++;
        }
        return html.toString().trim();
    }

    /**
     * Appends the content of one <code>{\*\htmltag…}</code> destination, decoding the RTF escapes it
     * may contain ({@code \'hh}, {@code \\ \{ \}}, {@code \\uN}, {@code \par}/{@code \line}/{@code
     * \tab}) and honoring escaped braces when locating the closing brace — a literal {@code \}}
     * inside an attribute value must not terminate the group. Returns the index just past the
     * closing brace, or {@code startIndex + 1} when the group never closes so the caller resumes
     * ordinary scanning.
     */
    private static int appendHtmlTag(
            String rtfText, int startIndex, Charset charset, StringBuilder html, int unicodeSkip) {
        var cursor = startIndex + "{\\*\\htmltag".length();
        // Skip the numeric destination argument (the tag kind) and its delimiter space.
        while (cursor < rtfText.length() && Character.isDigit(rtfText.charAt(cursor))) cursor++;
        if (cursor < rtfText.length() && rtfText.charAt(cursor) == ' ') cursor++;
        var tag = new StringBuilder();
        while (cursor < rtfText.length()) {
            var character = rtfText.charAt(cursor);
            if (character == '}') {
                html.append(tag);
                return cursor + 1;
            }
            if (character == '{') {
                cursor++;
                continue;
            }
            if (character != '\\') {
                if (character != '\r' && character != '\n') {
                    tag.append(character);
                }
                cursor++;
                continue;
            }
            if (cursor + 1 >= rtfText.length()) {
                cursor++;
                continue;
            }
            var next = rtfText.charAt(cursor + 1);
            if (next == '\\' || next == '{' || next == '}') {
                tag.append(next);
                cursor += 2;
                continue;
            }
            if (next == '\'') {
                // Decode the whole run of consecutive \'hh escapes together so a multibyte
                // (DBCS / UTF-8) attribute value is not split into per-byte mojibake.
                var run = decodeHexRun(rtfText, cursor, charset);
                tag.append(run.text());
                cursor = run.nextIndex();
                continue;
            }
            if (Character.isLetter(next)) {
                var wordStart = cursor + 1;
                var wordEnd = wordStart;
                while (wordEnd < rtfText.length() && Character.isLetter(rtfText.charAt(wordEnd))) {
                    wordEnd++;
                }
                var name = rtfText.substring(wordStart, wordEnd);
                var paramStart = wordEnd;
                if (wordEnd < rtfText.length()
                        && (rtfText.charAt(wordEnd) == '-' || Character.isDigit(rtfText.charAt(wordEnd)))) {
                    if (rtfText.charAt(wordEnd) == '-') {
                        wordEnd++;
                    }
                    while (wordEnd < rtfText.length() && Character.isDigit(rtfText.charAt(wordEnd))) {
                        wordEnd++;
                    }
                }
                cursor = wordEnd;
                if (cursor < rtfText.length() && rtfText.charAt(cursor) == ' ') cursor++;
                switch (name) {
                    case "par", "line" -> tag.append("\r\n");
                    case "tab" -> tag.append('\t');
                    case "u" -> {
                        try {
                            var codePoint = Integer.parseInt(rtfText.substring(paramStart, wordEnd));
                            if (codePoint < 0) {
                                codePoint += 0x10000;
                            }
                            if (Character.isValidCodePoint(codePoint)) {
                                tag.appendCodePoint(codePoint);
                            }
                        } catch (NumberFormatException ignored) {
                            // malformed \\uN escape — skip it
                        }
                        cursor = skipUnicodeFallback(rtfText, cursor, unicodeSkip);
                    }
                    default -> {
                        // other control words carry no literal content inside an htmltag
                    }
                }
                continue;
            }
            cursor += 2;
        }
        // Unterminated group: emit nothing and let the caller resume after the opening brace.
        return startIndex + 1;
    }

    /**
     * Skips a <code>{\*\…}</code> ignorable destination other than <code>\htmltag</code> — both its
     * control words and any literal content — so destinations such as <code>\mhtmltag</code>, <code>
     * \generator</code>, or <code>\fldinst</code> do not leak into the recovered HTML. RTF spec /
     * [MS-OXRTF]: a <code>\*</code> destination a reader does not recognize is dropped whole. Escaped
     * braces (<code>\{</code>, <code>\}</code>, <code>\\</code>) are honored so a literal brace inside
     * the group does not end it early. Returns the index just past the matching close brace, or the
     * end of input when the group never closes.
     */
    private static int skipIgnorableGroup(String rtfText, int startIndex) {
        var depth = 0;
        var index = startIndex;
        while (index < rtfText.length()) {
            var character = rtfText.charAt(index);
            if (character == '\\') {
                // Consume a \binN binary payload before the escape skip so raw brace bytes inside it
                // (common in {\pict}/{\object} groups) cannot unbalance the depth count.
                var afterBin = skipBin(rtfText, index);
                if (afterBin != index) {
                    index = afterBin;
                    continue;
                }
                if (index + 1 < rtfText.length()) {
                    // An escaped \{ \} \\ — or the leading char of any control word — is not a group brace.
                    index += 2;
                    continue;
                }
            }
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
            }
            index++;
        }
        return index;
    }

    /**
     * If a {@code \binN} control word starts at {@code index}, returns the index just past its N raw
     * binary bytes (RTF spec / [MS-OXRTFCP] §2.1.3.1.5) — the payload is not RTF and must be consumed
     * wholesale so its bytes (which can include stray braces) neither leak into the body nor desync the
     * group stack. Returns {@code index} unchanged when no {@code \bin} starts here; a missing or
     * unparsable count consumes no payload.
     */
    private static int skipBin(String rtfText, int index) {
        if (!rtfText.startsWith("\\bin", index)) {
            return index;
        }
        var cursor = index + 4;
        // \bin is this control word only when its letter run ends here; otherwise it is a longer word.
        if (cursor < rtfText.length() && Character.isLetter(rtfText.charAt(cursor))) {
            return index;
        }
        var digitsStart = cursor;
        while (cursor < rtfText.length() && Character.isDigit(rtfText.charAt(cursor))) {
            cursor++;
        }
        var next = cursor;
        if (next < rtfText.length() && rtfText.charAt(next) == ' ') {
            next++; // a control word's numeric argument may be followed by one delimiting space
        }
        if (cursor > digitsStart) {
            try {
                var binaryLength = Integer.parseInt(rtfText.substring(digitsStart, cursor));
                if (binaryLength > 0) {
                    next = Math.min(rtfText.length(), next + binaryLength);
                }
            } catch (NumberFormatException ignored) {
                // unparsable count — consume no payload
            }
        }
        return next;
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
        if ("1361".equals(codePage)) {
            // Korean Johab: none of the x-windows-1361 / windows-1361 / Cp1361 aliases tried below exist
            // in the JRE, so without this the page silently falls back to windows-1252 and mojibakes the
            // text. Resolve it to x-Johab — the charset the pst-parser CodePages table uses for 1361 —
            // keeping MSG and PST RTF de-encapsulation byte-identical.
            try {
                return Charset.forName("x-Johab");
            } catch (RuntimeException ignored) {
                return DEFAULT_RTF_CHARSET; // stripped JRE without x-Johab
            }
        }
        // Prefer the Microsoft x-windows-<cp> variant (matching the PST CodePages mapping) so a Windows
        // code page resolves to its MS flavour rather than the IBM one: cp950 is x-windows-950
        // (MS950/Big5), not Cp950 (x-IBM950), and the two differ on a few Big5 byte pairs. The alias is
        // a no-op where it does not exist. windows-<cp> covers 1250-1258/874; Cp<cp> covers 932/936.
        for (var candidate : new String[] {"x-windows-" + codePage, "windows-" + codePage, "Cp" + codePage}) {
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

    private static String controlReplacement(String name) {
        return switch (name) {
            // \pard is deliberately absent: it resets paragraph *formatting* and produces no break.
            case "par", "line" -> "\n";
            case "tab" -> "\t";
            // Table separators (RTF spec §Table Definitions): \cell/\nestcell end a cell and \row/\nestrow
            // end a table row. Without these arms they hit `default -> null`, emit nothing, and strip() also
            // swallows the trailing delimiter space, welding adjacent cells ("Name\cell John" -> "NameJohn").
            // Emit a tab between cells and a newline between rows, mirroring the \tab/\par arms. This feeds
            // only the plain-text strip() path (never deEncapsulateHtml), so it affects no HTML output.
            case "cell", "nestcell" -> "\t";
            case "row", "nestrow" -> "\n";
            case "lquote", "rquote" -> "'";
            case "ldblquote", "rdblquote" -> "\"";
            case "emdash" -> "—";
            case "endash" -> "–";
            case "bullet" -> "•";
            // Fixed-width spaces (RTF spec §Special Characters): emit the whitespace they denote rather
            // than dropping them and welding the surrounding words. controlReplacement feeds only the
            // plain-text strip() path (never deEncapsulateHtml), so this affects no HTML output.
            case "emspace" -> " ";
            case "enspace" -> " ";
            case "qmspace" -> " ";
            default -> null;
        };
    }

    /**
     * Decodes a maximal run of consecutive {@code \'hh} hex escapes starting at {@code index} (which
     * must point at the backslash of the first escape) as one byte sequence through {@code charset}.
     * A DBCS or UTF-8 ({@code \ansicpg65001}) code page stores a single character as several adjacent
     * {@code \'hh} bytes, so the run must be decoded together — decoding each byte in isolation yields
     * U+FFFD / {@code '?'} mojibake. A malformed or truncated escape ends the run. Always advances at
     * least past the first escape, so callers cannot loop.
     */
    private static HexRun decodeHexRun(String rtf, int index, Charset charset) {
        var bytes = new ByteArrayOutputStream();
        var cursor = index;
        while (cursor + 1 < rtf.length() && rtf.charAt(cursor) == '\\' && rtf.charAt(cursor + 1) == '\'') {
            if (cursor + 4 > rtf.length()) {
                cursor += 2; // truncated \'h at end of input — consume the marker and stop
                break;
            }
            try {
                bytes.write(Integer.parseInt(rtf.substring(cursor + 2, cursor + 4), 16));
            } catch (NumberFormatException ignored) {
                cursor += 4; // malformed hex — skip this escape and stop the run
                break;
            }
            cursor += 4;
        }
        return new HexRun(new String(bytes.toByteArray(), charset), cursor);
    }

    /** Decoded text of a {@code \'hh} escape run and the index just past it. */
    private record HexRun(String text, int nextIndex) {}
}
