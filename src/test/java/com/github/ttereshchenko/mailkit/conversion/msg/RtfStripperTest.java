package com.github.ttereshchenko.mailkit.conversion.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
