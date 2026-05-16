package com.github.ttereshchenko.mailkit.inspections.tools;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class UnencodedNonAsciiHeaderInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(UnencodedNonAsciiHeaderInspection.class);
    }

    private List<HighlightInfo> highlightsFor(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.WARNING);
    }

    public void testAsciiSubjectIsQuiet() {
        assertEmpty(highlightsFor("Subject: Hello\n\nbody\n"));
    }

    public void testEncodedWordSubjectIsQuiet() {
        assertEmpty(highlightsFor("Subject: =?UTF-8?Q?Caf=C3=A9?=\n\nbody\n"));
    }

    public void testRawNonAsciiSubjectFlagged() {
        var infos = highlightsFor("Subject: Café\n\nbody\n");
        assertEquals(1, infos.size());
    }

    public void testXHeaderNotFlaggedEvenWithNonAscii() {
        // Only the listed structured headers are checked.
        assertEmpty(highlightsFor("X-Custom: Café\n\nbody\n"));
    }

    public void testQuickFixWrapsAsEncodedWord() {
        myFixture.configureByText("test.eml", "Subject: Caf<caret>é\n\nbody\n");
        var intention = myFixture.findSingleIntention("Wrap header value as RFC 2047 encoded-word");
        myFixture.launchAction(intention);
        var result = myFixture.getEditor().getDocument().getText();
        assertTrue(result.contains("=?UTF-8?B?"));
        assertFalse(result.contains("Café"));
    }
}
