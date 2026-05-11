package com.github.ttereshchenko.emlhelper.highlighting;

import com.github.ttereshchenko.emlhelper.EmlTokenTypes;
import com.github.ttereshchenko.emlhelper.settings.EmlHeaderSettings;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class EmlHeaderAnnotator implements Annotator {
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!EmlHeaderSettings.getInstance().isHighlightingEnabled()) {
            return;
        }
        if (element.getNode().getElementType() != EmlTokenTypes.HEADER_LINE) {
            return;
        }

        var text = element.getText();
        var isContinuation = !text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(0) == '\t');

        var headerName = extractHeaderName(element);
        if (headerName == null) {
            return;
        }

        var settings = EmlHeaderSettings.getInstance();
        if (!settings.isHighlighted(headerName)) {
            return;
        }

        if (settings.isNameOnly(headerName)) {
            // In name-only mode, skip continuation lines (they have no header name)
            if (isContinuation) {
                return;
            }
            var colonIdx = text.indexOf(':');
            if (colonIdx <= 0) {
                return;
            }
            var start = element.getTextRange().getStartOffset();
            var nameRange = new TextRange(start, start + colonIdx + 1);
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(nameRange)
                    .textAttributes(EmlHeaderTextAttributeKeys.getKey(headerName))
                    .create();
        } else {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(element)
                    .textAttributes(EmlHeaderTextAttributeKeys.getKey(headerName))
                    .create();
        }
    }

    private static String extractHeaderName(PsiElement element) {
        var text = element.getText();

        // Check if this is a continuation line (starts with whitespace per RFC 822 §3.1.1)
        if (!text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(0) == '\t')) {
            // Walk backward through siblings to find the originating header
            var prev = element.getPrevSibling();
            while (prev != null) {
                if (prev.getNode().getElementType() == EmlTokenTypes.HEADER_LINE) {
                    String prevText = prev.getText();
                    if (!prevText.isEmpty() && prevText.charAt(0) != ' ' && prevText.charAt(0) != '\t') {
                        return extractNameFromColon(prevText);
                    }
                } else {
                    break;
                }
                prev = prev.getPrevSibling();
            }
            return null;
        }

        return extractNameFromColon(text);
    }

    static String extractNameFromColon(String text) {
        var colonIdx = text.indexOf(':');
        if (colonIdx <= 0) {
            return null;
        }
        var name = text.substring(0, colonIdx).trim();
        if (name.isEmpty()) {
            return null;
        }
        return name;
    }
}
