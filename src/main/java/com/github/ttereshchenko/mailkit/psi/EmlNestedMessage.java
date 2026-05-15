package com.github.ttereshchenko.mailkit.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlNestedMessage extends ASTWrapperPsiElement {

    public EmlNestedMessage(@NotNull ASTNode node) {
        super(node);
    }

    public @Nullable EmlHeaderBlock getHeaderBlock() {
        return PsiTreeUtil.getChildOfType(this, EmlHeaderBlock.class);
    }
}
