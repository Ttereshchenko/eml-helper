package com.github.ttereshchenko.mailkit.folding;

import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class EmlFoldingBuilderSampleTest extends BasePlatformTestCase {
    @Override
    protected String getTestDataPath() {
        return "src/test/resources/samples";
    }

    public void testRfc2047Subject() throws Exception {
        EmlFoldingBuilder foldingBuilder = new EmlFoldingBuilder();
        PsiFile file = myFixture.configureByFile("eml/edge/rfc2047_subject.eml");
        Document doc = myFixture.getEditor().getDocument();

        FoldingDescriptor[] descriptors = foldingBuilder.buildFoldRegions(file, doc, false);
        for (FoldingDescriptor d : descriptors) {
            System.out.println(
                    "Descriptor: " + d.getRange() + " -> " + foldingBuilder.getPlaceholderText(d.getElement()));
        }
    }
}
