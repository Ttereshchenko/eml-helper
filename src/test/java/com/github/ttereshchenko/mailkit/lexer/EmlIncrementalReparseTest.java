package com.github.ttereshchenko.mailkit.lexer;

import com.github.ttereshchenko.mailkit.EmlLanguage;
import com.github.ttereshchenko.mailkit.psi.EmlBodyContentType;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.impl.DebugUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Guards the reparseable collapsed body ({@code EmlBodyContentType}): after a document edit + commit,
 * the (possibly incrementally) reparsed PSI must be identical to a from-scratch parse of the same
 * final text. Any divergence between the isolated-reparse path and a full parse — the PSI-corruption
 * risk of making a node reparseable — fails here; the platform's own consistency assertions also fire
 * during commit.
 */
public class EmlIncrementalReparseTest extends BasePlatformTestCase {

    private static final String MULTIPART_WITH_BASE64 = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
            + "--b\nContent-Type: application/octet-stream\nContent-Transfer-Encoding: base64\n\n"
            + "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=\n"
            + "--b--\n";

    private static final String MULTIPART_WITH_TEXT = "Content-Type: multipart/mixed; boundary=\"b\"\n\n"
            + "--b\nContent-Type: text/plain\n\n"
            + "first line\nsecond line\n"
            + "--b--\n";

    public void testEditInsideBase64BodyMatchesFreshParse() {
        // The common in-body edit keeps the body newline-terminated with no boundary-shaped line, so
        // isParsable permits the cheap isolated reparse; it must still match a full parse.
        assertReparseMatchesFreshParse(
                MULTIPART_WITH_BASE64,
                document -> document.insertString(document.getText().indexOf("QUFB") + 4, "ZZ"));
    }

    public void testEditHeaderOutsideBodyMatchesFreshParse() {
        // A header edit lands outside every reparseable body, so the file is fully reparsed; the
        // collapsed base64 body must survive intact.
        assertReparseMatchesFreshParse(MULTIPART_WITH_BASE64, document -> document.insertString(0, "X-Note: hi\n"));
    }

    public void testTypingBoundaryLineIntoBodyReclassifies() {
        // Inserting a line that matches the declared boundary turns body text into a structural
        // boundary. isParsable must refuse the isolated reparse (it sees "\n--b"), so the full reparse
        // reclassifies globally — and the PSI must match a from-scratch parse of the new text.
        assertReparseMatchesFreshParse(
                MULTIPART_WITH_TEXT,
                document -> document.insertString(document.getText().indexOf("second line"), "--b\n"));
    }

    public void testDeletingBodyTrailingNewlineMatchesFreshParse() {
        // Removing the newline that terminates the body would merge the last body line with the
        // following "--b--" boundary on an isolated reparse. isParsable refuses (text no longer ends
        // with '\n'); the full reparse handles the merge correctly.
        assertReparseMatchesFreshParse(MULTIPART_WITH_TEXT, document -> {
            var index = document.getText().indexOf("second line") + "second line".length();
            document.deleteString(index, index + 1);
        });
    }

    public void testIsParsableGatesIsolatedReparse() {
        // Proves the common in-body edit actually qualifies for the cheap isolated reparse, and that
        // the structure-changing cases above are forced down the full-reparse path.
        var type = new EmlBodyContentType();
        var language = EmlLanguage.INSTANCE;
        var project = getProject();
        assertTrue(
                "plain newline-terminated body is reparsable",
                type.isParsable(null, "hello\nworld\n", language, project));
        assertFalse(
                "body not ending in newline is not reparsable",
                type.isParsable(null, "hello\nworld", language, project));
        assertFalse(
                "leading boundary-shaped line is not reparsable", type.isParsable(null, "--b\n", language, project));
        assertFalse(
                "interior boundary-shaped line is not reparsable",
                type.isParsable(null, "hello\n--b\nworld\n", language, project));
        assertFalse("empty body is not reparsable", type.isParsable(null, "", language, project));
    }

    private void assertReparseMatchesFreshParse(String content, Edit edit) {
        var file = myFixture.configureByText("test.eml", content);
        var document = myFixture.getEditor().getDocument();
        WriteCommandAction.runWriteCommandAction(getProject(), () -> edit.apply(document));
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        var reparsed = DebugUtil.psiToString(file, true, false);
        var fresh = PsiFileFactory.getInstance(getProject())
                .createFileFromText("fresh.eml", EmlLanguage.INSTANCE, document.getText());
        assertEquals(
                "Incremental reparse must match a from-scratch parse of the same text",
                DebugUtil.psiToString(fresh, true, false),
                reparsed);
    }

    @FunctionalInterface
    private interface Edit {
        void apply(Document document);
    }
}
