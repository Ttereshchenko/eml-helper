package com.github.ttereshchenko.mailkit.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
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
        // Real multipart parts have a blank line separating their per-part headers from the body.
        String input = "Content-Type: multipart/mixed; boundary=\"abc123\"\n\n--abc123\n\nBody part\n--abc123--\n";
        List<IElementType> types = tokenTypes(input);
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BOUNDARY_START,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE,
                        EmlTokenTypes.BOUNDARY_END),
                types);
    }

    @Test
    void testBoundaryExtractionUnquoted() {
        String input = "Content-Type: multipart/mixed; boundary=abc123\n\n--abc123\n\nBody\n--abc123--\n";
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
                        EmlTokenTypes.HEADER_CONT_LINE,
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
                        EmlTokenTypes.HEADER_CONT_LINE,
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
        String content = Files.readString(Path.of("src/test/resources/samples/eml/non-journaled/3.eml"));
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
    void testBoundaryPatternInBodyIsIgnored() {
        // `boundary=` text in the body must NOT be harvested into the boundary set —
        // otherwise later body lines that happen to spell `--phantom` get mis-tokenized
        // as BOUNDARY_START / BOUNDARY_END.
        String input = "From: a@b.com\n\nboundary=\"bodybound\"\n--bodybound\nContent\n--bodybound--\n";
        List<IElementType> types = tokenTypes(input);
        assertFalse(types.contains(EmlTokenTypes.BOUNDARY_START));
        assertFalse(types.contains(EmlTokenTypes.BOUNDARY_END));
        for (int i = 2; i < types.size(); i++) {
            assertEquals(EmlTokenTypes.BODY_LINE, types.get(i));
        }
    }

    // ===== CRLF Line-Ending Tests =====

    @Test
    void testCrlfSimpleHeadersAndBody() {
        String input = "From: a@b.com\r\nTo: c@d.com\r\n\r\nHello\r\n";
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE),
                tokenTypes(input));
    }

    @Test
    void testCrlfBoundaryExtraction() {
        String input = "Content-Type: multipart/mixed; boundary=\"abc\"\r\n\r\n--abc\r\n\r\nPart\r\n--abc--\r\n";
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BOUNDARY_START,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE,
                        EmlTokenTypes.BOUNDARY_END),
                tokenTypes(input));
    }

    @Test
    void testCrlfContinuationHeader() {
        String input = "To: a@b.com,\r\n b@c.com\r\n\r\nBody\r\n";
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.HEADER_CONT_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE),
                tokenTypes(input));
    }

    @Test
    void testQuotedPrintableSoftBreakStaysBodyLine() {
        // Quoted-printable soft breaks end with '=' but stay as plain body lines for the lexer.
        String input = "Content-Transfer-Encoding: quoted-printable\n\nThis is a long line=\nthat continues here.\n";
        var types = tokenTypes(input);
        assertEquals(EmlTokenTypes.BODY_LINE, types.get(2));
        assertEquals(EmlTokenTypes.BODY_LINE, types.get(3));
    }

    // ===== Nested EML / per-part header tests =====

    @Test
    void testPerPartHeadersAfterBoundaryAreHeaderLines() {
        // Headers of a MIME part (Content-Type, Content-Transfer-Encoding, ...) sit right after the
        // boundary marker and must be tokenized as HEADER_LINE so the annotator can color them.
        String input = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: text/plain\nContent-Transfer-Encoding: 7bit\n\n"
                + "hello body\n--b--\n";
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BOUNDARY_START,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE,
                        EmlTokenTypes.BOUNDARY_END),
                tokenTypes(input));
    }

    @Test
    void testNestedRfc822HeadersAreHeaderLines() {
        // After a `Content-Type: message/rfc822` part, the blank line ending the part's headers
        // does NOT switch the lexer to body mode — the body of that part is itself an RFC 822
        // message, so its own header block must keep emitting HEADER_LINE tokens.
        String input = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: message/rfc822\n\n"
                + "Subject: nested\nFrom: a@b\n\n"
                + "nested body\n--b--\n";
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BOUNDARY_START,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE,
                        EmlTokenTypes.BOUNDARY_END),
                tokenTypes(input));
    }

    @Test
    void testDoubleNestedRfc822() {
        // rfc822 inside rfc822 — recursion must work at any depth.
        String input = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: message/rfc822\n\n"
                + "Content-Type: message/rfc822\n\n"
                + "Subject: innermost\n\n"
                + "deep body\n--b--\n";
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BOUNDARY_START,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE,
                        EmlTokenTypes.BOUNDARY_END),
                tokenTypes(input));
    }

    @Test
    void testMultipartInsideRfc822() {
        // A nested rfc822 message can itself wrap a multipart body — boundary tracking must keep
        // working alongside the rfc822 re-entry into header mode.
        String input = "Content-Type: multipart/mixed; boundary=\"outer\"\n\n"
                + "--outer\nContent-Type: message/rfc822\n\n"
                + "Subject: nested email\nContent-Type: multipart/mixed; boundary=\"inner\"\n\n"
                + "--inner\nContent-Type: text/plain\n\nHi\n--inner--\n--outer--\n";
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BOUNDARY_START,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BOUNDARY_START,
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE,
                        EmlTokenTypes.BOUNDARY_END,
                        EmlTokenTypes.BOUNDARY_END),
                tokenTypes(input));
    }

    @Test
    void testContentTypeMessageRfc822IsCaseInsensitive() {
        // RFC 822 header names and MIME types are case-insensitive; the rfc822 detection must
        // accept any common spelling and tolerate the optional space after the colon.
        String input = "content-type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nCONTENT-TYPE:Message/RFC822\n\n"
                + "Subject: nested\n\nbody\n--b--\n";
        var types = tokenTypes(input);
        // Index 5 is the nested message's "Subject: nested" line.
        assertEquals(EmlTokenTypes.HEADER_LINE, types.get(5));
    }

    @Test
    void testHeaderLikeLinesInTextPlainBodyStayBodyLine() {
        // Journal-report style: a text/plain part contains lines that LOOK like headers
        // (Sender:, To:, Subject:) but they are plain text, not real headers. The lexer must
        // NOT promote them to HEADER_LINE because the part is text/plain, not message/rfc822.
        String input = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: text/plain\n\n"
                + "Sender: foo@example.com\nSubject: looks like a header but is body\n--b--\n";
        var types = tokenTypes(input);
        // Indices: 0=outer header, 1=blank, 2=--b, 3=text/plain header, 4=blank, 5=Sender, 6=Subject, 7=--b--
        assertEquals(EmlTokenTypes.BODY_LINE, types.get(5));
        assertEquals(EmlTokenTypes.BODY_LINE, types.get(6));
    }

    @Test
    void testJournaledSampleAppleMailAttachment() throws IOException {
        // Real-world sample: outer Apple Mail message with an attached message/rfc822. The
        // nested email's "Subject: This is an attachment" line was previously mis-tokenized as
        // body content and therefore never highlighted.
        String content =
                Files.readString(Path.of("src/test/resources/samples/eml/journaled/email_with_email_attachment.eml"));
        var tokens = tokenize(content);
        var nestedSubject = tokens.stream()
                .filter(token -> token.text().startsWith("Subject: This is an attachment"))
                .findFirst()
                .orElseThrow();
        assertEquals(EmlTokenTypes.HEADER_LINE, nestedSubject.type());
        var nestedFrom = tokens.stream()
                .filter(token -> token.text().startsWith("From: Peter MacRobert"))
                .skip(1)
                .findFirst()
                .orElseThrow();
        assertEquals(EmlTokenTypes.HEADER_LINE, nestedFrom.type());
    }

    @Test
    void testProseMentioningBoundaryIsNotMarkers() throws IOException {
        // Regression: `boundary=` appearing inside a text/plain body must not seed the
        // boundary set, so the `--phantom` / `--phantom--` lines that follow stay BODY_LINE.
        String content = Files.readString(Path.of("src/test/resources/samples/eml/edge/prose_mentions_boundary.eml"));
        List<IElementType> types = tokenTypes(content);
        assertEquals(
                List.of(
                        EmlTokenTypes.HEADER_LINE,
                        EmlTokenTypes.BLANK_LINE,
                        EmlTokenTypes.BODY_LINE,
                        EmlTokenTypes.BODY_LINE,
                        EmlTokenTypes.BODY_LINE),
                types);
    }

    @Test
    void testMultipartWithPhantomBoundaryInBodyText() throws IOException {
        // Regression for two distinct issues:
        //   (1) phantom `boundary=` strings in part body must not seed the boundary set, so
        //       `--phantom` / `--phantom--` stay BODY_LINE;
        //   (2) when the part body literally contains the real `--real--` close (e.g. a doc
        //       snippet that quotes its parent boundary), only the LAST `--real--` is the
        //       structural close — earlier matching lines stay BODY_LINE.
        String content =
                Files.readString(Path.of("src/test/resources/samples/eml/edge/multipart_body_mentions_boundary.eml"));
        List<TokenInfo> tokens = tokenize(content);
        var realStart = tokens.stream()
                .filter(token -> token.text().startsWith("--real\n"))
                .findFirst()
                .orElseThrow();
        assertEquals(EmlTokenTypes.BOUNDARY_START, realStart.type());
        var realCloseShaped = tokens.stream()
                .filter(token -> token.text().startsWith("--real--"))
                .toList();
        assertTrue(realCloseShaped.size() >= 2, "fixture must contain at least two `--real--` lines");
        for (int i = 0; i < realCloseShaped.size() - 1; i++) {
            assertEquals(
                    EmlTokenTypes.BODY_LINE,
                    realCloseShaped.get(i).type(),
                    "non-last `--real--` line must be body text");
        }
        assertEquals(EmlTokenTypes.BOUNDARY_END, realCloseShaped.getLast().type(), "last `--real--` is the close");
        tokens.stream()
                .filter(token -> token.text().startsWith("--phantom"))
                .forEach(token -> assertEquals(EmlTokenTypes.BODY_LINE, token.type()));
    }

    @Test
    void testPostCloseEpilogueIsNotReTokenized() {
        // Under "last close wins", the middle `--b--` is body text and the trailing one is
        // the real close.
        String input =
                "Content-Type: multipart/mixed; boundary=\"b\"\n\n" + "--b\n\nbody\n" + "--b--\nepilogue text\n--b--\n";
        var tokens = tokenize(input);
        var closeShaped = tokens.stream()
                .filter(token -> token.text().startsWith("--b--"))
                .toList();
        assertEquals(2, closeShaped.size());
        assertEquals(EmlTokenTypes.BODY_LINE, closeShaped.get(0).type());
        assertEquals(EmlTokenTypes.BOUNDARY_END, closeShaped.get(1).type());
    }

    @Test
    void testJournaledSampleJournalReport() throws IOException {
        // Real-world journal-report sample (Exchange-style X-MS-Journal-Report). The nested
        // message/rfc822 attachment's Date/From/Subject/etc. were never highlighted.
        String content = Files.readString(Path.of("src/test/resources/samples/eml/journaled/journaled_1.eml"));
        var tokens = tokenize(content);
        // Nested Message-ID is unique to the attached message.
        var nestedMessageId = tokens.stream()
                .filter(token -> token.text().startsWith("Message-ID: <2QKJUVIHJEJ5UE.JavaMail.vcap"))
                .findFirst()
                .orElseThrow();
        assertEquals(EmlTokenTypes.HEADER_LINE, nestedMessageId.type());
        // Journal summary lines inside text/plain (lines 19-24) must remain BODY_LINE.
        var summarySender = tokens.stream()
                .filter(token -> token.text().startsWith("Sender: Janie190860"))
                .findFirst()
                .orElseThrow();
        assertEquals(EmlTokenTypes.BODY_LINE, summarySender.type());
    }

    @Test
    void testRestartOnSameBufferKeepsTokenizationConsistent() {
        // Regression: EmlLexer caches EmlBoundaryParser.collect by buffer identity.
        // Repeated start() calls on the same buffer must produce identical token streams
        // (cache hit), and a different buffer must produce its own tokens (cache miss).
        var firstInput = "Content-Type: multipart/mixed; boundary=\"abc\"\n\n--abc\nbody\n--abc--\n";
        var secondInput = "Content-Type: multipart/mixed; boundary=\"xyz\"\n\n--xyz\nbody\n--xyz--\n";

        var lexer = new EmlLexer();

        lexer.start(firstInput, 0, firstInput.length(), 0);
        var firstRun = drain(lexer);

        lexer.start(firstInput, 0, firstInput.length(), 0);
        var secondRun = drain(lexer);
        assertEquals(firstRun, secondRun);

        lexer.start(secondInput, 0, secondInput.length(), 0);
        var thirdRun = drain(lexer);
        var thirdTypes = thirdRun.stream().map(TokenInfo::type).toList();
        assertTrue(
                thirdTypes.contains(EmlTokenTypes.BOUNDARY_START),
                "Switching to a buffer with a different boundary must not reuse the stale cache");
        assertTrue(thirdTypes.contains(EmlTokenTypes.BOUNDARY_END));
        var boundaryTexts = thirdRun.stream()
                .filter(token ->
                        token.type() == EmlTokenTypes.BOUNDARY_START || token.type() == EmlTokenTypes.BOUNDARY_END)
                .map(TokenInfo::text)
                .toList();
        assertTrue(
                boundaryTexts.stream().allMatch(text -> text.contains("xyz")),
                "Cache must invalidate on a new buffer; got " + boundaryTexts);
    }

    private static List<TokenInfo> drain(EmlLexer lexer) {
        var tokens = new ArrayList<TokenInfo>();
        while (lexer.getTokenType() != null) {
            tokens.add(new TokenInfo(
                    lexer.getTokenType(),
                    lexer.getTokenStart(),
                    lexer.getTokenEnd(),
                    lexer.getBufferSequence()
                            .subSequence(lexer.getTokenStart(), lexer.getTokenEnd())
                            .toString()));
            lexer.advance();
        }
        return tokens;
    }
}
