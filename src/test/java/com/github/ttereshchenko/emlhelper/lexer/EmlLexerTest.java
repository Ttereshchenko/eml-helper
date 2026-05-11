package com.github.ttereshchenko.emlhelper.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.emlhelper.EmlTokenTypes;
import com.intellij.psi.tree.IElementType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmlLexerTest {

    record TokenInfo(IElementType type, int start, int end, String text) {}

    private List<TokenInfo> tokenize(String input) {
        EmlLexer lexer = new EmlLexer();
        lexer.start(input, 0, input.length(), 0);
        List<TokenInfo> tokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            tokens.add(new TokenInfo(
                    lexer.getTokenType(),
                    lexer.getTokenStart(),
                    lexer.getTokenEnd(),
                    input.substring(lexer.getTokenStart(), lexer.getTokenEnd())));
            lexer.advance();
        }
        return tokens;
    }

    private List<IElementType> tokenTypes(String input) {
        return tokenize(input).stream().map(TokenInfo::type).toList();
    }

    // ===== Positive Tests =====

    @Test
    void testSimpleHeadersAndBody() {
        String input = "From: a@b.com\nTo: c@d.com\n\nHello\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE),
                types);
    }

    @Test
    void testBoundaryExtraction() {
        String input = "Content-Type: multipart/mixed; boundary=\"abc123\"\n\n--abc123\nBody part\n--abc123--\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BOUNDARY_START,
                        EmlTokenTypes.BODY_LINE,
                        EmlTokenTypes.BOUNDARY_END),
                types);
    }

    @Test
    void testBoundaryExtractionUnquoted() {
        String input = "Content-Type: multipart/mixed; boundary=abc123\n\n--abc123\nBody\n--abc123--\n";
        List<IElementType> types = tokenTypes(input);
        assertTrue(types.contains(EmlTokenTypes.BOUNDARY_START));
        assertTrue(types.contains(EmlTokenTypes.BOUNDARY_END));
    }

    @Test
    void testMultipleBoundaries() {
        String input = "Content-Type: multipart/mixed; boundary=\"outer\"\n\n"
                + "--outer\nContent-Type: multipart/alternative; boundary=\"inner\"\n\n"
                + "--inner\nText\n--inner--\n--outer--\n";
        List<IElementType> types = tokenTypes(input);

        long boundaryStartCount = types.stream()
                .filter(tokenType -> tokenType == EmlTokenTypes.BOUNDARY_START)
                .count();
        long boundaryEndCount = types.stream()
                .filter(tokenType -> tokenType == EmlTokenTypes.BOUNDARY_END)
                .count();
        assertEquals(2, boundaryStartCount);
        assertEquals(2, boundaryEndCount);
    }

    @Test
    void testDuplicateBoundaryDeduplication() {
        String input = "Content-Type: multipart/mixed; boundary=\"dup\"\n"
                + "X-Other: multipart/mixed; boundary=\"dup\"\n\n" + "--dup\nBody\n--dup--\n";
        List<IElementType> types = tokenTypes(input);

        long boundaryStartCount = types.stream()
                .filter(tokenType -> tokenType == EmlTokenTypes.BOUNDARY_START)
                .count();
        assertEquals(1, boundaryStartCount);
    }

    @Test
    void testContinuationHeaderLine() {
        String input = "To: a@b.com,\n b@c.com\n\nBody\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE),
                types);
    }

    @Test
    void testTabContinuationHeaderLine() {
        String input = "To: a@b.com,\n\tb@c.com\n\nBody\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE),
                types);
    }

    @Test
    void testEmptyBodyAfterHeaders() {
        String input = "From: a@b.com\n\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(List.of(EmlTokenTypes.HEADER_LINE, EmlTokenTypes.BLANK_LINE), types);
    }

    @Test
    void testOnlyBody() {
        String input = "\nHello World\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(List.of(EmlTokenTypes.BLANK_LINE, EmlTokenTypes.BODY_LINE), types);
    }

    @Test
    void testMultipleBoundaryStartLines() {
        String input =
                "Content-Type: multipart/mixed; boundary=\"sep\"\n\n" + "--sep\nPart 1\n--sep\nPart 2\n--sep--\n";
        List<IElementType> types = tokenTypes(input);

        long startCount = types.stream()
                .filter(tokenType -> tokenType == EmlTokenTypes.BOUNDARY_START)
                .count();
        assertEquals(2, startCount);
    }

    @Test
    void testTokenOffsets() {
        String input = "From: a\nTo: b\n";
        List<TokenInfo> tokens = tokenize(input);
        assertEquals(2, tokens.size());

        assertEquals(0, tokens.get(0).start());
        assertEquals(8, tokens.get(0).end());
        assertEquals("From: a\n", tokens.get(0).text());

        assertEquals(8, tokens.get(1).start());
        assertEquals(14, tokens.get(1).end());
        assertEquals("To: b\n", tokens.get(1).text());
    }

    @Test
    void testStateTransition() {
        String input = "From: a\n\nBody\n";
        EmlLexer lexer = new EmlLexer();
        lexer.start(input, 0, input.length(), 0);

        // First token: header, state should be 0 (in headers)
        assertEquals(EmlTokenTypes.HEADER_LINE, lexer.getTokenType());
        assertEquals(0, lexer.getState());

        lexer.advance();
        // Blank line, transitions to body, state becomes 1
        assertEquals(EmlTokenTypes.BLANK_LINE, lexer.getTokenType());
        assertEquals(1, lexer.getState());

        lexer.advance();
        // Body line, state stays 1
        assertEquals(EmlTokenTypes.BODY_LINE, lexer.getTokenType());
        assertEquals(1, lexer.getState());
    }

    @Test
    void testInitialStateBody() {
        String input = "Not a header\n";
        EmlLexer lexer = new EmlLexer();
        lexer.start(input, 0, input.length(), 1);

        assertEquals(EmlTokenTypes.BODY_LINE, lexer.getTokenType());
        assertEquals(1, lexer.getState());
    }

    @Test
    void testGetBufferSequence() {
        String input = "From: test\n";
        EmlLexer lexer = new EmlLexer();
        lexer.start(input, 0, input.length(), 0);
        assertSame(input, lexer.getBufferSequence());
    }

    @Test
    void testGetBufferEnd() {
        String input = "From: test\n";
        EmlLexer lexer = new EmlLexer();
        lexer.start(input, 0, input.length(), 0);
        assertEquals(input.length(), lexer.getBufferEnd());
    }

    @Test
    void testRealEmlFile() throws IOException {
        String content = Files.readString(Path.of("src/test/resources/samples/3.eml"));
        List<TokenInfo> tokens = tokenize(content);

        // File starts with headers
        assertEquals(EmlTokenTypes.HEADER_LINE, tokens.getFirst().type());

        // Should contain boundary markers (file has boundary="------------26A45336F6C6196BD8BBA2A2")
        boolean hasBoundaryStart = tokens.stream().anyMatch(token -> token.type() == EmlTokenTypes.BOUNDARY_START);
        boolean hasBoundaryEnd = tokens.stream().anyMatch(token -> token.type() == EmlTokenTypes.BOUNDARY_END);
        boolean hasBlankLine = tokens.stream().anyMatch(token -> token.type() == EmlTokenTypes.BLANK_LINE);
        assertTrue(hasBoundaryStart, "Should have boundary start markers");
        assertTrue(hasBoundaryEnd, "Should have boundary end markers");
        assertTrue(hasBlankLine, "Should have blank line separating headers from body");
    }

    // ===== Negative Tests =====

    @Test
    void testEmptyInput() {
        String input = "";
        EmlLexer lexer = new EmlLexer();
        lexer.start(input, 0, input.length(), 0);
        assertNull(lexer.getTokenType());
    }

    @Test
    void testNoBoundaryDefined() {
        String input = "From: a@b.com\n\n--notaboundary\nBody text\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE,
                        EmlTokenTypes.BODY_LINE),
                types);
    }

    @Test
    void testBoundaryLikeTextNotMatching() {
        String input = "Content-Type: multipart/mixed; boundary=\"abc123\"\n\n--xyz789\nBody\n";
        List<IElementType> types = tokenTypes(input);
        // --xyz789 should be BODY_LINE since it doesn't match the defined boundary
        assertEquals(EmlTokenTypes.BODY_LINE, types.get(2));
    }

    @Test
    void testPartialBoundaryMatch() {
        String input = "Content-Type: multipart/mixed; boundary=\"abc123\"\n\n--abc12\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(EmlTokenTypes.BODY_LINE, types.get(2));
    }

    @Test
    void testBoundaryWithExtraText() {
        String input = "Content-Type: multipart/mixed; boundary=\"abc123\"\n\n--abc123extra\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(EmlTokenTypes.BODY_LINE, types.get(2));
    }

    @Test
    void testBoundaryEndWithExtraText() {
        String input = "Content-Type: multipart/mixed; boundary=\"abc123\"\n\n--abc123--extra\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(EmlTokenTypes.BODY_LINE, types.get(2));
    }

    @Test
    void testOnlyNewlines() {
        String input = "\n\n\n";
        List<IElementType> types = tokenTypes(input);
        // First \n is blank line (transitions to body), remaining are body lines
        assertEquals(EmlTokenTypes.BLANK_LINE, types.get(0));
        for (int i = 1; i < types.size(); i++) {
            assertEquals(EmlTokenTypes.BODY_LINE, types.get(i));
        }
    }

    @Test
    void testNoNewlineAtEnd() {
        String input = "From: a@b.com";
        List<TokenInfo> tokens = tokenize(input);
        assertEquals(1, tokens.size());
        assertEquals(EmlTokenTypes.HEADER_LINE, tokens.getFirst().type());
        assertEquals(0, tokens.getFirst().start());
        assertEquals(input.length(), tokens.getFirst().end());
    }

    @Test
    void testBoundaryPatternInBodyIsStillExtracted() {
        // boundary= pattern in body text is also extracted by the lexer
        // (it scans the full buffer with regex)
        String input = "From: a@b.com\n\nboundary=\"bodybound\"\n--bodybound\nContent\n--bodybound--\n";
        List<IElementType> types = tokenTypes(input);
        assertTrue(types.contains(EmlTokenTypes.BOUNDARY_START));
        assertTrue(types.contains(EmlTokenTypes.BOUNDARY_END));
    }
}
