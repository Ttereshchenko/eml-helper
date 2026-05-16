package com.github.ttereshchenko.mailkit.inspections.tools;

import com.github.ttereshchenko.mailkit.inspections.rules.LineLengthRule;
import com.github.ttereshchenko.mailkit.psi.EmlHeader;
import com.github.ttereshchenko.mailkit.psi.EmlPsiFile;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

public final class LineTooLongInspection extends LocalInspectionTool {

    private static final int FOLD_COLUMN = 78;

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitFile(@NotNull PsiFile file) {
                if (!(file instanceof EmlPsiFile)) {
                    return;
                }
                var text = file.getText();
                var fileStart = file.getTextRange().getStartOffset();
                for (var range : LineLengthRule.findLongLines(text)) {
                    var absoluteStart = fileStart + range.startOffset();
                    var absoluteEnd = fileStart + range.endOffset();
                    var header = PsiTreeUtil.findElementOfClassAtOffset(file, absoluteStart, EmlHeader.class, false);
                    LocalQuickFix[] fixes = header != null
                            ? new LocalQuickFix[] {new WrapFoldedHeaderFix()}
                            : LocalQuickFix.EMPTY_ARRAY;
                    holder.registerProblem(
                            file,
                            "Line exceeds 998 octets (RFC 5322 §2.1.1)",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            new TextRange(absoluteStart, absoluteEnd),
                            fixes);
                }
            }
        };
    }

    static final class WrapFoldedHeaderFix implements LocalQuickFix {

        @Override
        public @NotNull String getFamilyName() {
            return "Fold header at column " + FOLD_COLUMN;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var range = descriptor.getTextRangeInElement();
            var file = descriptor.getPsiElement().getContainingFile();
            if (file == null) {
                return;
            }
            var documentManager = PsiDocumentManager.getInstance(project);
            var document = documentManager.getDocument(file);
            if (document == null) {
                return;
            }
            var absoluteStart = descriptor.getPsiElement().getTextRange().getStartOffset() + range.getStartOffset();
            var absoluteEnd = descriptor.getPsiElement().getTextRange().getStartOffset() + range.getEndOffset();
            var line = document.getText(new TextRange(absoluteStart, absoluteEnd));
            var foldAt = lastWhitespaceBefore(line, FOLD_COLUMN);
            if (foldAt <= 0) {
                return;
            }
            document.replaceString(absoluteStart + foldAt, absoluteStart + foldAt + 1, "\n ");
            documentManager.commitDocument(document);
        }

        private static int lastWhitespaceBefore(String line, int column) {
            var bound = Math.min(column, line.length());
            for (var idx = bound - 1; idx > 0; idx--) {
                var character = line.charAt(idx);
                if (character == ' ' || character == '\t') {
                    return idx;
                }
            }
            return -1;
        }
    }
}
