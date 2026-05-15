package com.github.ttereshchenko.mailkit.psi;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;

public final class EmlPsiFactory {

    private EmlPsiFactory() {}

    public static PsiElement createElement(ASTNode node) {
        IElementType type = node.getElementType();
        if (type == EmlElementTypes.HEADER) {
            return new EmlHeader(node);
        }
        if (type == EmlElementTypes.HEADER_BLOCK) {
            return new EmlHeaderBlock(node);
        }
        if (type == EmlElementTypes.MIME_PART) {
            return new EmlMimePart(node);
        }
        if (type == EmlElementTypes.NESTED_MESSAGE) {
            return new EmlNestedMessage(node);
        }
        if (type == EmlElementTypes.BODY_TEXT) {
            return new EmlBodyText(node);
        }
        throw new IllegalStateException("Unexpected element type: " + type);
    }
}
