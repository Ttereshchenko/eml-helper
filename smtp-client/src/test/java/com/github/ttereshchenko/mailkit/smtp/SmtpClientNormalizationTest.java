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
    void crSplitAcrossChunkBoundaryFormsASingleCrlf() {
        // A CR ending one chunk and an LF starting the next is a single CRLF: rfc5321 §2.3.8. The
        // pending CR must be carried (endedWithCr) and resolved by the next-chunk LF as one CRLF —
        // not flushed early (which would yield "line1\r\n\r\nline2") nor left as a bare CR.
        var first = "line1\r".getBytes(StandardCharsets.UTF_8);
        var second = "\nline2\n".getBytes(StandardCharsets.UTF_8);
        var firstResult = SmtpClient.normalizeAndDotStuff(first, first.length, false, true);
        assertTrue(firstResult.endedWithCr(), "trailing CR must be held pending across the chunk boundary");
        var secondResult = SmtpClient.normalizeAndDotStuff(
                second, second.length, firstResult.endedWithCr(), firstResult.endsAtLineStart());
        var combined = new String(firstResult.bytes(), StandardCharsets.UTF_8)
                + new String(secondResult.bytes(), StandardCharsets.UTF_8);
        assertEquals("line1\r\nline2\r\n", combined);
    }

    @Test
    void bareCrMidLineIsPromotedToCrlf() {
        // rfc5321 §2.3.8: lines are terminated only by CRLF; a lone CR (CR not followed by LF) is
        // not a valid terminator and must not reach the wire. Old behavior left the bare CR intact.
        var input = "alpha\rbeta\r\ngamma\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalizeAndDotStuff(input, input.length, false, true);
        var rendered = new String(result.bytes(), StandardCharsets.UTF_8);
        assertEquals("alpha\r\nbeta\r\ngamma\r\n", rendered);
        assertFalse(result.endedWithCr());
        assertTrue(result.endsAtLineStart());
    }

    @Test
    void consecutiveBareCrsEachBecomeCrlf() {
        // Two adjacent bare CRs are two empty-terminated lines, each promoted to CRLF (rfc5321
        // §2.3.8); the second CR must resolve the first rather than coalescing into one CRLF.
        var input = "x\r\ry\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalizeAndDotStuff(input, input.length, false, true);
        assertEquals("x\r\n\r\ny\r\n", new String(result.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void trailingBareCrAtEndOfStreamBecomesCrlfViaCaller() {
        // A bare CR at the very end of the source is held pending (endedWithCr) and ends not at a
        // line start, so streamPayload's end-of-stream flush emits the closing CRLF (rfc5321 §2.3.8).
        var input = "tail\r".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalizeAndDotStuff(input, input.length, false, true);
        assertEquals("tail", new String(result.bytes(), StandardCharsets.UTF_8));
        assertTrue(result.endedWithCr());
        assertFalse(result.endsAtLineStart());
    }

    @Test
    void bareCrAtChunkEndFollowedByNonLfNextChunkIsPromoted() {
        // The pending CR carried from one chunk must be flushed as CRLF when the next chunk does
        // NOT begin with LF, turning a bare CR into a proper line ending (rfc5321 §2.3.8).
        var first = "head\r".getBytes(StandardCharsets.UTF_8);
        var second = "tail\n".getBytes(StandardCharsets.UTF_8);
        var firstResult = SmtpClient.normalizeAndDotStuff(first, first.length, false, true);
        var secondResult = SmtpClient.normalizeAndDotStuff(
                second, second.length, firstResult.endedWithCr(), firstResult.endsAtLineStart());
        var combined = new String(firstResult.bytes(), StandardCharsets.UTF_8)
                + new String(secondResult.bytes(), StandardCharsets.UTF_8);
        assertEquals("head\r\ntail\r\n", combined);
    }

    @Test
    void bareCrBeforeLeadingDotStillStuffsAtNewLineStart() {
        // After a bare CR is promoted to CRLF the next byte is at a line start, so a leading dot
        // must still be dot-stuffed (rfc5321 §4.5.2).
        var input = "data\r.dotline\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalizeAndDotStuff(input, input.length, false, true);
        assertEquals("data\r\n..dotline\r\n", new String(result.bytes(), StandardCharsets.UTF_8));
    }
}
