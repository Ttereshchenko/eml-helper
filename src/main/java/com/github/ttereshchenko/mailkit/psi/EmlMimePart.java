package com.github.ttereshchenko.mailkit.psi;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlMimePart extends ASTWrapperPsiElement {

    public EmlMimePart(@NotNull ASTNode node) {
        super(node);
    }

    public @Nullable EmlHeaderBlock getHeaderBlock() {
        return PsiTreeUtil.getChildOfType(this, EmlHeaderBlock.class);
    }

    public @Nullable String getBoundaryName() {
        var marker = startMarkerNode();
        if (marker == null) {
            return null;
        }
        var text = marker.getText().stripTrailing();
        return text.startsWith("--") ? text.substring(2) : null;
    }

    public @Nullable TextRange getContentRange() {
        var marker = startMarkerNode();
        if (marker == null) {
            return null;
        }
        var contentStart = marker.getTextRange().getEndOffset();
        var contentEnd = getTextRange().getEndOffset();
        if (contentEnd <= contentStart) {
            return null;
        }
        return new TextRange(contentStart, contentEnd);
    }

    private @Nullable ASTNode startMarkerNode() {
        for (var child : getNode().getChildren(null)) {
            if (child.getElementType() == EmlTokenTypes.BOUNDARY_START) {
                return child;
            }
        }
        return null;
    }
}
