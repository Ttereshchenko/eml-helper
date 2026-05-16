package com.github.ttereshchenko.mailkit.inspections.tools;

import com.github.ttereshchenko.mailkit.inspections.HeaderRanges;
import com.github.ttereshchenko.mailkit.inspections.rules.EncodedWordRule;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.jetbrains.annotations.NotNull;

public final class UnencodedNonAsciiHeaderInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof EmlHeader header)) {
                    return;
                }
                if (!EncodedWordRule.isStructured(header.getHeaderName())) {
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
                var ranges = EncodedWordRule.findUnencodedNonAscii(valuePart);
                if (ranges.isEmpty()) {
                    return;
                }
                var headerStart = header.getTextRange().getStartOffset();
                var lineStart = firstLine.getTextRange().getStartOffset();
                var valueOffsetInHeader = lineStart - headerStart + colon + 1;
                var fullValueRange = HeaderRanges.valueRangeOnFirstLine(header);
                for (var range : ranges) {
                    var rangeInHeader = new TextRange(
                            valueOffsetInHeader + range.startOffset(), valueOffsetInHeader + range.endOffset());
                    holder.registerProblem(
                            header,
                            "Non-ASCII characters in a structured header must be wrapped in an RFC 2047 encoded-word",
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            rangeInHeader,
                            new WrapAsEncodedWordFix(fullValueRange));
                }
            }
        };
    }

    static final class WrapAsEncodedWordFix implements LocalQuickFix {

        private final TextRange valueRange;

        WrapAsEncodedWordFix(TextRange valueRange) {
            this.valueRange = valueRange;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Wrap header value as RFC 2047 encoded-word";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            var element = descriptor.getPsiElement();
            if (element == null || valueRange == null) {
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
            var current = document.getText(valueRange);
            var encoded =
                    "=?UTF-8?B?" + Base64.getEncoder().encodeToString(current.getBytes(StandardCharsets.UTF_8)) + "?=";
            document.replaceString(valueRange.getStartOffset(), valueRange.getEndOffset(), encoded);
            documentManager.commitDocument(document);
        }
    }
}
