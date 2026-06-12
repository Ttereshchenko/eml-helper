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
}
