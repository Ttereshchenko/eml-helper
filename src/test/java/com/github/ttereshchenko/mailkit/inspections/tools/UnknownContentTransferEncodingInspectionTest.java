package com.github.ttereshchenko.mailkit.inspections.tools;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class UnknownContentTransferEncodingInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(UnknownContentTransferEncodingInspection.class);
    }

    private List<HighlightInfo> highlightsFor(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.WARNING);
    }

    public void testKnownEncodingIsQuiet() {
        assertEmpty(highlightsFor("Content-Transfer-Encoding: base64\n\nbody\n"));
    }

    public void testCaseInsensitiveAcceptance() {
        assertEmpty(highlightsFor("Content-Transfer-Encoding: BASE64\n\nbody\n"));
    }

    public void testUnknownEncodingFlagged() {
        assertEquals(
                1, highlightsFor("Content-Transfer-Encoding: rot13\n\nbody\n").size());
    }

    public void testQuickFixReplacesWith8Bit() {
        myFixture.configureByText("test.eml", "Content-Transfer-Encoding: rot<caret>13\n\nbody\n");
        var intention = myFixture.findSingleIntention("Replace with '8bit'");
        myFixture.launchAction(intention);
        assertTrue(myFixture.getEditor().getDocument().getText().contains("Content-Transfer-Encoding: 8bit"));
    }
}
