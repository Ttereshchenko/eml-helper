package com.github.ttereshchenko.emlhelper.folding;

import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class EmlFoldingBuilderTest extends BasePlatformTestCase {

    private EmlFoldingBuilder foldingBuilder;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        foldingBuilder = new EmlFoldingBuilder();
    }

    // ===== Positive Tests =====

    public void testSingleBoundaryPart() {
        String content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n" +
                "--sep\nPart 1 content\n--sep--\n";
        PsiFile file = myFixture.configureByText("test.eml", content);
        Document doc = myFixture.getEditor().getDocument();

        FoldingDescriptor[] descriptors = foldingBuilder.buildFoldRegions(file, doc, false);
        assertEquals(1, descriptors.length);
    }

    public void testMultipleBoundaryParts() {
        String content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n" +
                "--sep\nPart 1\n--sep\nPart 2\n--sep--\n";
        PsiFile file = myFixture.configureByText("test.eml", content);
        Document doc = myFixture.getEditor().getDocument();

        FoldingDescriptor[] descriptors = foldingBuilder.buildFoldRegions(file, doc, false);
        assertEquals(2, descriptors.length);
    }

    public void testNestedBoundaries() {
        String content = "Content-Type: multipart/mixed; boundary=\"outer\"\n\n" +
                "--outer\nContent-Type: multipart/alternative; boundary=\"inner\"\n\n" +
                "--inner\nText part\n--inner--\n--outer--\n";
        PsiFile file = myFixture.configureByText("test.eml", content);
        Document doc = myFixture.getEditor().getDocument();

        FoldingDescriptor[] descriptors = foldingBuilder.buildFoldRegions(file, doc, false);
        // Should have fold regions for both outer and inner boundaries
        assertTrue(descriptors.length >= 2);
    }

    public void testFoldRegionOffsets() {
        String content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n" +
                "--sep\nPart content here\n--sep--\n";
        PsiFile file = myFixture.configureByText("test.eml", content);
        Document doc = myFixture.getEditor().getDocument();

        FoldingDescriptor[] descriptors = foldingBuilder.buildFoldRegions(file, doc, false);
        assertEquals(1, descriptors.length);

        // Fold should start after "--sep\n" and end before "--sep--\n"
        int sepLineEnd = content.indexOf("--sep\n") + "--sep\n".length();
        int endMarkerStart = content.indexOf("--sep--\n");
        assertEquals(sepLineEnd, descriptors[0].getRange().getStartOffset());
        assertEquals(endMarkerStart, descriptors[0].getRange().getEndOffset());
    }

    public void testPlaceholderText() {
        String content = "From: test\n\nBody\n";
        PsiFile file = myFixture.configureByText("test.eml", content);
        assertEquals("...", foldingBuilder.getPlaceholderText(file.getNode()));
    }

    public void testIsCollapsedByDefault() {
        String content = "From: test\n\nBody\n";
        PsiFile file = myFixture.configureByText("test.eml", content);
        assertFalse(foldingBuilder.isCollapsedByDefault(file.getNode()));
    }

    // ===== Negative Tests =====

    public void testQuickModeReturnsEmpty() {
        String content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n" +
                "--sep\nPart\n--sep--\n";
        PsiFile file = myFixture.configureByText("test.eml", content);
        Document doc = myFixture.getEditor().getDocument();

        FoldingDescriptor[] descriptors = foldingBuilder.buildFoldRegions(file, doc, true);
        assertEquals(0, descriptors.length);
    }

    public void testNoBoundary() {
        String content = "From: test@example.com\nSubject: Hello\n\nBody text here\n";
        PsiFile file = myFixture.configureByText("test.eml", content);
        Document doc = myFixture.getEditor().getDocument();

        FoldingDescriptor[] descriptors = foldingBuilder.buildFoldRegions(file, doc, false);
        assertEquals(0, descriptors.length);
    }

    public void testEmptyContentBetweenMarkers() {
        String content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n" +
                "--sep\n--sep--\n";
        PsiFile file = myFixture.configureByText("test.eml", content);
        Document doc = myFixture.getEditor().getDocument();

        FoldingDescriptor[] descriptors = foldingBuilder.buildFoldRegions(file, doc, false);
        // No content between markers, so no fold region should be created
        assertEquals(0, descriptors.length);
    }
}
