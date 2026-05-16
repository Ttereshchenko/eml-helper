package com.github.ttereshchenko.mailkit.inspections.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CharsetDecodeRuleTest {

    @Test
    void utf8DecodesCleanUtf8Body() {
        var bytes = "Café".getBytes(StandardCharsets.UTF_8);
        assertNull(CharsetDecodeRule.check(bytes, "UTF-8"));
    }

    @Test
    void utf8RejectsInvalidByteSequenceAndSuggestsLatin1() {
        var bytes = new byte[] {(byte) 0x43, (byte) 0xE9}; // "C" + lone 0xE9 — invalid UTF-8 but valid ISO-8859-1.
        var result = CharsetDecodeRule.check(bytes, "UTF-8");
        assertNotNull(result);
        assertEquals(1, result.invalidRange().startOffset());
        assertNotNull(result.suggestion());
    }

    @Test
    void unknownCharsetReturnsNull() {
        assertNull(CharsetDecodeRule.check(new byte[] {65}, "no-such-charset"));
    }

    @Test
    void blankCharsetReturnsNull() {
        assertNull(CharsetDecodeRule.check(new byte[] {65}, ""));
    }
}
