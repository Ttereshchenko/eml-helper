package com.github.ttereshchenko.mailkit.inspections.tools;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class UnterminatedBoundaryInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(UnterminatedBoundaryInspection.class);
    }

    private List<HighlightInfo> highlightsFor(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.WARNING);
    }

    public void testProperlyClosedMultipartIsQuiet() {
        var content = "Content-Type: multipart/mixed; boundary=\"abc\"\n\n--abc\n\nfirst\n--abc--\n";
        assertEmpty(highlightsFor(content));
    }

    public void testUnterminatedBoundaryFlagged() {
        var content = "Content-Type: multipart/mixed; boundary=\"abc\"\n\n--abc\n\nfirst\n";
        var infos = highlightsFor(content);
        assertEquals(1, infos.size());
        assertTrue(infos.getFirst().getDescription().contains("abc"));
    }

    public void testQuickFixAppendsClosingBoundary() {
        myFixture.configureByText(
                "test.eml", "Content-Type: multipart/mixed; boundary=\"abc\"\n\n--<caret>abc\n\nfirst\n");
        var intention = myFixture.findSingleIntention("Insert closing MIME boundary");
        myFixture.launchAction(intention);
        assertTrue(myFixture.getEditor().getDocument().getText().contains("--abc--"));
    }
}
