package com.github.ttereshchenko.mailkit.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public final class EmlBodyText extends ASTWrapperPsiElement {

    public EmlBodyText(@NotNull ASTNode node) {
        super(node);
    }
}
