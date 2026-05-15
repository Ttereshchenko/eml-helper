package com.github.ttereshchenko.mailkit.psi;

import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Collection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlHeaderBlock extends ASTWrapperPsiElement {

    public EmlHeaderBlock(@NotNull ASTNode node) {
        super(node);
    }

    public @NotNull Collection<EmlHeader> getHeaders() {
        return PsiTreeUtil.getChildrenOfTypeAsList(this, EmlHeader.class);
    }

    public @Nullable EmlHeader findHeader(@NotNull String name) {
        for (var header : getHeaders()) {
            if (name.equalsIgnoreCase(header.getHeaderName())) {
                return header;
            }
        }
        return null;
    }
}
