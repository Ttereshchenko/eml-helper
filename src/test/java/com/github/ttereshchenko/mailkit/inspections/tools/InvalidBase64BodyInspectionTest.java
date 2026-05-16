package com.github.ttereshchenko.mailkit.inspections.tools;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class InvalidBase64BodyInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(InvalidBase64BodyInspection.class);
    }

    private List<HighlightInfo> highlightsFor(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.WARNING);
    }

    public void testCleanBase64IsQuiet() {
        var content = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: application/octet-stream\nContent-Transfer-Encoding: base64\n\n"
                + "SGVsbG8=\n--b--\n";
        assertEmpty(highlightsFor(content));
    }

    public void testInvalidCharFlagged() {
        var content = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: application/octet-stream\nContent-Transfer-Encoding: base64\n\n"
                + "SGV*sbG8=\n--b--\n";
        var infos = highlightsFor(content);
        assertEquals(1, infos.size());
    }

    public void testNonBase64EncodingNotChecked() {
        var content = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: text/plain\nContent-Transfer-Encoding: 7bit\n\n"
                + "Hello *world*\n--b--\n";
        assertEmpty(highlightsFor(content));
    }

    public void testQuickFixStripsInvalidCharacters() {
        var content = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: application/octet-stream\nContent-Transfer-Encoding: base64\n\n"
                + "SGV<caret>*sbG8=\n--b--\n";
        myFixture.configureByText("test.eml", content);
        var intention = myFixture.findSingleIntention("Strip invalid base64 characters");
        myFixture.launchAction(intention);
        assertFalse(myFixture.getEditor().getDocument().getText().contains("*"));
    }
}
