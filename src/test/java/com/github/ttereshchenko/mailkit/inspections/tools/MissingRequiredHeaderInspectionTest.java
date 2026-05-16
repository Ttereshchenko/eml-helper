package com.github.ttereshchenko.mailkit.inspections.tools;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class MissingRequiredHeaderInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(MissingRequiredHeaderInspection.class);
    }

    private List<HighlightInfo> highlightsFor(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.WARNING);
    }

    public void testTriggersWhenFromAbsent() {
        var infos = highlightsFor("Subject: hi\nDate: Mon, 1 Jan 2024 10:00:00 +0000\n\nbody\n");
        assertEquals(1, infos.size());
        assertTrue(infos.getFirst().getDescription().contains("From"));
    }

    public void testTriggersWhenDateAbsent() {
        var infos = highlightsFor("From: a@b.com\n\nbody\n");
        assertEquals(1, infos.size());
        assertTrue(infos.getFirst().getDescription().contains("Date"));
    }

    public void testQuietWhenBothPresent() {
        assertEmpty(highlightsFor("From: a@b.com\nDate: Mon, 1 Jan 2024 10:00:00 +0000\n\nbody\n"));
    }

    public void testQuickFixInsertsFromHeader() {
        myFixture.configureByText("test.eml", "Subject: hi\nDate: Mon, 1 Jan 2024 10:00:00 +0000\n\nbody\n");
        var intention = myFixture.findSingleIntention("Insert 'From' header");
        myFixture.launchAction(intention);
        var result = myFixture.getEditor().getDocument().getText();
        assertTrue(result.startsWith("From: \n"));
    }
}
