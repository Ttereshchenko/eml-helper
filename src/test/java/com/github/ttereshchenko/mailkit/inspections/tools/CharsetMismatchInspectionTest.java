package com.github.ttereshchenko.mailkit.inspections.tools;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

public class CharsetMismatchInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(CharsetMismatchInspection.class);
    }

    private List<HighlightInfo> highlightsFor(String content) {
        myFixture.configureByText("test.eml", content);
        return myFixture.doHighlighting(HighlightSeverity.WARNING);
    }

    public void testUtf8DeclarationWithCleanBodyIsQuiet() {
        var content = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: text/plain; charset=UTF-8\n\nCafé\n--b--\n";
        assertEmpty(highlightsFor(content));
    }

    public void testAsciiDeclarationWithMultibyteFlagged() {
        var content = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: text/plain; charset=us-ascii\n\nCafé\n--b--\n";
        assertEquals(1, highlightsFor(content).size());
    }

    public void testBase64BodyNotCheckedAgainstDeclaredCharset() {
        // Charset describes the *decoded* bytes; for base64-encoded parts the inspection skips.
        var content = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
                + "--b\nContent-Type: text/plain; charset=us-ascii\nContent-Transfer-Encoding: base64\n\n"
                + "SGVsbG8=\n--b--\n";
        assertEmpty(highlightsFor(content));
    }
}
