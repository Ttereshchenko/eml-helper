package com.github.ttereshchenko.mailkit.inspections.tools;

import com.github.ttereshchenko.mailkit.inspections.HeaderRanges;
import com.github.ttereshchenko.mailkit.inspections.rules.KnownEncodingRule;
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

public final class UnknownContentTransferEncodingInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof EmlHeader header)) {
                    return;
                }
                if (!"Content-Transfer-Encoding".equalsIgnoreCase(header.getHeaderName())) {
                    return;
                }
                var rawValue = header.getRawValue();
                if (rawValue == null || rawValue.isBlank()) {
                    return;
                }
                if (KnownEncodingRule.isKnown(rawValue)) {
                    return;
                }
                var valueRange = HeaderRanges.valueRangeOnFirstLine(header);
                if (valueRange == null) {
                    return;
                }
                var headerStart = header.getTextRange().getStartOffset();
                holder.registerProblem(
                        header,
                        "Unknown Content-Transfer-Encoding '" + rawValue.trim() + "' (RFC 2045 §6.1)",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        new TextRange(
                                valueRange.getStartOffset() - headerStart, valueRange.getEndOffset() - headerStart),
                        new ReplaceCteFix(valueRange));
            }
        };
    }

    static final class ReplaceCteFix implements LocalQuickFix {

        private final TextRange valueRange;

        ReplaceCteFix(TextRange valueRange) {
            this.valueRange = valueRange;
        }

        @Override
        public @NotNull String getName() {
            return "Replace with '" + KnownEncodingRule.DEFAULT_REPLACEMENT + "'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Replace Content-Transfer-Encoding with a known value";
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
            document.replaceString(
                    valueRange.getStartOffset(), valueRange.getEndOffset(), KnownEncodingRule.DEFAULT_REPLACEMENT);
            documentManager.commitDocument(document);
        }
    }
}
