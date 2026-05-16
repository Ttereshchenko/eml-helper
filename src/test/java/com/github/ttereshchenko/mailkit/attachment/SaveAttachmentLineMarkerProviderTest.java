package com.github.ttereshchenko.mailkit.attachment;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.ArrayList;
import java.util.List;

public class SaveAttachmentLineMarkerProviderTest extends BasePlatformTestCase {

    private SaveAttachmentLineMarkerProvider provider;
    private boolean originalShowAttachmentActions;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        provider = new SaveAttachmentLineMarkerProvider();
        originalShowAttachmentActions = EmlHeaderSettings.getInstance().isShowAttachmentActions();
        EmlHeaderSettings.getInstance().setShowAttachmentActions(true);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            EmlHeaderSettings.getInstance().setShowAttachmentActions(originalShowAttachmentActions);
        } finally {
            super.tearDown();
        }
    }

    public void testOneMarkerOnAttachmentPartOnly() {
        var content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n"
                + "--sep\n"
                + "Content-Type: text/plain\n\n"
                + "Body text\n"
                + "--sep\n"
                + "Content-Type: application/pdf; name=\"file.pdf\"\n"
                + "Content-Disposition: attachment; filename=\"file.pdf\"\n"
                + "Content-Transfer-Encoding: base64\n\n"
                + "QUJD\n"
                + "--sep--\n";
        var file = myFixture.configureByText("test.eml", content);
        var markers = collectMarkers(file);
        assertEquals(1, markers.size());
    }

    public void testNoMarkersWhenSettingDisabled() {
        EmlHeaderSettings.getInstance().setShowAttachmentActions(false);
        var content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n"
                + "--sep\n"
                + "Content-Type: application/pdf; name=\"file.pdf\"\n"
                + "Content-Disposition: attachment; filename=\"file.pdf\"\n"
                + "Content-Transfer-Encoding: base64\n\n"
                + "QUJD\n"
                + "--sep--\n";
        var file = myFixture.configureByText("test.eml", content);
        var markers = collectMarkers(file);
        assertEquals(0, markers.size());
    }

    public void testNoMarkersWhenNoAttachments() {
        var content = "From: a@example.com\nContent-Type: text/plain\n\nplain body\n";
        var file = myFixture.configureByText("test.eml", content);
        var markers = collectMarkers(file);
        assertEquals(0, markers.size());
    }

    private List<PsiElement> collectMarkers(PsiFile file) {
        var leaves = new ArrayList<LeafPsiElement>();
        PsiTreeUtil.processElements(file, element -> {
            if (element instanceof LeafPsiElement leaf && leaf.getElementType() == EmlTokenTypes.BOUNDARY_START) {
                leaves.add(leaf);
            }
            return true;
        });
        var anchors = new ArrayList<PsiElement>();
        for (var leaf : leaves) {
            var info = provider.getLineMarkerInfo(leaf);
            if (info != null) {
                anchors.add(leaf);
            }
        }
        return anchors;
    }
}
