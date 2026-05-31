package com.github.ttereshchenko.mailkit.folding;

import com.github.ttereshchenko.mailkit.psi.EmlHeader;
import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlFoldingBuilder extends FoldingBuilderEx implements DumbAware {

    private static final FoldingDescriptor[] EMPTY = new FoldingDescriptor[0];

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull PsiElement root, @NotNull Document doc, boolean quick) {
        if (quick) {
            return EMPTY;
        }
        var descriptors = new ArrayList<FoldingDescriptor>();

        var parts = PsiTreeUtil.findChildrenOfType(root, EmlMimePart.class);
        for (var part : parts) {
            var range = part.getContentRange();
            if (range != null && !range.isEmpty()) {
                descriptors.add(new FoldingDescriptor(part.getNode(), range));
            }
        }

        var headers = PsiTreeUtil.findChildrenOfType(root, EmlHeader.class);
        for (var header : headers) {
            var rawValue = header.getRawValue();
            if (rawValue != null && rawValue.contains("=?")) {
                var decodedValue = header.getDecodedValue();
                if (decodedValue != null && !decodedValue.equals(rawValue)) {
                    var range = header.getValueTextRange();
                    if (range != null && !range.isEmpty()) {
                        descriptors.add(new FoldingDescriptor(header.getNode(), range));
                    }
                }
            }
        }

        return descriptors.toArray(FoldingDescriptor[]::new);
    }

    @Override
    public @Nullable String getPlaceholderText(@NotNull ASTNode node) {
        var psi = node.getPsi();
        if (psi instanceof EmlHeader header) {
            var decoded = header.getDecodedValue();
            if (decoded != null) {
                return " " + decoded;
            }
        }
        return "...";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return node.getPsi() instanceof EmlHeader;
    }
}
