package com.github.ttereshchenko.mailkit.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.intellij.psi.tree.IElementType;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmlBoundaryParserTest {

    private static IElementType classifyLine(EmlBoundaryParser parser, String buffer, String lineText) {
        var offset = buffer.indexOf(lineText);
        if (offset < 0) {
            throw new AssertionError("Line not found in buffer: " + lineText);
        }
        return parser.classifyBoundary(offset);
    }

    private static IElementType classifyNthLine(EmlBoundaryParser parser, String buffer, String lineText, int nth) {
        var offset = -1;
        for (var i = 0; i <= nth; i++) {
            offset = buffer.indexOf(lineText, offset + 1);
            if (offset < 0) {
                throw new AssertionError("Occurrence " + nth + " of line not found in buffer: " + lineText);
            }
        }
        return parser.classifyBoundary(offset);
    }

    @Test
    void largeBodyLineIsNeverMaterialized() {
        // Regression: collect() used subSequence(...).stripTrailing() on EVERY line, so a multi-MB
        // single-line base64 body was copied into a String on each call — thrashing GC and lagging
        // typing. Drive collect() through a CharSequence that records the largest slice requested;
        // the giant body line must never be materialized.
        var prologue = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: application/octet-stream\nContent-Transfer-Encoding: base64\n\n";
        var hugeBodyLine = "A".repeat(1_000_000);
        var recording = new RecordingCharSequence(prologue + hugeBodyLine + "\n--b--\n");

        EmlBoundaryParser.collect(recording);

        assertTrue(
                recording.maxSliceLength() < 10_000,
                "collect() must never copy the multi-MB body line; largest slice was " + recording.maxSliceLength());
    }

    @Test
    void emptyTextProducesEmptyParser() {
        var parser = EmlBoundaryParser.collect("");
        assertTrue(parser.isEmpty());
        assertEquals(Set.of(), parser.rawNames());
    }

    @Test
    void textWithoutBoundaryProducesEmptyParser() {
        var parser = EmlBoundaryParser.collect("From: a@b.com\nSubject: hi\n\nbody\n");
        assertTrue(parser.isEmpty());
    }

    @Test
    void quotedBoundaryIsExtracted() {
        var buffer = "Content-Type: multipart/mixed; boundary=\"abc123\"\n\n--abc123\n\nBody\n--abc123--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertEquals(Set.of("abc123"), parser.rawNames());
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--abc123\n"));
        assertSame(EmlTokenTypes.BOUNDARY_END, classifyLine(parser, buffer, "--abc123--\n"));
    }

    @Test
    void unquotedBoundaryIsExtracted() {
        var buffer = "Content-Type: multipart/mixed; boundary=abc123\n\n--abc123\n\nBody\n--abc123--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--abc123\n"));
        assertSame(EmlTokenTypes.BOUNDARY_END, classifyLine(parser, buffer, "--abc123--\n"));
    }

    @Test
    void quotedBoundaryWithInternalWhitespaceIsExtracted() {
        // RFC 2046 bchars allow SP inside a quoted boundary. The legacy regex captured only "ab",
        // so `--ab cd` lines were never classified — the whole multipart lexed as a flat body.
        var buffer = "Content-Type: multipart/mixed; boundary=\"ab cd\"\n\n--ab cd\nBody\n--ab cd--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertEquals(Set.of("ab cd"), parser.rawNames());
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--ab cd\n"));
        assertSame(EmlTokenTypes.BOUNDARY_END, classifyLine(parser, buffer, "--ab cd--\n"));
    }

    @Test
    void quotedEmptyBoundaryIsIgnored() {
        // boundary="" is RFC-illegal; ensure we never add the empty string to rawNames, otherwise
        // matchBoundary's Set.contains check would mis-classify random `--` / `----` body lines.
        var parser = EmlBoundaryParser.collect("Content-Type: multipart/mixed; boundary=\"\"\n\n--\nbody\n----\n");
        assertTrue(parser.isEmpty());
    }

    @Test
    void duplicateBoundariesAreDeduplicated() {
        var parser = EmlBoundaryParser.collect(
                "Content-Type: multipart/mixed; boundary=\"dup\"\n" + "X-Other: multipart/mixed; boundary=\"dup\"\n");
        assertEquals(1, parser.rawNames().size());
    }

    @Test
    void multipleDistinctBoundariesAreExtracted() {
        var buffer = "Content-Type: multipart/mixed; boundary=\"outer\"\n"
                + "\n"
                + "--outer\n"
                + "Content-Type: multipart/alternative; boundary=\"inner\"\n"
                + "\n"
                + "--inner\nText\n--inner--\n"
                + "--outer--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertEquals(Set.of("outer", "inner"), parser.rawNames());
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--outer\n"));
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--inner\n"));
        assertSame(EmlTokenTypes.BOUNDARY_END, classifyLine(parser, buffer, "--inner--\n"));
        assertSame(EmlTokenTypes.BOUNDARY_END, classifyLine(parser, buffer, "--outer--\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"boundary=foo", "BOUNDARY=foo", "Boundary=foo", "boundary = foo", "boundary=\t\"foo\""})
    void boundaryKeywordIsCaseInsensitiveWithSpaceTolerance(String text) {
        var buffer = "Content-Type: multipart/mixed; " + text + "\n\n--foo\nbody\n--foo--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--foo\n"), text);
    }

    @Test
    void notAMarkerReturnsNullClassification() {
        // Lines that look near-similar to the boundary but don't match exactly stay unclassified.
        var buffer = "Content-Type: multipart/mixed; boundary=\"abc\"\n"
                + "\n"
                + "--abcd\n"
                + "--ab\n"
                + "abc\n"
                + "--abc--extra\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertNull(classifyLine(parser, buffer, "--abcd\n"));
        assertNull(classifyLine(parser, buffer, "--ab\n"));
        assertNull(classifyLine(parser, buffer, "abc\n"));
        assertNull(classifyLine(parser, buffer, "--abc--extra\n"));
    }

    @Test
    void endMarkerIsNotConfusedWithStartMarker() {
        var buffer = "Content-Type: multipart/mixed; boundary=\"foo\"\n\n--foo\nbody\n--foo--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--foo\n"));
        assertSame(EmlTokenTypes.BOUNDARY_END, classifyLine(parser, buffer, "--foo--\n"));
    }

    @Test
    void boundaryWithTrailingDashesProducesDistinctMarkers() {
        // RFC 2046 allows '-' in boundary; verify --X- (open) and --X--- (close) classify correctly.
        var buffer = "Content-Type: multipart/mixed; boundary=\"X-\"\n\n--X-\nbody\n--X---\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--X-\n"));
        assertSame(EmlTokenTypes.BOUNDARY_END, classifyLine(parser, buffer, "--X---\n"));
    }

    @Test
    void nullTextThrows() {
        assertThrows(NullPointerException.class, () -> EmlBoundaryParser.collect(null));
    }

    // ===== Regression: collect() must only look at header lines for `boundary=` declarations =====

    @Test
    void proseInBodyDoesNotInjectPhantomBoundary() {
        var parser = EmlBoundaryParser.collect("Content-Type: multipart/mixed; boundary=\"real\"\n"
                + "\n"
                + "--real\n"
                + "Content-Type: text/plain\n"
                + "\n"
                + "note that boundary=\"phantom\" appears here\n"
                + "--real--\n");
        assertEquals(Set.of("real"), parser.rawNames());
    }

    @Test
    void boundaryMentionInBodyOfNonMultipartMessageIsIgnored() {
        var parser = EmlBoundaryParser.collect("From: a@b.com\n\nthe word boundary=\"phantom\" is in the body\n");
        assertTrue(parser.isEmpty());
    }

    @Test
    void attachedEmlInBodyDoesNotLeakItsBoundary() {
        // A text/plain part whose body literally embeds the headers of another EML —
        // including its own `boundary="leaked"` declaration and a `--leaked` line.
        var buffer = "Content-Type: multipart/mixed; boundary=\"outer\"\n"
                + "\n"
                + "--outer\n"
                + "Content-Type: text/plain\n"
                + "\n"
                + "Forwarded message follows:\n"
                + "Content-Type: multipart/mixed; boundary=\"leaked\"\n"
                + "\n"
                + "--leaked\n"
                + "fake part body\n"
                + "--leaked--\n"
                + "--outer--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertEquals(Set.of("outer"), parser.rawNames());
        assertNull(classifyLine(parser, buffer, "--leaked\n"));
        assertNull(classifyLine(parser, buffer, "--leaked--\n"));
    }

    @Test
    void boundaryLikeStringInBase64BodyIsIgnored() {
        var buffer = "Content-Type: multipart/mixed; boundary=\"outer\"\n"
                + "\n"
                + "--outer\n"
                + "Content-Type: application/octet-stream\n"
                + "Content-Transfer-Encoding: base64\n"
                + "\n"
                + "Ym91bmRhcnk9ImxlYWtlZCIK boundary=\"leaked\"\n"
                + "--outer--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertEquals(Set.of("outer"), parser.rawNames());
    }

    @Test
    void foldedContentTypeContinuationIsExtracted() {
        var buffer = "Content-Type: multipart/mixed;\n boundary=\"folded\"\n\n--folded\nbody\n--folded--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--folded\n"));
    }

    @Test
    void nestedRfc822HeadersContributeBoundaries() {
        var buffer = "Content-Type: message/rfc822\n"
                + "\n"
                + "Content-Type: multipart/mixed; boundary=\"nested\"\n"
                + "\n"
                + "--nested\nbody\n--nested--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--nested\n"));
    }

    @Test
    void perPartHeaderAfterBoundaryContributesInnerBoundary() {
        var parser = EmlBoundaryParser.collect("Content-Type: multipart/mixed; boundary=\"outer\"\n"
                + "\n"
                + "--outer\n"
                + "Content-Type: multipart/alternative; boundary=\"inner\"\n"
                + "\n"
                + "--inner\nText\n--inner--\n"
                + "--outer--\n");
        assertEquals(Set.of("outer", "inner"), parser.rawNames());
    }

    // ===== "Last close wins": boundary literals inside text/* part bodies =====

    @Test
    void lastClosingMarkerWinsWhenBodyContainsOne() {
        // The first `--real--` line lives inside a text/plain part body. The actual structural
        // close is the LAST `--real--` line.
        var buffer = "Content-Type: multipart/mixed; boundary=\"real\"\n"
                + "\n"
                + "--real\n"
                + "Content-Type: text/plain\n"
                + "\n"
                + "Body part that quotes its parent boundary verbatim:\n"
                + "--real--\n"
                + "trailing prose\n"
                + "--real--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--real\n"));
        assertNull(classifyNthLine(parser, buffer, "--real--\n", 0));
        assertSame(EmlTokenTypes.BOUNDARY_END, classifyNthLine(parser, buffer, "--real--\n", 1));
    }

    @Test
    void repeatedClosingMarkerInEpilogueResolvesToLast() {
        var buffer = "Content-Type: multipart/mixed; boundary=\"b\"\n\n--b\nBody A\n--b--\n--b--\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertNull(classifyNthLine(parser, buffer, "--b--\n", 0));
        assertSame(EmlTokenTypes.BOUNDARY_END, classifyNthLine(parser, buffer, "--b--\n", 1));
    }

    @Test
    void incompleteMultipartHasNoCloseToken() {
        var buffer = "Content-Type: multipart/mixed; boundary=\"x\"\n\n--x\nBody only\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyLine(parser, buffer, "--x\n"));
    }

    @Test
    void boundaryAfterClosingMarkerIsNotStart() {
        // A `--name` line that appears AFTER the resolved close stays plain body.
        var buffer = "Content-Type: multipart/mixed; boundary=\"b\"\n"
                + "\n"
                + "--b\nBody\n--b--\n"
                + "--b\nepilogue text matching open shape\n";
        var parser = EmlBoundaryParser.collect(buffer);
        assertSame(EmlTokenTypes.BOUNDARY_START, classifyNthLine(parser, buffer, "--b\n", 0));
        assertSame(EmlTokenTypes.BOUNDARY_END, classifyLine(parser, buffer, "--b--\n"));
        assertNull(classifyNthLine(parser, buffer, "--b\n", 1));
    }
}
