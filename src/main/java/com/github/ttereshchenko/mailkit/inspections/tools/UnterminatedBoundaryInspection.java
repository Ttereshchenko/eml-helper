package com.github.ttereshchenko.mailkit.inspections.tools;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.github.ttereshchenko.mailkit.inspections.rules.BoundaryClosureRule;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderBlock;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderParsing;
import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
import com.github.ttereshchenko.mailkit.psi.EmlPsiFile;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public final class UnterminatedBoundaryInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitFile(@NotNull PsiFile file) {
                if (!(file instanceof EmlPsiFile emlFile)) {
                    return;
                }
                checkContainer(emlFile, emlFile.getHeaderBlock(), holder);
                for (var part : PsiTreeUtil.findChildrenOfType(emlFile, EmlMimePart.class)) {
                    checkContainer(part, part.getHeaderBlock(), holder);
                }
            }
        };
    }

    private static void checkContainer(PsiElement container, EmlHeaderBlock headerBlock, ProblemsHolder holder) {
        if (headerBlock == null) {
            return;
        }
        var contentType = headerBlock.findHeader(EmlHeaderParsing.CONTENT_TYPE);
        if (contentType == null) {
            return;
        }
        var rawValue = contentType.getRawValue();
        if (!EmlHeaderParsing.isMultipart(rawValue)) {
            return;
        }
        var boundary = EmlHeaderParsing.mediaTypeParam(rawValue, "boundary");
        if (boundary == null || boundary.isEmpty()) {
            return;
        }
        var bodyText = bodyTextOf(container);
        if (BoundaryClosureRule.hasClosingMarker(bodyText, boundary)) {
            return;
        }
        var firstOpener = firstOpeningBoundary(container, boundary);
        var insertionOffset = container.getTextRange().getEndOffset();
        var fix = new InsertClosingBoundaryFix(boundary, insertionOffset);
        if (firstOpener != null) {
            holder.registerProblem(
                    container,
                    "Multipart boundary '" + boundary + "' is never closed (RFC 2046 §5.1.1)",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    rangeWithin(container, firstOpener.getTextRange()),
                    fix);
        } else {
            // No --boundary opener seen — anchor on the Content-Type header value.
            holder.registerProblem(
                    contentType,
                    "Multipart boundary '" + boundary + "' is declared but never appears",
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    fix);
        }
    }

    private static String bodyTextOf(PsiElement container) {
        if (container instanceof EmlMimePart part) {
            var range = part.getContentRange();
            return range == null
                    ? ""
                    : range.substring(container.getContainingFile().getText());
        }
        var fileText = container.getContainingFile().getText();
        var headerBlock = container instanceof EmlPsiFile emlFile ? emlFile.getHeaderBlock() : null;
        if (headerBlock == null) {
            return fileText;
        }
        return fileText.substring(headerBlock.getTextRange().getEndOffset());
    }

    private static ASTNode firstOpeningBoundary(PsiElement container, String boundary) {
        var marker = "--" + boundary;
        var nodes = collectTokens(container.getNode(), EmlTokenTypes.BOUNDARY_START);
        for (var node : nodes) {
            var text = node.getText().stripTrailing();
            if (text.equals(marker)) {
                return node;
            }
        }
        return null;
    }

    private static List<ASTNode> collectTokens(ASTNode root, com.intellij.psi.tree.IElementType type) {
        var result = new ArrayList<ASTNode>();
        collectInto(root, type, result);
        return result;
    }

    private static void collectInto(ASTNode node, com.intellij.psi.tree.IElementType type, List<ASTNode> sink) {
        if (node.getElementType() == type) {
            sink.add(node);
        }
        for (var child = node.getFirstChildNode(); child != null; child = child.getTreeNext()) {
            collectInto(child, type, sink);
        }
    }

    private static TextRange rangeWithin(PsiElement container, TextRange absolute) {
        var base = container.getTextRange().getStartOffset();
        return new TextRange(absolute.getStartOffset() - base, absolute.getEndOffset() - base);
    }

    static final class InsertClosingBoundaryFix implements LocalQuickFix {

        private final String boundary;
        private final int insertionOffset;

        InsertClosingBoundaryFix(String boundary, int insertionOffset) {
            this.boundary = boundary;
            this.insertionOffset = insertionOffset;
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Insert closing MIME boundary";
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
            var safeOffset = Math.min(insertionOffset, document.getTextLength());
            var prefix = safeOffset > 0
                            && document.getText(new TextRange(safeOffset - 1, safeOffset))
                                    .equals("\n")
                    ? ""
                    : "\n";
            document.insertString(safeOffset, prefix + "--" + boundary + "--\n");
            documentManager.commitDocument(document);
        }
    }
}
