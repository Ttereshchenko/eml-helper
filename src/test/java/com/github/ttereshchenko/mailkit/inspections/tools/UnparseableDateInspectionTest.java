package com.github.ttereshchenko.mailkit.inspections.tools;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class UnparseableDateInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(UnparseableDateInspection.class);
    }

    private List<HighlightInfo> highlightsFor(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.WARNING);
    }

    public void testValidDateIsQuiet() {
        assertEmpty(highlightsFor("Date: Mon, 1 Jan 2024 10:00:00 +0000\n\nbody\n"));
    }

    public void testGarbageDateFlagged() {
        assertEquals(1, highlightsFor("Date: yesterday afternoon\n\nbody\n").size());
    }

    public void testQuickFixInsertsRfc1123Date() {
        myFixture.configureByText("test.eml", "Date: yester<caret>day afternoon\n\nbody\n");
        var intention = myFixture.findSingleIntention("Replace with current date");
        myFixture.launchAction(intention);
        var result = myFixture.getEditor().getDocument().getText();
        assertFalse(result.contains("yesterday"));
    }
}
