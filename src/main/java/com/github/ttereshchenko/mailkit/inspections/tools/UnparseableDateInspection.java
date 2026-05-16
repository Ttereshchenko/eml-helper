package com.github.ttereshchenko.mailkit.inspections.tools;

import com.github.ttereshchenko.mailkit.inspections.HeaderRanges;
import com.github.ttereshchenko.mailkit.inspections.rules.DateParseRule;
import com.github.ttereshchenko.mailkit.psi.EmlHeader;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;

public final class UnparseableDateInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof EmlHeader header)) {
                    return;
                }
                if (!"Date".equalsIgnoreCase(header.getHeaderName())) {
                    return;
                }
                var raw = header.getRawValue();
                if (raw == null || raw.isBlank()) {
                    return;
                }
                if (DateParseRule.tryParse(raw).isPresent()) {
                    return;
                }
                var valueRange = HeaderRanges.valueRangeOnFirstLine(header);
                if (valueRange == null) {
                    return;
                }
                var headerStart = header.getTextRange().getStartOffset();
                holder.registerProblem(
                        header,
                        "Date does not parse as RFC 2822",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        new TextRange(
                                valueRange.getStartOffset() - headerStart, valueRange.getEndOffset() - headerStart),
                        new ReplaceWithCurrentDateFix(valueRange));
            }
        };
    }

    static final class ReplaceWithCurrentDateFix implements LocalQuickFix {

        private final TextRange valueRange;

        ReplaceWithCurrentDateFix(TextRange valueRange) {
            this.valueRange = valueRange;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Replace with current date";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null) {
                return;
            }
            var file = element.getContainingFile();
            if (file == null) {
                return;
            }
            var documentManager = PsiDocumentManager.getInstance(project);
            var document = documentManager.getDocument(file);
            if (document == null) {
                return;
            }
            document.replaceString(valueRange.getStartOffset(), valueRange.getEndOffset(), DateParseRule.formatNow());
            documentManager.commitDocument(document);
        }
    }
}
