package com.github.ttereshchenko.mailkit.psi;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlHeader extends ASTWrapperPsiElement {

    public EmlHeader(@NotNull ASTNode node) {
        super(node);
    }

    public @Nullable String getHeaderName() {
        var firstLine = firstLineText();
        return firstLine == null ? null : EmlHeaderParsing.headerName(firstLine);
    }

    public @Nullable String getRawValue() {
        var firstLine = firstLineText();
        if (firstLine == null) {
            return null;
        }
        return EmlHeaderParsing.joinValue(firstLine, continuationTexts());
    }

    public @Nullable String getDecodedValue() {
        var raw = getRawValue();
        return raw == null ? null : EmlEncodedWord.decode(raw);
    }

    public @Nullable TextRange getNameRange() {
        var firstLineNode = firstLineNode();
        if (firstLineNode == null) {
            return null;
        }
        var firstLine = firstLineNode.getText();
        var colon = firstLine.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        var start = firstLineNode.getTextRange().getStartOffset();
        return new TextRange(start, start + colon + 1);
    }

    public @Nullable TextRange getValueTextRange() {
        var nameRange = getNameRange();
        if (nameRange == null) {
            return null;
        }
        var start = nameRange.getEndOffset();
        var end = getTextRange().getEndOffset();

        var text = getText();
        var textStart = getTextRange().getStartOffset();
        while (end > start && Character.isWhitespace(text.charAt(end - textStart - 1))) {
            end--;
        }

        if (start >= end) {
            return null;
        }
        return new TextRange(start, end);
    }

    private @Nullable ASTNode firstLineNode() {
        for (var child : getNode().getChildren(null)) {
            if (child.getElementType() == EmlTokenTypes.HEADER_LINE) {
                return child;
            }
        }
        return null;
    }

    private @Nullable String firstLineText() {
        var node = firstLineNode();
        return node == null ? null : node.getText().stripTrailing();
    }

    private List<String> continuationTexts() {
        List<String> result = null;
        for (var child : getNode().getChildren(null)) {
            if (child.getElementType() == EmlTokenTypes.HEADER_CONT_LINE) {
                if (result == null) {
                    result = new ArrayList<>(2);
                }
                result.add(child.getText().stripTrailing());
            }
        }
        return result == null ? Collections.emptyList() : result;
    }
}
