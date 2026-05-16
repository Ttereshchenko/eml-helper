package com.github.ttereshchenko.mailkit.inspections.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EncodedWordRuleTest {

    @Test
    void asciiOnlyHeaderHasNoFindings() {
        assertTrue(EncodedWordRule.findUnencodedNonAscii("hello world").isEmpty());
    }

    @Test
    void nonAsciiOutsideEncodedWordIsReported() {
        var input = "Café deux";
        var ranges = EncodedWordRule.findUnencodedNonAscii(input);
        assertEquals(1, ranges.size());
        assertEquals(3, ranges.get(0).startOffset());
        assertEquals(4, ranges.get(0).endOffset());
    }

    @Test
    void nonAsciiInsideEncodedWordIsIgnored() {
        var input = "=?UTF-8?Q?Café?= deux";
        assertTrue(EncodedWordRule.findUnencodedNonAscii(input).isEmpty());
    }

    @Test
    void multipleNonAsciiRunsCoalesced() {
        var input = "éé abc è";
        var ranges = EncodedWordRule.findUnencodedNonAscii(input);
        assertEquals(2, ranges.size());
        assertEquals(0, ranges.get(0).startOffset());
        assertEquals(2, ranges.get(0).endOffset());
    }

    @Test
    void structuredHeaderDetection() {
        assertTrue(EncodedWordRule.isStructured("From"));
        assertTrue(EncodedWordRule.isStructured("subject"));
        assertFalse(EncodedWordRule.isStructured("X-Custom"));
    }
}
