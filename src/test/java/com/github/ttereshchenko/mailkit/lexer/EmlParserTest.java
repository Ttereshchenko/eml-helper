package com.github.ttereshchenko.mailkit.lexer;

import com.github.ttereshchenko.mailkit.psi.EmlHeaderBlock;
import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
import com.github.ttereshchenko.mailkit.psi.EmlNestedMessage;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class EmlParserTest extends BasePlatformTestCase {

    private PsiFile parseFromText(String content) {
        return myFixture.configureByText("test.eml", content);
    }

    public void testFlatMessageHasHeaderBlockAndBody() {
        var file = parseFromText("From: a@b.com\nSubject: hi\n\nBody text\n");
        var block = PsiTreeUtil.getChildOfType(file, EmlHeaderBlock.class);
        assertNotNull(block);
        var headers = block.getHeaders();
        assertEquals(2, headers.size());
        var fromHeader = block.findHeader("From");
        assertNotNull(fromHeader);
        assertEquals("From", fromHeader.getHeaderName());
        assertEquals("a@b.com", fromHeader.getRawValue());
    }

    public void testFoldedContinuationJoinsIntoSingleHeader() {
        var file = parseFromText("To: a@b.com,\n c@d.com\n\nBody\n");
        var block = PsiTreeUtil.getChildOfType(file, EmlHeaderBlock.class);
        assertNotNull(block);
        assertEquals(1, block.getHeaders().size());
        var toHeader = block.findHeader("To");
        assertNotNull(toHeader);
        assertEquals("a@b.com, c@d.com", toHeader.getRawValue());
    }

    public void testMultipartProducesMimePartChildren() {
        var content = "Content-Type: multipart/mixed; boundary=\"abc\"\n\n"
                + "--abc\nContent-Type: text/plain\n\nPart one\n"
                + "--abc\nContent-Type: text/html\n\n<p>Part two</p>\n"
                + "--abc--\n";
        var file = parseFromText(content);
        var parts = PsiTreeUtil.findChildrenOfType(file, EmlMimePart.class);
        assertEquals(2, parts.size());
        for (var part : parts) {
            assertNotNull(part.getBoundaryName());
            assertEquals("abc", part.getBoundaryName());
        }
    }

    public void testNestedMultipartProducesNestedMimeParts() {
        var content = "Content-Type: multipart/mixed; boundary=\"outer\"\n\n"
                + "--outer\nContent-Type: multipart/alternative; boundary=\"inner\"\n\n"
                + "--inner\nContent-Type: text/plain\n\nplain\n"
                + "--inner\nContent-Type: text/html\n\n<p>html</p>\n"
                + "--inner--\n"
                + "--outer--\n";
        var file = parseFromText(content);
        var allParts = PsiTreeUtil.findChildrenOfType(file, EmlMimePart.class);
        long outerCount = allParts.stream()
                .filter(part -> "outer".equals(part.getBoundaryName()))
                .count();
        long innerCount = allParts.stream()
                .filter(part -> "inner".equals(part.getBoundaryName()))
                .count();
        assertEquals(1, outerCount);
        assertEquals(2, innerCount);
    }

    public void testMessageRfc822ProducesNestedMessage() {
        var content = "From: outer@example.com\n"
                + "Content-Type: message/rfc822\n\n"
                + "From: inner@example.com\n"
                + "Subject: nested\n\n"
                + "Inner body\n";
        var file = parseFromText(content);
        var nested = PsiTreeUtil.findChildOfType(file, EmlNestedMessage.class);
        assertNotNull(nested);
        var innerBlock = nested.getHeaderBlock();
        assertNotNull(innerBlock);
        assertNotNull(innerBlock.findHeader("Subject"));
    }

    public void testMissingTerminatorBoundaryDoesNotCrash() throws IOException {
        var content = Files.readString(Path.of("src/test/resources/samples/eml/edge/missing_end_boundary.eml"));
        var file = parseFromText(content);
        var parts = PsiTreeUtil.findChildrenOfType(file, EmlMimePart.class);
        assertEquals(1, parts.size());
        // No closing --abc-- means the MimePart extends to EOF; folding still produces a descriptor.
        var part = parts.iterator().next();
        assertNotNull(part.getContentRange());
    }

    public void testNestedRfc822WithCrlf() throws IOException {
        var content = Files.readString(Path.of("src/test/resources/samples/eml/edge/nested_rfc822_crlf.eml"));
        var file = parseFromText(content);
        var nested = PsiTreeUtil.findChildOfType(file, EmlNestedMessage.class);
        assertNotNull(nested);
        var inner = nested.getHeaderBlock();
        assertNotNull(inner);
        assertNotNull(inner.findHeader("Subject"));
        assertEquals("Original subject", inner.findHeader("Subject").getRawValue());
    }

    public void testRfc2047EncodedSubjectDecodes() throws IOException {
        var content = Files.readString(Path.of("src/test/resources/samples/eml/edge/rfc2047_subject.eml"));
        var file = parseFromText(content);
        var block = PsiTreeUtil.getChildOfType(file, EmlHeaderBlock.class);
        assertNotNull(block);
        var subject = block.findHeader("Subject");
        assertNotNull(subject);
        // Raw value retains the encoded form
        assertTrue(subject.getRawValue().startsWith("=?UTF-8?Q?"));
        // Decoded value renders the world emoji literally
        assertEquals("Hello 世界", subject.getDecodedValue());
    }

    public void testBomPrefixedContentDoesNotBreakHeaders() {
        var content = "﻿From: a@b.com\nSubject: hi\n\nBody\n";
        var file = parseFromText(content);
        var block = PsiTreeUtil.getChildOfType(file, EmlHeaderBlock.class);
        assertNotNull(block);
        // First "header line" includes the BOM; we still expect From to be findable via raw substring
        // tolerance — flag the actual behavior so a regression here is visible.
        var firstLine = block.getHeaders().iterator().next();
        assertTrue(firstLine.getText().contains("From: a@b.com"));
    }

    public void testNoContentTypeFallsBackToPlainBody() {
        var file = parseFromText("From: a@b.com\n\nplain\nbody\nlines\n");
        var parts = PsiTreeUtil.findChildrenOfType(file, EmlMimePart.class);
        assertEmpty(parts);
        var nested = PsiTreeUtil.findChildOfType(file, EmlNestedMessage.class);
        assertNull(nested);
    }

    public void testDeeplyNestedMultipartIsDepthCapped() {
        // F2 regression: each multipart nesting level used to recurse with no bound, risking a
        // StackOverflowError on a crafted message. The parser now caps nesting depth and treats
        // anything deeper as flat body text. With 400 nested levels the OLD parser structured all
        // 400 parts; the cap keeps the structured-part count far below the input depth. (We assert
        // the cap rather than a raw overflow because the test JVM's stack may absorb deep recursion.)
        var levels = 400;
        var file = parseFromText(buildNestedMultipart(levels));
        var parts = PsiTreeUtil.findChildrenOfType(file, EmlMimePart.class);
        assertFalse(parts.isEmpty());
        assertTrue("nesting must be capped well below the " + levels + " input levels", parts.size() < 200);
    }

    public void testDeeplyNestedMessageRfc822IsDepthCapped() {
        // Same cap on the message/rfc822 recursion path: the OLD parser nested all 400 messages.
        var levels = 400;
        var file = parseFromText(buildNestedRfc822(levels));
        var nested = PsiTreeUtil.findChildrenOfType(file, EmlNestedMessage.class);
        assertFalse(nested.isEmpty());
        assertTrue("nested-message depth must be capped well below " + levels, nested.size() < 200);
    }

    public void testDeeplyNestedMultipartSampleParses() throws IOException {
        var content = Files.readString(Path.of("src/test/resources/samples/eml/edge/deeply_nested_multipart.eml"));
        var file = parseFromText(content);
        // The sample nests past the parser's depth cap; it must parse without error and still yield
        // the structured MIME parts produced up to the cap.
        var parts = PsiTreeUtil.findChildrenOfType(file, EmlMimePart.class);
        assertFalse(parts.isEmpty());
    }

    private static String buildNestedMultipart(int levels) {
        var builder = new StringBuilder();
        for (var level = 0; level < levels; level++) {
            builder.append("Content-Type: multipart/mixed; boundary=\"b")
                    .append(level)
                    .append("\"\n\n--b")
                    .append(level)
                    .append('\n');
        }
        builder.append("Content-Type: text/plain\n\nleaf\n");
        for (var level = levels - 1; level >= 0; level--) {
            builder.append("--b").append(level).append("--\n");
        }
        return builder.toString();
    }

    private static String buildNestedRfc822(int levels) {
        var builder = new StringBuilder();
        for (var level = 0; level < levels; level++) {
            builder.append("Content-Type: message/rfc822\nSubject: level ")
                    .append(level)
                    .append("\n\n");
        }
        builder.append("Subject: leaf\n\nleaf body\n");
        return builder.toString();
    }
}
