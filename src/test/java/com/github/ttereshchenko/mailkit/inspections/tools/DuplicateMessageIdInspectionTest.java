package com.github.ttereshchenko.mailkit.inspections.tools;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class DuplicateMessageIdInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(DuplicateMessageIdInspection.class);
    }

    private List<HighlightInfo> highlightsFor(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.WARNING);
    }

    public void testSingleMessageIdIsQuiet() {
        assertEmpty(highlightsFor("Message-ID: <a@b>\nFrom: x@y\n\nbody\n"));
    }

    public void testDuplicateMessageIdFlagged() {
        var content = "Message-ID: <a@b>\nMessage-ID: <c@d>\nFrom: x@y\n\nbody\n";
        assertEquals(1, highlightsFor(content).size());
    }

    public void testThreeMessageIdsReportTwoDuplicates() {
        var content = "Message-ID: <a@b>\nMessage-ID: <c@d>\nMessage-ID: <e@f>\nFrom: x@y\n\nbody\n";
        assertEquals(2, highlightsFor(content).size());
    }

    public void testQuickFixRemovesOneHeader() {
        var content = "Message-ID: <a@b>\nMes<caret>sage-ID: <c@d>\nFrom: x@y\n\nbody\n";
        myFixture.configureByText("test.eml", content);
        var intention = myFixture.findSingleIntention("Remove duplicate header");
        myFixture.launchAction(intention);
        var result = myFixture.getEditor().getDocument().getText();
        assertTrue(result.contains("Message-ID: <a@b>"));
        assertFalse(result.contains("Message-ID: <c@d>"));
    }
}
