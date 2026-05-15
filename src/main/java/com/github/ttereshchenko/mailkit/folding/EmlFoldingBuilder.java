package com.github.ttereshchenko.mailkit.folding;

import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlFoldingBuilder extends FoldingBuilderEx {

    private static final FoldingDescriptor[] EMPTY = new FoldingDescriptor[0];

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull PsiElement root, @NotNull Document doc, boolean quick) {
        if (quick) {
            return EMPTY;
        }
        var parts = PsiTreeUtil.findChildrenOfType(root, EmlMimePart.class);
        if (parts.isEmpty()) {
            return EMPTY;
        }
        var descriptors = new ArrayList<FoldingDescriptor>(parts.size());
        for (var part : parts) {
            var range = part.getContentRange();
            if (range == null || range.isEmpty()) {
                continue;
            }
            descriptors.add(new FoldingDescriptor(part.getNode(), range));
        }
        return descriptors.toArray(FoldingDescriptor[]::new);
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull ASTNode node) {
        return "...";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }
}
