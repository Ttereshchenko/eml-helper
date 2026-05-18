package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SmtpClientNormalizationTest {

    @Test
    void lfOnlyInputIsRewrittenToCrlf() {
        var input = "From: a@example.com\nTo: b@example.com\n\nhi\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalizeAndDotStuff(input, input.length, false, true);
        var rendered = new String(result.bytes(), StandardCharsets.UTF_8);
        assertEquals("From: a@example.com\r\nTo: b@example.com\r\n\r\nhi\r\n", rendered);
        assertFalse(result.endedWithCr());
        assertTrue(result.endsAtLineStart());
    }

    @Test
    void existingCrlfIsLeftIntact() {
        var input = "Subject: hi\r\n\r\nbody\r\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalizeAndDotStuff(input, input.length, false, true);
        assertEquals(new String(input, StandardCharsets.UTF_8), new String(result.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void leadingDotOnLineGetsStuffed() {
        var input = ".start of line\n..already two dots\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalizeAndDotStuff(input, input.length, false, true);
        var rendered = new String(result.bytes(), StandardCharsets.UTF_8);
        assertEquals("..start of line\r\n...already two dots\r\n", rendered);
    }

    @Test
    void midLineDotIsNotStuffed() {
        var input = "see http://x.y for more\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalizeAndDotStuff(input, input.length, false, true);
        assertEquals("see http://x.y for more\r\n", new String(result.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void crSplitAcrossChunkBoundaryIsPreserved() {
        var first = "line1\r".getBytes(StandardCharsets.UTF_8);
        var second = "\nline2\n".getBytes(StandardCharsets.UTF_8);
        var firstResult = SmtpClient.normalizeAndDotStuff(first, first.length, false, true);
        var secondResult = SmtpClient.normalizeAndDotStuff(
                second, second.length, firstResult.endedWithCr(), firstResult.endsAtLineStart());
        var combined = new String(firstResult.bytes(), StandardCharsets.UTF_8)
                + new String(secondResult.bytes(), StandardCharsets.UTF_8);
        assertEquals("line1\r\nline2\r\n", combined);
    }
}
