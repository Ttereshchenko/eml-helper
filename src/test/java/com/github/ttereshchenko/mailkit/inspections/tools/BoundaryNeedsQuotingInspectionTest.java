package com.github.ttereshchenko.mailkit.inspections.tools;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class BoundaryNeedsQuotingInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(BoundaryNeedsQuotingInspection.class);
    }

    private List<HighlightInfo> highlightsFor(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.WARNING);
    }

    public void testQuotedBoundaryIsQuiet() {
        assertEmpty(highlightsFor("Content-Type: multipart/mixed; boundary=\"a b\"\n\nbody\n"));
    }

    public void testAlnumBoundaryIsQuiet() {
        assertEmpty(highlightsFor("Content-Type: multipart/mixed; boundary=abc123\n\nbody\n"));
    }

    public void testUnquotedBoundaryWithSpecialFlagged() {
        var infos = highlightsFor("Content-Type: multipart/mixed; boundary=a(b)c\n\nbody\n");
        assertEquals(1, infos.size());
    }

    public void testQuickFixWrapsInQuotes() {
        myFixture.configureByText("test.eml", "Content-Type: multipart/mixed; boundary=a(<caret>b)c\n\nbody\n");
        var intention = myFixture.findSingleIntention("Quote boundary value");
        myFixture.launchAction(intention);
        assertTrue(myFixture.getEditor().getDocument().getText().contains("boundary=\"a(b)c\""));
    }
}
