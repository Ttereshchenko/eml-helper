package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RtfStripperTest {

    @Test
    void stripsControlWordsAndKeepsRunText() {
        var rtf = "{\\rtf1\\ansi Hello \\b world\\b0 .\\par}";
        assertEquals("Hello world.", RtfStripper.strip(rtf));
    }

    @Test
    void dropsFontTableGroup() {
        var rtf = "{\\rtf1\\ansi{\\fonttbl{\\f0 Helvetica;}}Body text}";
        assertEquals("Body text", RtfStripper.strip(rtf));
    }

    @Test
    void dropsStarPrefixedDestination() {
        var rtf = "{\\rtf1{\\*\\generator MyTool;}Body}";
        assertEquals("Body", RtfStripper.strip(rtf));
    }

    @Test
    void deEncapsulateHtmlSkipsMhtmltagDestination() {
        // [MS-OXRTF]: the \mhtmltag destination is the MHTML twin of \htmltag and must not be emitted
        // during HTML de-encapsulation. Before the fix its literal content leaked, doubling the <img>.
        var html = RtfStripper.deEncapsulateHtml("{\\rtf1\\fromhtml1 {\\*\\htmltag84 <img src=\"cid:image001\">}"
                + "{\\*\\mhtmltag84 <img src=\"http://example.com/x.png\">}}");
        assertEquals("<img src=\"cid:image001\">", html);
    }

    @Test
    void deEncapsulateHtmlSkipsIgnorableGeneratorDestination() {
        // A {\*\generator …} ignorable destination carries no recovered HTML; its text must not leak
        // into the output (RTF spec: an unrecognized \* destination group is dropped whole).
        var html = RtfStripper.deEncapsulateHtml("{\\rtf1\\fromhtml1 {\\*\\generator Microsoft Word 15}<p>Hi</p>}");
        assertEquals("<p>Hi</p>", html);
    }

    @Test
    void deEncapsulateHtmlDropsFontColorAndStyleHeaderTables() {
        // [MS-OXRTFEX] section 2.1.3.1: the fonttbl/colortbl/stylesheet/info header tables are RTF
        // infrastructure, not de-encapsulated HTML. Before the fix the generic control-word skip ate
        // only the table keyword and then leaked the group's literal text (font face names, the
        // colortbl ';' separators, style names) into the recovered body — real Outlook fromhtml RTF
        // always carries a font table.
        var rtf = "{\\rtf1\\ansi\\ansicpg1252\\fromhtml1\\deff0"
                + "{\\fonttbl{\\f0\\fswiss Calibri;}{\\f1\\fmodern Courier New;}}"
                + "{\\colortbl;\\red0\\green0\\blue0;\\red255\\green0\\blue0;}"
                + "{\\stylesheet{\\s0 Normal;}}"
                + "{\\*\\htmltag84 <html>}{\\*\\htmltag64 <body>}Hello world"
                + "{\\*\\htmltag72 </body>}{\\*\\htmltag14 </html>}}";
        var html = RtfStripper.deEncapsulateHtml(rtf);
        assertEquals("<html><body>Hello world</body></html>", html);
        assertFalse(html.contains("Calibri"), html);
        assertFalse(html.contains("Courier"), html);
        assertFalse(html.contains("Normal"), html);
    }

    @Test
    void deEncapsulateHtmlConsumesBinPayloadInBody() {
        // \binN in a non-htmlrtf run carries N raw bytes that are not RTF. Before the fix the generic
        // control-word scan ate only "\bin3" and then leaked the payload into the body — and the raw
        // '}' bytes in it popped the group stack, desyncing all later output. The count must be
        // consumed wholesale so the payload "}X}" vanishes and "B" survives.
        var rtf = "{\\rtf1\\fromhtml1 {\\*\\htmltag64 <p>}A\\bin3 }X}B{\\*\\htmltag72 </p>}}";
        assertEquals("<p>AB</p>", RtfStripper.deEncapsulateHtml(rtf));
    }

    @Test
    void deEncapsulateHtmlSkipsPictureGroupWithBinaryPayload() {
        // A {\pict ...} picture group is RTF infrastructure, not de-encapsulated HTML. Before the fix
        // {\pict was unrecognized, so its literal payload ("DEADBEEF") leaked into the body. Skipping
        // it also requires the group-skip to consume the \bin payload: the single payload byte is an
        // unbalanced '{' that a brace-counting skip would otherwise treat as a nested group, swallowing
        // the trailing "Bye" and the closing htmltag.
        var rtf = "{\\rtf1\\fromhtml1 {\\*\\htmltag64 <p>}Hi{\\pict\\wmetafile8\\bin1 {DEADBEEF}Bye"
                + "{\\*\\htmltag72 </p>}}";
        assertEquals("<p>HiBye</p>", RtfStripper.deEncapsulateHtml(rtf));
    }

    @Test
    void treatsParAsNewline() {
        var rtf = "{\\rtf1 Line1\\par Line2}";
        var output = RtfStripper.strip(rtf);
        assertTrue(output.contains("Line1"));
        assertTrue(output.contains("Line2"));
        assertTrue(output.contains("\n"));
    }

    @Test
    void decodesHexEscape() {
        var rtf = "{\\rtf1\\ansi caf\\'e9}";
        assertEquals("café", RtfStripper.strip(rtf));
    }

    @Test
    void decodesUnicodeEscape() {
        var rtf = "{\\rtf1\\ansi\\u8364?}";
        assertEquals("€", RtfStripper.strip(rtf));
    }

    @Test
    void emptyInputReturnsEmptyString() {
        assertEquals("", RtfStripper.strip(""));
    }

    @Test
    void outOfRangeUnicodeEscapeIsSkippedInsteadOfThrowing() {
        // A \\u control word above U+10FFFF is a valid int but an invalid code point. The pre-fix
        // guard only checked codepoint >= 0, so appendCodePoint threw IllegalArgumentException and
        // escaped the converter. The surrounding run text must survive and the bad escape be dropped.
        var rtf = "{\\rtf1\\ansi A\\u2000000?B}";
        assertEquals("AB", RtfStripper.strip(rtf));
    }

    @Test
    void honorsAnsiCpgCodePageForHexEscapes() {
        // 0xC0 is 'A-grave' in windows-1252 but Cyrillic 'A' (U+0410) in windows-1251. Before the fix
        // the stripper always assumed windows-1252; with \\ansicpg1251 it must decode Cyrillic.
        var rtf = "{\\rtf1\\ansi\\ansicpg1251 \\'c0}";
        assertEquals("А", RtfStripper.strip(rtf));
    }

    @Test
    void honorsAnsiCpg1361KoreanJohab() {
        // Code page 1361 (Korean Johab) has no x-windows-1361 / windows-1361 / Cp1361 JRE alias, so the
        // pre-fix resolver fell back to windows-1252 and mojibaked the text. \\ansicpg1361 must resolve to
        // x-Johab — the same charset the pst-parser CodePages table uses — so the byte pair 0x88 0x61
        // decodes to the Hangul syllable 가 (U+AC00) instead of "ˆa" under windows-1252.
        var rtf = "{\\rtf1\\ansi\\ansicpg1361 \\'88\\'61}";
        assertEquals("가", RtfStripper.strip(rtf));
    }

    @Test
    void honorsUnicodeSkipCountForFallbackChars() {
        // \\uc2 means each \\u carries two ANSI fallback chars. The pre-fix stripper always skipped
        // exactly one, leaking the second '?' into the output as "EUR?Done".
        var rtf = "{\\rtf1\\ansi\\uc2\\u8364??Done}";
        assertEquals("€Done", RtfStripper.strip(rtf));
    }

    @Test
    void unicodeSkipCountZeroKeepsFollowingText() {
        // \\uc0 means no ANSI fallback char follows the \\u escape.
        var rtf = "{\\rtf1\\ansi\\uc0\\u8364Done}";
        assertEquals("€Done", RtfStripper.strip(rtf));
    }

    @Test
    void unicodeSkipCountIsGroupScopedInStrip() {
        // \\ucN is a group-scoped property: a \\uc0 set inside {...} must revert at the closing brace, so
        // the \\u8364 *after* the group still skips its one ANSI fallback char. The pre-fix stripper let
        // the \\uc0 leak past the brace, leaving the trailing '?' in the output (€X€?Y).
        var rtf = "{\\rtf1\\ansi{\\uc0\\u8364}X\\u8364?Y}";
        assertEquals("€X€Y", RtfStripper.strip(rtf));
    }

    // \binN introduces exactly N bytes of raw binary picture data (RTF spec / [MS-OXRTFCP]
    // §2.1.3.1.5) that are NOT RTF text. The pre-fix stripper consumed only the \binN control word
    // and then leaked the N raw bytes into the plain-text output.
    @Test
    void binBinaryDataIsSkipped() {
        // \bin5 followed by a single delimiting space and exactly five raw bytes "ABCDE".
        var rtf = "{\\rtf1\\ansi Before\\bin5 ABCDEAfter}";
        assertEquals("BeforeAfter", RtfStripper.strip(rtf));
    }

    @Test
    void binBinaryDataContainingBackslashesAndBracesIsSkipped() {
        // The skipped bytes may themselves be backslashes/braces; counting (not re-parsing) them is
        // what keeps the parser in sync. The four bytes "\{a}" are consumed, then "Tail" survives.
        var rtf = "{\\rtf1\\ansi X\\bin4 \\{a}Tail}";
        assertEquals("XTail", RtfStripper.strip(rtf));
    }

    @Test
    void binWithoutCountSkipsNoBinaryData() {
        // A bare \bin with no count carries no binary payload; following text must survive.
        var rtf = "{\\rtf1\\ansi P\\bin Q}";
        assertEquals("PQ", RtfStripper.strip(rtf));
    }

    // #14: HTML-encapsulated RTF (\fromhtml) is detected and de-encapsulated to HTML, rather than
    // stripped to plain text (which loses the markup the PST path already preserves).
    @Test
    void detectsHtmlEncapsulation() {
        assertTrue(RtfStripper.isHtmlEncapsulated("{\\rtf1\\ansi\\fromhtml1 \\htmlrtf0 x}"));
        assertFalse(RtfStripper.isHtmlEncapsulated("{\\rtf1\\ansi Hello world}"));
    }

    @Test
    void deEncapsulatesHtmlRatherThanLeakingOrLosingMarkup() {
        var rtf = "{\\rtf1\\ansi\\ansicpg1252\\fromhtml1 "
                + "{\\*\\htmltag84 <html>}"
                + "{\\*\\htmltag64 <body>}"
                + "Hello \\htmlrtf\\b world\\b0\\htmlrtf0  again"
                + "{\\*\\htmltag72 </body>}"
                + "{\\*\\htmltag14 </html>}}";

        var html = RtfStripper.deEncapsulateHtml(rtf);

        // The original HTML structure and visible text are recovered.
        assertTrue(html.contains("<html>"), html);
        assertTrue(html.contains("<body>"), html);
        assertTrue(html.contains("Hello"), html);
        assertTrue(html.contains("again"), html);
        assertTrue(html.contains("</body>"), html);
        // RTF-only formatting inside \htmlrtf ... \htmlrtf0 is suppressed, not rendered as text.
        assertFalse(html.contains("\\b"), html);
        assertFalse(html.contains("world"), html);

        // The old plain-text fallback drops the {\*\htmltag ...} markup entirely; de-encapsulation keeps it.
        assertFalse(RtfStripper.strip(rtf).contains("<body>"), "strip() loses HTML structure");
    }

    // Round-14: an RTF control symbol (\~ \_ \- \| …) in a non-htmlrtf text run is exactly two chars with
    // no delimiter. The generic control-word scan used to run forward to the next space/brace/backslash,
    // over-running the symbol and deleting the literal body text that followed it.
    @Test
    void deEncapsulationKeepsTextAfterAControlSymbol() {
        var html = RtfStripper.deEncapsulateHtml("{\\rtf1\\fromhtml1 a\\~b c}");
        assertEquals("ab c", html, "text after a control symbol must survive, not be swallowed by the scan");
    }

    // Round-14: the \\ \{ \} escapes carry real backslash/brace characters of the HTML body and must be
    // emitted as literals (like strip() does), not consumed by the generic control-word scan.
    @Test
    void deEncapsulationEmitsEscapedBackslashAndBraceLiterals() {
        var html = RtfStripper.deEncapsulateHtml("{\\rtf1\\fromhtml1 a\\\\b\\{c\\}d}");
        assertEquals("a\\b{c}d", html);
    }

    // R12: \pard resets paragraph formatting and produces no break — it used to be mapped to "\n",
    // adding a spurious blank line after every real \par in Outlook-generated RTF.
    @Test
    void pardDoesNotProduceALineBreak() {
        var output = RtfStripper.strip("{\\rtf1 first\\par\\pard second}");
        assertEquals("first\nsecond", output);
    }

    // R8: the de-encapsulation used to hardcode a '?' fallback after a uN escape; a literal
    // (non-'?') fallback char leaked into the output as a duplicate of the decoded code point.
    @Test
    void deEncapsulationSkipsLiteralUnicodeFallbackChar() {
        var html = RtfStripper.deEncapsulateHtml("{\\rtf1\\fromhtml1\\uc1 \\u1055P after}");
        assertEquals("П after", html);
    }

    // R8: a uc0 directive declares that no fallback follows — the next character is real content and must stay.
    @Test
    void deEncapsulationHonorsUcZero() {
        var html = RtfStripper.deEncapsulateHtml("{\\rtf1\\fromhtml1\\uc0 \\u1055 X}");
        assertEquals("ПX", html);
    }

    // R8: \'hh escapes inside a {\*\htmltag ...} run used to be appended verbatim ("a\'3db") instead
    // of decoded.
    @Test
    void htmlTagContentDecodesHexEscapes() {
        var html = RtfStripper.deEncapsulateHtml(
                "{\\rtf1\\ansi\\ansicpg1252\\fromhtml1 {\\*\\htmltag84 <a href=\"a\\'3db\">}x{\\*\\htmltag92 </a>}}");
        assertEquals("<a href=\"a=b\">x</a>", html);
    }

    // R8: an escaped brace inside a tag attribute used to terminate the htmltag group early,
    // truncating the markup mid-attribute.
    @Test
    void htmlTagContentHonorsEscapedBraces() {
        var html = RtfStripper.deEncapsulateHtml(
                "{\\rtf1\\fromhtml1 {\\*\\htmltag84 <span title=\"\\{x\\}\">}y{\\*\\htmltag92 </span>}}");
        assertEquals("<span title=\"{x}\">y</span>", html);
    }

    // R8: surrogate pairs arrive as two negative uN values; the signed-16-bit normalization must
    // reassemble them into one supplementary code point.
    @Test
    void deEncapsulationDecodesNegativeSurrogatePairs() {
        var html = RtfStripper.deEncapsulateHtml("{\\rtf1\\fromhtml1\\uc1 \\u-10179?\\u-8704?}");
        assertEquals("😀", html);
    }

    // A DBCS or UTF-8 (\ansicpg65001) code page stores one character as several consecutive \'hh
    // bytes; the pre-fix stripper decoded each byte alone, turning one character into U+FFFD / '?'
    // mojibake. The run must be decoded as a single byte sequence.
    @Test
    void decodesMultiByteUtf8HexEscapeRun() {
        // \ansicpg65001 is UTF-8: U+4E2D (中) is the three bytes E4 B8 AD.
        var rtf = "{\\rtf1\\ansi\\ansicpg65001 \\'e4\\'b8\\'ad}";
        assertEquals("中", RtfStripper.strip(rtf));
    }

    @Test
    void decodesMultiByteDbcsHexEscapeRun() {
        // \ansicpg936 is GBK: U+4E2D (中) is the two bytes D6 D0; per-byte decoding yields '?' mojibake.
        var rtf = "{\\rtf1\\ansi\\ansicpg936 \\'d6\\'d0}";
        assertEquals("中", RtfStripper.strip(rtf));
    }

    @Test
    void deEncapsulationDecodesMultiByteUtf8Run() {
        // A recovered HTML body carries non-ASCII as consecutive \'hh bytes in the declared code page.
        var html = RtfStripper.deEncapsulateHtml(
                "{\\rtf1\\ansi\\ansicpg65001\\fromhtml1 {\\*\\htmltag84 <p>}\\'e4\\'b8\\'ad{\\*\\htmltag92 </p>}}");
        assertEquals("<p>中</p>", html);
    }

    @Test
    void htmlTagContentDecodesMultiByteUtf8Run() {
        // The same multibyte run can appear inside a {\*\htmltag ...} attribute value.
        var html = RtfStripper.deEncapsulateHtml(
                "{\\rtf1\\ansi\\ansicpg65001\\fromhtml1 {\\*\\htmltag84 <p title=\"\\'e4\\'b8\\'ad\">}x{\\*\\htmltag92 </p>}}");
        assertEquals("<p title=\"中\">x</p>", html);
    }

    // F8 (audit follow-up): \htmlrtf is RTF group-scoped — a toggle set inside a {group} must be
    // restored at the group's closing brace and not suppress text that follows it.
    @Test
    void htmlRtfSuppressionIsGroupScoped() {
        var html = RtfStripper.deEncapsulateHtml("{\\rtf1\\fromhtml1 A{\\htmlrtf B}C}");
        assertEquals("AC", html);
    }

    // F9 (audit follow-up): a \\uN escape inside a {\*\htmltag ...} run honors the active \\ucN fallback
    // count instead of a hardcoded 1, so surplus fallback bytes do not leak into the recovered tag text.
    // H1 regression: ansicpg950 (Big5/Traditional Chinese) must resolve to x-windows-950, not
    // Cp950 (IBM-950). The two charsets diverge on a handful of Big5 byte pairs: for example, the
    // byte pair 0xC2 0x55 decodes to U+5F5D (彝) under x-windows-950 but to U+5F5E (彞) under
    // Cp950. On the old code Cp950 was chosen first and produced the wrong character.
    @Test
    void ansicpg950ResolvesToXWindows950NotCp950() {
        // \'c2\'55 is the Big5 byte pair 0xC2 0x55.
        var rtf = "{\\rtf1\\ansi\\ansicpg950\\fs20 \\'c2\\'55}";
        var result = RtfStripper.strip(rtf);
        // x-windows-950: U+5F5D (彝); Cp950: U+5F5E (彞).
        assertEquals("彝", result, "ansicpg950 must use x-windows-950 (U+5F5D 彝), not Cp950 (U+5F5E 彞). Got: " + result);
        assertFalse(result.contains("彞"), "Cp950-decoded character U+5F5E (彞) must not appear in the result");
    }

    @Test
    void htmlTagUnicodeFallbackHonorsActiveUcCount() {
        var html = RtfStripper.deEncapsulateHtml("{\\rtf1\\fromhtml1\\uc2 {\\*\\htmltag84 \\u8364XX<b>}done}");
        assertEquals("€<b>done", html);
    }

    /**
     * Regression for the raw CR/LF drop in deEncapsulateHtml: Outlook's {@code \\fromhtml} writer
     * hard-wraps long text runs by inserting bare CR/LF characters at column boundaries. These are
     * physical line-wrapping artefacts, not content line breaks (which are encoded as {@code \\par}
     * or {@code \\line}). Before the fix they were appended to the output HTML, splicing stray
     * newlines into the recovered body and diverging from the PST fork and the {@code strip()}
     * sibling loop (both of which already dropped them). After the fix the characters are silently
     * skipped and the surrounding words are joined seamlessly.
     */
    @Test
    void deEncapsulateHtmlDropsPhysicalLineWrappingCrLfFromTextRuns() {
        // The \r\n between "Hello" and "world" is a physical wrap inserted by Outlook, not a content
        // break.  After de-encapsulation the two words must be joined without any CR or LF.
        var html = RtfStripper.deEncapsulateHtml(
                "{\\rtf1\\ansi\\fromhtml1 {\\*\\htmltag64 <p>}Hello\r\nworld{\\*\\htmltag72 </p>}}");
        assertEquals(
                "<p>Helloworld</p>",
                html,
                "physical CR/LF line-wrap must be dropped, not emitted into the recovered HTML");
        assertFalse(html.contains("\r"), "no CR must survive in de-encapsulated output");
        assertFalse(html.contains("\n"), "no LF must survive in de-encapsulated output");
    }

    // -----------------------------------------------------------------------
    // Round-22 audit tests
    // -----------------------------------------------------------------------

    // Fix RTF-1 — control symbols \~, \_, \-, \emspace, \enspace, \qmspace must not weld words.

    @Test
    void nonBreakingSpaceControlSymbolSeparatesAdjacentWords() {
        // \~ is a non-breaking space; "Mr.\~Smith" must NOT produce "Mr.Smith" (words welded).
        var text = RtfStripper.strip("{\\rtf1\\ansi Mr.\\~Smith}");
        assertFalse(text.contains("Mr.Smith"), "\\~ must not weld words — got: '" + text + "'");
        assertTrue(text.contains("Mr."), "prefix word must survive: '" + text + "'");
        assertTrue(text.contains("Smith"), "suffix word must survive: '" + text + "'");
    }

    @Test
    void nonBreakingHyphenControlSymbolEmitsUnicode2011() {
        // \_ is U+2011 NON-BREAKING HYPHEN (not dropped, not a plain hyphen-minus).
        var text = RtfStripper.strip("{\\rtf1\\ansi A\\_B}");
        assertTrue(text.contains("‑"), "\\_ must emit U+2011 non-breaking hyphen: '" + text + "'");
    }

    @Test
    void optionalHyphenControlSymbolIsDropped() {
        // \- is an optional hyphen — invisible, produces no character in the output.
        var text = RtfStripper.strip("{\\rtf1\\ansi A\\-B}");
        assertEquals("AB", text, "\\- optional hyphen must be dropped: '" + text + "'");
    }

    @Test
    void emspaceEnspaceAndQmspaceEachProduceASpace() {
        // Fixed-width space control words emit a Unicode space character (U+2003 EM SPACE, U+2002
        // EN SPACE, U+2005 FOUR-PER-EM SPACE respectively) so surrounding words are not welded.
        var emspace = RtfStripper.strip("{\\rtf1\\ansi A\\emspace B}");
        assertFalse(emspace.contains("AB"), "\\emspace must not weld words: '" + emspace + "'");
        assertTrue(emspace.contains("A") && emspace.contains("B"), emspace);
        assertTrue(emspace.contains(" "), "\\emspace must emit U+2003 EM SPACE: '" + emspace + "'");

        var enspace = RtfStripper.strip("{\\rtf1\\ansi A\\enspace B}");
        assertFalse(enspace.contains("AB"), "\\enspace must not weld words: '" + enspace + "'");
        assertTrue(enspace.contains(" "), "\\enspace must emit U+2002 EN SPACE: '" + enspace + "'");

        var qmspace = RtfStripper.strip("{\\rtf1\\ansi A\\qmspace B}");
        assertFalse(qmspace.contains("AB"), "\\qmspace must not weld words: '" + qmspace + "'");
        assertTrue(qmspace.contains(" "), "\\qmspace must emit U+2005 FOUR-PER-EM SPACE: '" + qmspace + "'");
    }

    // -----------------------------------------------------------------------
    // Round-23 audit tests
    // -----------------------------------------------------------------------

    // Fix RTFCELL-1 — \cell/\row table separators. Before the fix strip() had no controlReplacement
    // arm for them, so they hit `default -> null`, emitted nothing, and the following delimiter space
    // was also swallowed, welding adjacent cells and rows into one run ("NameJohn SmithEmailx@y.com").
    // A cell terminator must emit a tab and a row terminator a newline, mirroring the \tab/\par arms.
    @Test
    void cellAndRowSeparatorsBecomeTabsAndNewlines() {
        var rtf = "{\\rtf1\\ansi Name\\cell John Smith\\cell\\row Email\\cell x@y.com\\cell\\row}";
        var text = RtfStripper.strip(rtf);
        // strip() trims trailing whitespace, so the final row's separators do not survive at end-of-text.
        assertEquals(
                "Name\tJohn Smith\t\nEmail\tx@y.com",
                text,
                "cells must be tab-separated and rows newline-separated: '" + text + "'");
        assertFalse(text.contains("NameJohn"), "\\cell must not weld a cell onto the next: '" + text + "'");
        assertFalse(text.contains("SmithEmail"), "\\row must not weld a row onto the next: '" + text + "'");
    }

    // Fix RTFCELL-1 — the nested-table variants \nestcell/\nestrow map the same way as \cell/\row.
    @Test
    void nestedCellAndRowSeparatorsBecomeTabsAndNewlines() {
        var text = RtfStripper.strip("{\\rtf1\\ansi A\\nestcell B\\nestrow C\\nestcell D\\nestrow}");
        assertEquals("A\tB\nC\tD", text, "\\nestcell -> tab and \\nestrow -> newline: '" + text + "'");
    }
}
