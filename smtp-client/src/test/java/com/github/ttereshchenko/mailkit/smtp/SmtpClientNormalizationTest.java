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

    // --- normalizeLineEndings=TRUE regression guard ---

    @Test
    void normalizeEnabledLfOnlyInputIsRewrittenToCrlfRegressionGuard() {
        // Explicit regression guard: the normalize=true path (default) must still expand bare LF to
        // CRLF. If someone flips the default this test catches the regression.
        var input = "Subject: hi\n\nbody line\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalize(input, input.length, false, true, true, true);
        assertEquals("Subject: hi\r\n\r\nbody line\r\n", new String(result.bytes(), StandardCharsets.UTF_8));
        assertTrue(result.endsAtLineStart(), "must end at a line start after a CRLF");
        assertTrue(result.endsWithCrlf(), "must end with CRLF in normalize mode");
    }

    @Test
    void normalizeEnabledLeadingDotIsStuffed() {
        // Regression guard: dot-stuffing must still work with normalizeLineEndings=true.
        var input = ".top\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalize(input, input.length, false, true, true, true);
        assertEquals("..top\r\n", new String(result.bytes(), StandardCharsets.UTF_8));
    }

    // --- normalizeLineEndings=FALSE ---

    @Test
    void noNormalizeLfIsEmittedVerbatimWithoutCrlfPromotion() {
        // When normalizeLineEndings=false, a bare LF must be emitted as-is (no CRLF expansion).
        // The caller (streamPayload) is responsible for the terminator framing; the chunk itself
        // carries the raw byte.
        var input = "From: a@example.com\nTo: b@example.com\n\nhi\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalize(input, input.length, false, true, true, false);
        var rendered = new String(result.bytes(), StandardCharsets.UTF_8);
        // The LFs must NOT have been promoted to CRLF — the string must still contain bare LFs.
        assertEquals("From: a@example.com\nTo: b@example.com\n\nhi\n", rendered);
        assertFalse(result.endedWithCr(), "endedWithCr must be false in verbatim mode (CR is emitted immediately)");
        assertTrue(result.endsAtLineStart(), "endsAtLineStart must be true (last byte was LF)");
        // A bare-LF ending is NOT a CRLF ending: the framing guard in streamPayload depends on this.
        assertFalse(
                result.endsWithCrlf(),
                "endsWithCrlf must be false for a bare-LF ending so the framing CRLF is still emitted"
                        + " (rfc5321 §4.1.1.4)");
    }

    @Test
    void noNormalizeLeadingDotIsStillDotStuffed() {
        // Dot-stuffing applies in both normalize modes (rfc5321 §4.5.2). Even with
        // normalizeLineEndings=false the leading dot must get an extra dot prepended.
        var input = ".verbatim\nline\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalize(input, input.length, false, true, true, false);
        var rendered = new String(result.bytes(), StandardCharsets.UTF_8);
        assertEquals("..verbatim\nline\n", rendered);
    }

    @Test
    void noNormalizeExistingCrlfIsPreservedNotDoubled() {
        // An already-correct CRLF pair must be transmitted as a single CRLF even in no-normalize
        // mode — the verbatim path must not synthesize an extra CR.
        var input = "Subject: hi\r\n\r\nbody\r\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalize(input, input.length, false, true, true, false);
        assertEquals(
                "Subject: hi\r\n\r\nbody\r\n",
                new String(result.bytes(), StandardCharsets.UTF_8),
                "CRLF pairs must be preserved verbatim (not doubled)");
        assertTrue(result.endsWithCrlf(), "must end with CRLF so no framing CRLF is added");
    }

    @Test
    void noNormalizeCrlfEndingSetEndsWithCrlfTrue() {
        // A body ending with CRLF sets endsWithCrlf=true so streamPayload does NOT emit the
        // redundant framing CRLF. This is the no-normalize mirror of the normalize mode.
        var input = "tail\r\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalize(input, input.length, false, true, true, false);
        assertTrue(result.endsWithCrlf(), "endsWithCrlf must be true so streamPayload skips the framing CRLF");
    }

    // --- Terminator framing (via endsWithCrlf flag) ---

    @Test
    void terminatorFramingCrlfEmittedWhenBodyEndsInBareLf() {
        // rfc5321 §4.1.1.4: the terminating <CRLF>.<CRLF> requires the stream to already be at a
        // CRLF boundary. When normalizeLineEndings=false and the body ends in a bare LF, the wire
        // is NOT positioned after a CRLF, so streamPayload emits a framing CRLF before the dot.
        // This test exercises the NormalizedChunk flag that drives that decision.
        //
        // Body: "line\n" — bare-LF ending.
        var input = "line\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalize(input, input.length, false, true, true, false);
        assertTrue(result.endsAtLineStart(), "endsAtLineStart must be true (last byte was LF)");
        assertFalse(
                result.endsWithCrlf(),
                "endsWithCrlf must be false for bare-LF: the framing CRLF must be emitted before .<CRLF>");
        // streamPayload will emit CRLF + ".\r\n"; without the guard the server would see "line\n.\r\n"
        // and never find the required <CRLF>.<CRLF> terminator sequence.
    }

    @Test
    void terminatorFramingNotDoubledWhenBodyEndsInCrlf() {
        // Conversely, when the body already ends with CRLF (even in no-normalize mode) the flag
        // endsWithCrlf=true tells streamPayload NOT to add another CRLF, avoiding a spurious blank
        // line before the terminating dot.
        var input = "line\r\n".getBytes(StandardCharsets.UTF_8);
        var result = SmtpClient.normalize(input, input.length, false, true, true, false);
        assertTrue(
                result.endsWithCrlf(), "endsWithCrlf must be true so the framing CRLF is NOT doubled before .<CRLF>");
    }

    // --- SIZE preflight (rfc1870 §6) ---

    @Test
    void wireLengthIsSmallerWithNormalizeDisabledForLfOnlyBody() {
        // rfc1870 §6: SIZE declared in MAIL FROM must match the bytes actually transmitted.
        // computeWireMetrics uses the same normalize flag as streamPayload, so a LF-only body
        // measures SMALLER with normalizeLineEndings=false (each LF stays 1 byte) than with
        // =true (each LF becomes CRLF, 2 bytes). We verify this at the chunk level by comparing
        // the output lengths of the two normalize paths for the same LF-only input.
        var input = "line one\nline two\n".getBytes(StandardCharsets.UTF_8);

        var withNormalize = SmtpClient.normalize(input, input.length, false, true, false, true);
        var withoutNormalize = SmtpClient.normalize(input, input.length, false, true, false, false);

        assertTrue(
                withNormalize.bytes().length > withoutNormalize.bytes().length,
                "normalized output must be longer than verbatim (2 LFs → 2×CRLF adds 2 bytes)");
        // Exact delta: 2 bare LFs each expand to CRLF → +2 bytes.
        assertEquals(
                withoutNormalize.bytes().length + 2,
                withNormalize.bytes().length,
                "each bare LF adds exactly 1 extra byte when normalized");
    }
}
