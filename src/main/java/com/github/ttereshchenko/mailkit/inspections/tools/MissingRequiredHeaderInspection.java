package com.github.ttereshchenko.mailkit.inspections.tools;

import com.github.ttereshchenko.mailkit.inspections.rules.DateParseRule;
import com.github.ttereshchenko.mailkit.inspections.rules.HeaderRequirementRule;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderBlock;
import com.github.ttereshchenko.mailkit.psi.EmlPsiFile;
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
import com.intellij.psi.PsiFile;
import java.util.ArrayList;
import org.jetbrains.annotations.NotNull;

public final class MissingRequiredHeaderInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitFile(@NotNull PsiFile file) {
                if (!(file instanceof EmlPsiFile emlFile)) {
                    return;
                }
                var headerBlock = emlFile.getHeaderBlock();
                var presentNames = new ArrayList<String>();
                if (headerBlock != null) {
                    for (var header : headerBlock.getHeaders()) {
                        presentNames.add(header.getHeaderName());
                    }
                }
                var missing = HeaderRequirementRule.missing(presentNames);
                if (missing.isEmpty()) {
                    return;
                }
                var anchor = anchorElement(emlFile, headerBlock);
                var anchorRange = anchorRange(emlFile, headerBlock);
                for (var name : missing) {
                    holder.registerProblem(
                            anchor,
                            "Missing required header '" + name + "' (RFC 5322 §3.6)",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            anchorRange,
                            new InsertMissingHeaderFix(name));
                }
            }
        };
    }

    private static PsiElement anchorElement(EmlPsiFile file, EmlHeaderBlock headerBlock) {
        if (headerBlock == null) {
            return file;
        }
        var first = headerBlock.getHeaders().iterator();
        return first.hasNext() ? first.next() : file;
    }

    private static TextRange anchorRange(EmlPsiFile file, EmlHeaderBlock headerBlock) {
        if (headerBlock == null) {
            var length = file.getTextLength();
            return new TextRange(0, Math.min(1, length));
        }
        var first = headerBlock.getHeaders().iterator();
        if (!first.hasNext()) {
            return new TextRange(0, Math.min(1, file.getTextLength()));
        }
        var firstHeader = first.next();
        var nameRange = firstHeader.getNameRange();
        var absolute = nameRange == null ? firstHeader.getTextRange() : nameRange;
        var headerStart = firstHeader.getTextRange().getStartOffset();
        return new TextRange(absolute.getStartOffset() - headerStart, absolute.getEndOffset() - headerStart);
    }

    static final class InsertMissingHeaderFix implements LocalQuickFix {

        private final String headerName;

        InsertMissingHeaderFix(String headerName) {
            this.headerName = headerName;
        }

        @Override
        public @NotNull String getName() {
            return "Insert '" + headerName + "' header";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Insert missing required header";
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
            var value = "Date".equalsIgnoreCase(headerName) ? DateParseRule.formatNow() : "";
            var insertion = headerName + ": " + value + "\n";
            document.insertString(0, insertion);
            documentManager.commitDocument(document);
        }
    }
}
