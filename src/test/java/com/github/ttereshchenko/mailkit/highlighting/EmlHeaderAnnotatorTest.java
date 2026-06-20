package com.github.ttereshchenko.mailkit.highlighting;

import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class EmlHeaderAnnotatorTest extends BasePlatformTestCase {

    private boolean originalEnabled;
    private List<String> originalHighlighted;
    private List<String> originalNameOnly;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        var settings = EmlHeaderSettings.getInstance();
        originalEnabled = settings.isHighlightingEnabled();
        originalHighlighted = List.copyOf(settings.getHighlightedHeaders());
        originalNameOnly = List.copyOf(settings.getNameOnlyHeaders());
        settings.setHighlightingEnabled(true);
        settings.setHighlightedHeaders(List.of("From", "To", "Subject", "X-Custom"));
        settings.setNameOnlyHeaders(List.of("From", "To", "Subject"));
    }

    @Override
    protected void tearDown() throws Exception {
        var settings = EmlHeaderSettings.getInstance();
        settings.setHighlightingEnabled(originalEnabled);
        settings.setHighlightedHeaders(originalHighlighted);
        settings.setNameOnlyHeaders(originalNameOnly);
        super.tearDown();
    }

    private List<HighlightInfo> annotateText(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.INFORMATION);
    }

    public void testHighlightingDisabledProducesNoAnnotations() {
        EmlHeaderSettings.getInstance().setHighlightingEnabled(false);
        var infos = annotateText("From: a@b.com\nTo: c@d.com\n\nBody\n");
        assertEmpty(infos);
    }

    public void testNameOnlyHighlightsOnlyHeaderNamePortion() {
        var content = "From: sender@example.com\n\nBody\n";
        var infos = annotateText(content);
        var ranges = infos.stream().map(info -> content.substring(info.getStartOffset(), info.getEndOffset()));
        assertContainsElements(ranges.toList(), "From:");
    }

    public void testFullLineHighlightingForNonNameOnlyHeader() {
        var content = "X-Custom: some-value\n\nBody\n";
        var infos = annotateText(content);
        assertEquals(1, infos.size());
        var info = infos.getFirst();
        // Full line including trailing newline is annotated.
        assertEquals("X-Custom: some-value\n", content.substring(info.getStartOffset(), info.getEndOffset()));
    }

    public void testContinuationLineHighlightedInFullLineMode() {
        // "X-Custom" is full-line; the whole header including continuation is annotated as one range.
        var content = "X-Custom: first,\n second\n\nBody\n";
        var infos = annotateText(content);
        assertEquals(1, infos.size());
        var info = infos.getFirst();
        assertEquals("X-Custom: first,\n second\n", content.substring(info.getStartOffset(), info.getEndOffset()));
    }

    public void testContinuationLineSuppressedInNameOnlyMode() {
        // "To" is name-only — continuation has no name to highlight, must be skipped.
        var content = "To: a@b.com,\n c@d.com\n\nBody\n";
        var infos = annotateText(content);
        assertEquals(1, infos.size());
        assertEquals(
                "To:",
                content.substring(
                        infos.getFirst().getStartOffset(), infos.getFirst().getEndOffset()));
    }

    public void testUnknownHeaderProducesNoAnnotation() {
        var content = "X-Unknown: ignored\n\nBody\n";
        var infos = annotateText(content);
        assertEmpty(infos);
    }

    public void testHeaderWithoutColonProducesNoAnnotation() {
        var content = "NoColonHere\n\nBody\n";
        var infos = annotateText(content);
        assertEmpty(infos);
    }

    public void testBodyLinesAreNotAnnotated() {
        var content = "From: a@b.com\n\nFrom: this is body, not a header\n";
        var infos = annotateText(content);
        assertEquals(1, infos.size());
    }

    public void testNestedEmlHeadersAreAnnotated() {
        // Regression test for #27: headers of a nested message/rfc822 attachment must be
        // highlighted just like the outer headers.
        var content = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: message/rfc822\n\n"
                + "From: nested@example.com\nSubject: nested\n\nbody\n--b--\n";
        var infos = annotateText(content);
        // Highlight list (configured in setUp) covers From and Subject — both name-only.
        assertEquals(2, infos.size());
    }

    public void testPerPartHeadersAreAnnotated() {
        // Per-part MIME headers (the block right after a --boundary marker) must be annotated.
        var content =
                "Content-Type: multipart/mixed; boundary=\"b\"\n\n" + "--b\nX-Custom: per-part-value\n\nhello\n--b--\n";
        var infos = annotateText(content);
        // X-Custom is in the highlight list (full-line, not name-only).
        assertEquals(1, infos.size());
    }

    public void testLeadingBlankLineStillHighlightsHeaders() {
        // Repro (image 5): a blank line typed before the first header of a multipart message must not
        // switch off header coloring for the whole file. Subject and From must still be highlighted.
        var content = "\nSubject: hi\nContent-Type: multipart/mixed; boundary=\"b\"\n"
                + "From: a@b.com\n\n--b\nbody\n--b--\n";
        var infos = annotateText(content);
        assertEquals(2, infos.size());
    }

    public void testStrayBlankBetweenHeadersStillHighlightsFollowingHeaders() {
        // Repro (image 3): a stray blank line between the headers of a multipart message must not drop
        // the headers after it. Subject, From and To must all be highlighted.
        var content = "Subject: hi\nContent-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "From: a@b.com\nTo: c@d.com\n\n--b\nbody\n--b--\n";
        var infos = annotateText(content);
        assertEquals(3, infos.size());
    }

    public void testMultipleStrayBlankLinesBetweenHeadersStillHighlight() {
        // Consecutive stray blanks in a multipart header block are tolerated together; the header after
        // them stays highlighted.
        var content = "Subject: hi\nContent-Type: multipart/mixed; boundary=\"b\"\n\n\n\n"
                + "To: c@d.com\n\n--b\nbody\n--b--\n";
        var infos = annotateText(content);
        assertEquals(2, infos.size());
    }
}
