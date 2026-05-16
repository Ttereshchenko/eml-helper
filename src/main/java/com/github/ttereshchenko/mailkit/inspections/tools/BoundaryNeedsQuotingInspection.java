package com.github.ttereshchenko.mailkit.inspections.tools;

import com.github.ttereshchenko.mailkit.inspections.HeaderRanges;
import com.github.ttereshchenko.mailkit.inspections.rules.BoundaryQuotingRule;
import com.github.ttereshchenko.mailkit.psi.EmlHeader;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderParsing;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;

public final class BoundaryNeedsQuotingInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull com.intellij.psi.PsiElement element) {
                if (!(element instanceof EmlHeader header)) {
                    return;
                }
                if (!EmlHeaderParsing.CONTENT_TYPE.equalsIgnoreCase(header.getHeaderName())) {
                    return;
                }
                var firstLine = HeaderRanges.firstLineNode(header);
                if (firstLine == null) {
                    return;
                }
                var lineText = firstLine.getText();
                var colon = lineText.indexOf(':');
                if (colon < 0) {
                    return;
                }
                var valuePart = lineText.substring(colon + 1);
                var hit = BoundaryQuotingRule.scan(valuePart);
                if (hit == null) {
                    return;
                }
                var headerStart = firstLine.getTextRange().getStartOffset();
                var absStart = headerStart + colon + 1 + hit.valueStart();
                var absEnd = headerStart + colon + 1 + hit.valueEnd();
                var rangeInHeader = new TextRange(
                        absStart - header.getTextRange().getStartOffset(),
                        absEnd - header.getTextRange().getStartOffset());
                holder.registerProblem(
                        header,
                        "Boundary value contains characters that require quoting (RFC 2045 §5.1)",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        rangeInHeader,
                        new QuoteBoundaryValueFix(absStart, absEnd));
            }
        };
    }

    static final class QuoteBoundaryValueFix implements LocalQuickFix {

        private final int absoluteStart;
        private final int absoluteEnd;

        QuoteBoundaryValueFix(int absoluteStart, int absoluteEnd) {
            this.absoluteStart = absoluteStart;
            this.absoluteEnd = absoluteEnd;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Quote boundary value";
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
            var current = document.getText(new TextRange(absoluteStart, absoluteEnd));
            var escaped = current.replace("\\", "\\\\").replace("\"", "\\\"");
            document.replaceString(absoluteStart, absoluteEnd, "\"" + escaped + "\"");
            documentManager.commitDocument(document);
        }
    }
}
