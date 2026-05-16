package com.github.ttereshchenko.mailkit.inspections.tools;

import com.github.ttereshchenko.mailkit.attachment.ContentTransferEncoding;
import com.github.ttereshchenko.mailkit.inspections.rules.CharsetDecodeRule;
import com.github.ttereshchenko.mailkit.psi.EmlHeader;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderParsing;
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
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

public final class CharsetMismatchInspection extends LocalInspectionTool {

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
                var contentType = headerBlock.findHeader(EmlHeaderParsing.CONTENT_TYPE);
                if (contentType == null) {
                    return;
                }
                var rawCt = contentType.getRawValue();
                var charset = EmlHeaderParsing.mediaTypeParam(rawCt, "charset");
                if (charset == null) {
                    return;
                }
                var encoding = ContentTransferEncoding.parse(
                        headerBlock.findHeader("Content-Transfer-Encoding") == null
                                ? null
                                : headerBlock
                                        .findHeader("Content-Transfer-Encoding")
                                        .getDecodedValue());
                if (encoding == ContentTransferEncoding.BASE64
                        || encoding == ContentTransferEncoding.QUOTED_PRINTABLE) {
                    return;
                }
                var bodyStart = headerBlock.getTextRange().getEndOffset();
                var partEnd = part.getTextRange().getEndOffset();
                var fileText = part.getContainingFile().getText();
                while (bodyStart < partEnd
                        && (fileText.charAt(bodyStart) == '\n' || fileText.charAt(bodyStart) == '\r')) {
                    bodyStart++;
                }
                if (bodyStart >= partEnd) {
                    return;
                }
                var bodyText = fileText.substring(bodyStart, partEnd);
                var bytes = bodyText.getBytes(StandardCharsets.UTF_8);
                var result = CharsetDecodeRule.check(bytes, charset);
                if (result == null) {
                    return;
                }
                var absStart = bodyStart + result.invalidRange().startOffset();
                var absEnd = bodyStart + result.invalidRange().endOffset();
                var partStart = part.getTextRange().getStartOffset();
                var charsetParamRange = locateCharsetParam(contentType, rawCt);
                LocalQuickFix[] fixes = (charsetParamRange != null && result.suggestion() != null)
                        ? new LocalQuickFix[] {new ReplaceCharsetFix(charsetParamRange, result.suggestion())}
                        : LocalQuickFix.EMPTY_ARRAY;
                holder.registerProblem(
                        part,
                        "Body byte cannot be decoded with declared charset '" + charset + "'",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        new TextRange(absStart - partStart, absEnd - partStart),
                        fixes);
            }
        };
    }

    private static TextRange locateCharsetParam(EmlHeader contentType, String rawValue) {
        if (rawValue == null) {
            return null;
        }
        var lower = rawValue.toLowerCase(Locale.ROOT);
        var index = lower.indexOf("charset");
        if (index < 0) {
            return null;
        }
        var equals = lower.indexOf('=', index);
        if (equals < 0) {
            return null;
        }
        var valStart = equals + 1;
        while (valStart < rawValue.length() && Character.isWhitespace(rawValue.charAt(valStart))) {
            valStart++;
        }
        var quoted = valStart < rawValue.length() && rawValue.charAt(valStart) == '"';
        var contentStart = quoted ? valStart + 1 : valStart;
        var idx = contentStart;
        if (quoted) {
            while (idx < rawValue.length() && rawValue.charAt(idx) != '"') {
                idx++;
            }
        } else {
            while (idx < rawValue.length()
                    && rawValue.charAt(idx) != ';'
                    && !Character.isWhitespace(rawValue.charAt(idx))) {
                idx++;
            }
        }
        // Map raw-value offset back to header first-line offset. The raw value loses the
        // leading "Header-Name:" and any leading whitespace; rescan the first line directly.
        var firstLine = com.github.ttereshchenko.mailkit.inspections.HeaderRanges.firstLineNode(contentType);
        if (firstLine == null) {
            return null;
        }
        var lineText = firstLine.getText();
        var charsetInLine = lineText.toLowerCase(Locale.ROOT).indexOf("charset");
        if (charsetInLine < 0) {
            return null;
        }
        var equalsInLine = lineText.indexOf('=', charsetInLine);
        if (equalsInLine < 0) {
            return null;
        }
        var lineValStart = equalsInLine + 1;
        while (lineValStart < lineText.length() && Character.isWhitespace(lineText.charAt(lineValStart))) {
            lineValStart++;
        }
        var lineQuoted = lineValStart < lineText.length() && lineText.charAt(lineValStart) == '"';
        var lineContentStart = lineQuoted ? lineValStart + 1 : lineValStart;
        var lineEnd = lineContentStart;
        if (lineQuoted) {
            while (lineEnd < lineText.length() && lineText.charAt(lineEnd) != '"') {
                lineEnd++;
            }
        } else {
            while (lineEnd < lineText.length()
                    && lineText.charAt(lineEnd) != ';'
                    && !Character.isWhitespace(lineText.charAt(lineEnd))) {
                lineEnd++;
            }
        }
        var base = firstLine.getTextRange().getStartOffset();
        return new TextRange(base + lineContentStart, base + lineEnd);
    }

    static final class ReplaceCharsetFix implements LocalQuickFix {

        private final TextRange charsetValueRange;
        private final String replacement;

        ReplaceCharsetFix(TextRange charsetValueRange, String replacement) {
            this.charsetValueRange = charsetValueRange;
            this.replacement = replacement;
        }

        @Override
        public @NotNull String getName() {
            return "Replace charset with '" + replacement + "'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Replace declared charset";
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
            document.replaceString(charsetValueRange.getStartOffset(), charsetValueRange.getEndOffset(), replacement);
            documentManager.commitDocument(document);
        }
    }
}
