package com.github.ttereshchenko.mailkit.highlighting;

import com.github.ttereshchenko.mailkit.psi.EmlHeader;
import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;

public final class EmlHeaderAnnotator implements Annotator, DumbAware {

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof EmlHeader header)) {
            return;
        }
        var settings = EmlHeaderSettings.getInstance();
        if (!settings.isHighlightingEnabled()) {
            return;
        }
        var name = header.getHeaderName();
        if (name == null || !settings.isHighlighted(name)) {
            return;
        }

        var attrKey = EmlHeaderTextAttributeKeys.getKey(name);
        if (settings.isNameOnly(name)) {
            var nameRange = header.getNameRange();
            if (nameRange == null) {
                return;
            }
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(nameRange)
                    .textAttributes(attrKey)
                    .create();
        } else {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(header.getTextRange())
                    .textAttributes(attrKey)
                    .create();
        }
    }
}
