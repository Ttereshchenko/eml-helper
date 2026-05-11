package com.github.ttereshchenko.emlhelper.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmlBoundaryParserTest {

    @Test
    void emptyTextProducesEmptyParser() {
        var parser = EmlBoundaryParser.collect("");
        assertTrue(parser.isEmpty());
        assertFalse(parser.isStart("--anything"));
        assertFalse(parser.isEnd("--anything--"));
        assertEquals(Set.of(), parser.rawNames());
    }

    @Test
    void textWithoutBoundaryProducesEmptyParser() {
        var parser = EmlBoundaryParser.collect("From: a@b.com\nSubject: hi\n\nbody\n");
        assertTrue(parser.isEmpty());
    }

    @Test
    void quotedBoundaryIsExtracted() {
        var parser = EmlBoundaryParser.collect("Content-Type: multipart/mixed; boundary=\"abc123\"\n");
        assertTrue(parser.isStart("--abc123"));
        assertTrue(parser.isEnd("--abc123--"));
        assertEquals(Set.of("abc123"), parser.rawNames());
    }

    @Test
    void unquotedBoundaryIsExtracted() {
        var parser = EmlBoundaryParser.collect("Content-Type: multipart/mixed; boundary=abc123\n");
        assertTrue(parser.isStart("--abc123"));
        assertTrue(parser.isEnd("--abc123--"));
    }

    @Test
    void duplicateBoundariesAreDeduplicated() {
        var parser = EmlBoundaryParser.collect(
                "Content-Type: multipart/mixed; boundary=\"dup\"\n" + "X-Other: multipart/mixed; boundary=\"dup\"\n");
        assertEquals(1, parser.rawNames().size());
    }

    @Test
    void multipleDistinctBoundariesAreExtracted() {
        var parser = EmlBoundaryParser.collect("Content-Type: multipart/mixed; boundary=\"outer\"\n"
                + "Content-Type: multipart/alternative; boundary=\"inner\"\n");
        assertEquals(Set.of("outer", "inner"), parser.rawNames());
        assertTrue(parser.isStart("--outer"));
        assertTrue(parser.isStart("--inner"));
        assertTrue(parser.isEnd("--outer--"));
        assertTrue(parser.isEnd("--inner--"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"boundary=foo", "BOUNDARY=foo", "Boundary=foo", "boundary = foo", "boundary=\t\"foo\""})
    void boundaryKeywordIsCaseInsensitiveWithSpaceTolerance(String text) {
        var parser = EmlBoundaryParser.collect("Content-Type: multipart/mixed; " + text + "\n");
        assertTrue(parser.isStart("--foo"), text);
    }

    @Test
    void notAMarkerReturnsFalse() {
        var parser = EmlBoundaryParser.collect("Content-Type: multipart/mixed; boundary=\"abc\"\n");
        assertFalse(parser.isStart("--abcd"));
        assertFalse(parser.isStart("--ab"));
        assertFalse(parser.isStart("abc"));
        assertFalse(parser.isStart("--abc--extra"));
        assertFalse(parser.isEnd("--abc"));
    }

    @Test
    void endMarkerIsNotConfusedWithStartMarker() {
        var parser = EmlBoundaryParser.collect("Content-Type: multipart/mixed; boundary=\"foo\"\n");
        assertFalse(parser.isStart("--foo--"));
        assertFalse(parser.isEnd("--foo"));
    }

    @Test
    void boundaryWithTrailingDashesProducesDistinctMarkers() {
        // RFC 2046 allows '-' in boundary; verify both markers are correct.
        var parser = EmlBoundaryParser.collect("Content-Type: multipart/mixed; boundary=\"X-\"\n");
        assertTrue(parser.isStart("--X-"));
        assertTrue(parser.isEnd("--X---"));
        assertFalse(parser.isStart("--X---"));
    }

    @Test
    void nullTextThrows() {
        assertThrows(NullPointerException.class, () -> EmlBoundaryParser.collect(null));
    }
}
