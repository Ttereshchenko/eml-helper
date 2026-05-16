package com.github.ttereshchenko.mailkit.inspections.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LineLengthRuleTest {

    @Test
    void shortLinesAreFine() {
        assertTrue(LineLengthRule.findLongLines("Subject: hi\n\nbody\n").isEmpty());
    }

    @Test
    void detectsLineExactlyAtBoundaryAsValid() {
        var line = "X: " + "a".repeat(995); // 998 chars total
        assertTrue(LineLengthRule.findLongLines(line + "\n").isEmpty());
    }

    @Test
    void detectsLineOverBoundary() {
        var line = "X: " + "a".repeat(996); // 999 chars total
        var ranges = LineLengthRule.findLongLines(line + "\n");
        assertEquals(1, ranges.size());
        assertEquals(0, ranges.get(0).startOffset());
        assertEquals(line.length(), ranges.get(0).endOffset());
    }

    @Test
    void countsUtf8Octets() {
        // Each '€' is 3 bytes in UTF-8 — 333 of them is 999 octets, exceeds the limit.
        var line = "€".repeat(333);
        var ranges = LineLengthRule.findLongLines(line + "\n");
        assertEquals(1, ranges.size());
    }

    @Test
    void handlesCrlfLineEndings() {
        var line = "a".repeat(1000);
        var ranges = LineLengthRule.findLongLines(line + "\r\n");
        assertEquals(1, ranges.size());
        assertEquals(line.length(), ranges.get(0).endOffset());
    }
}
