package com.github.ttereshchenko.mailkit.inspections.tools;

import com.github.ttereshchenko.mailkit.psi.EmlHeader;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderBlock;
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

public final class DuplicateMessageIdInspection extends LocalInspectionTool {

    private static final String TARGET = "Message-ID";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof EmlHeaderBlock block)) {
                    return;
                }
                var seen = false;
                for (var header : block.getHeaders()) {
                    if (!TARGET.equalsIgnoreCase(header.getHeaderName())) {
                        continue;
                    }
                    if (!seen) {
                        seen = true;
                        continue;
                    }
                    var nameRange = header.getNameRange();
                    var rangeInHeader = nameRange == null
                            ? new TextRange(0, Math.min(1, header.getTextLength()))
                            : new TextRange(
                                    nameRange.getStartOffset()
                                            - header.getTextRange().getStartOffset(),
                                    nameRange.getEndOffset()
                                            - header.getTextRange().getStartOffset());
                    holder.registerProblem(
                            header,
                            "Duplicate Message-ID header (RFC 5322 §3.6.4)",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            rangeInHeader,
                            new RemoveHeaderFix());
                }
            }
        };
    }

    static final class RemoveHeaderFix implements LocalQuickFix {

        @Override
        public @NotNull String getFamilyName() {
            return "Remove duplicate header";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (!(element instanceof EmlHeader header)) {
                return;
            }
            var file = header.getContainingFile();
            if (file == null) {
                return;
            }
            var documentManager = PsiDocumentManager.getInstance(project);
            var document = documentManager.getDocument(file);
            if (document == null) {
                return;
            }
            var range = header.getTextRange();
            var start = range.getStartOffset();
            var end = range.getEndOffset();
            if (end < document.getTextLength()
                    && document.getText(new TextRange(end, end + 1)).equals("\n")) {
                end++;
            }
            document.deleteString(start, end);
            documentManager.commitDocument(document);
        }
    }
}
