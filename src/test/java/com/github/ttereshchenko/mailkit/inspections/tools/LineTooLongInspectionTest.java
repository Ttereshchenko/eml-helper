package com.github.ttereshchenko.mailkit.inspections.tools;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class LineTooLongInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(LineTooLongInspection.class);
    }

    private List<HighlightInfo> highlightsFor(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.WARNING);
    }

    public void testShortLinesNotFlagged() {
        assertEmpty(highlightsFor("From: a@b.com\nSubject: hi\n\nbody\n"));
    }

    public void testLineOverLimitFlagged() {
        var longLine = "X-Header: " + "a".repeat(1000);
        var infos = highlightsFor("From: a@b.com\n" + longLine + "\n\nbody\n");
        assertEquals(1, infos.size());
    }

    public void testQuickFixFoldsHeader() {
        var value = "abc def ghi jkl mno pqr stu vwx yz1 234 567 890 ".repeat(25);
        myFixture.configureByText("test.eml", "From: a@b.com\nX-Header: <caret>" + value + "\n\nbody\n");
        var intention = myFixture.findSingleIntention("Fold header at column 78");
        myFixture.launchAction(intention);
        var result = myFixture.getEditor().getDocument().getText();
        // After folding, there must be at least one continuation line starting with a space.
        assertTrue(result.contains("\n "));
    }
}
