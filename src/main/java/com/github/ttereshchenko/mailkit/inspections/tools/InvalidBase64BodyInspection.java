package com.github.ttereshchenko.mailkit.inspections.tools;

import com.github.ttereshchenko.mailkit.attachment.ContentTransferEncoding;
import com.github.ttereshchenko.mailkit.inspections.rules.Base64AlphabetRule;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderBlock;
import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
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

public final class InvalidBase64BodyInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof EmlMimePart part)) {
                    return;
                }
                var headerBlock = part.getHeaderBlock();
                if (headerBlock == null) {
                    return;
                }
                var cte = headerBlock.findHeader("Content-Transfer-Encoding");
                if (cte == null
                        || ContentTransferEncoding.parse(cte.getDecodedValue()) != ContentTransferEncoding.BASE64) {
                    return;
                }
                var bodyRange = bodyOnlyRange(part, headerBlock);
                if (bodyRange == null) {
                    return;
                }
                var bodyText = bodyRange.substring(part.getContainingFile().getText());
                var runs = Base64AlphabetRule.invalidRuns(bodyText);
                if (runs.isEmpty()) {
                    return;
                }
                var fix = new StripInvalidBase64CharsFix(bodyRange);
                var partStart = part.getTextRange().getStartOffset();
                for (var run : runs) {
                    var absStart = bodyRange.getStartOffset() + run.startOffset();
                    var absEnd = bodyRange.getStartOffset() + run.endOffset();
                    holder.registerProblem(
                            part,
                            "Character outside the base64 alphabet (RFC 4648)",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            new TextRange(absStart - partStart, absEnd - partStart),
                            fix);
                }
            }
        };
    }

    private static TextRange bodyOnlyRange(EmlMimePart part, EmlHeaderBlock headerBlock) {
        var bodyStart = headerBlock.getTextRange().getEndOffset();
        var partEnd = part.getTextRange().getEndOffset();
        var fileText = part.getContainingFile().getText();
        while (bodyStart < partEnd && (fileText.charAt(bodyStart) == '\n' || fileText.charAt(bodyStart) == '\r')) {
            bodyStart++;
        }
        if (bodyStart >= partEnd) {
            return null;
        }
        return new TextRange(bodyStart, partEnd);
    }

    static final class StripInvalidBase64CharsFix implements LocalQuickFix {

        private final TextRange contentRange;

        StripInvalidBase64CharsFix(TextRange contentRange) {
            this.contentRange = contentRange;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Strip invalid base64 characters";
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
            var current = document.getText(contentRange);
            var runs = Base64AlphabetRule.invalidRuns(current);
            for (var idx = runs.size() - 1; idx >= 0; idx--) {
                var run = runs.get(idx);
                document.deleteString(
                        contentRange.getStartOffset() + run.startOffset(),
                        contentRange.getStartOffset() + run.endOffset());
            }
            documentManager.commitDocument(document);
        }
    }
}
