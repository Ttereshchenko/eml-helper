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
}
