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
        // "X-Custom" is full-line; continuation should also be annotated as the same header.
        var content = "X-Custom: first,\n second\n\nBody\n";
        var infos = annotateText(content);
        assertEquals(2, infos.size());
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
}
