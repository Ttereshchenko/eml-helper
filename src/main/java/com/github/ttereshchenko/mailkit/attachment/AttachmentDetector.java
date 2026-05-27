package com.github.ttereshchenko.mailkit.attachment;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.github.ttereshchenko.mailkit.psi.EmlHeader;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderBlock;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderParsing;
import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AttachmentDetector {

    private static final String CONTENT_DISPOSITION = "Content-Disposition";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";

    private AttachmentDetector() {}

    public static @NotNull Optional<AttachmentPartInfo> detect(@NotNull EmlMimePart part) {
        Objects.requireNonNull(part, "part");
        var headerBlock = part.getHeaderBlock();
        if (headerBlock == null) {
            return Optional.empty();
        }
        var contentType = decoded(headerBlock, CONTENT_TYPE);
        var contentDisposition = decoded(headerBlock, CONTENT_DISPOSITION);
        var contentTypeName = EmlHeaderParsing.mediaTypeParam(contentType, "name");
        var contentTypeFilename = EmlHeaderParsing.mediaTypeParam(contentType, "filename");
        var dispositionFilename = EmlHeaderParsing.mediaTypeParam(contentDisposition, "filename");

        if (!qualifies(contentType, contentDisposition, contentTypeName, contentTypeFilename, dispositionFilename)) {
            return Optional.empty();
        }

        var rawFilename = firstNonBlank(dispositionFilename, contentTypeFilename, contentTypeName);
        var filename = FilenameSanitizer.sanitize(rawFilename);
        var encoding = ContentTransferEncoding.parse(decoded(headerBlock, CONTENT_TRANSFER_ENCODING));
        var firstLineRange = firstLineRange(part);
        var bodyStart = headerBlock.getTextRange().getEndOffset();
        var bodyEnd = part.getTextRange().getEndOffset();
        var pointer = SmartPointerManager.getInstance(part.getProject()).createSmartPsiElementPointer(part);
        return Optional.of(new AttachmentPartInfo(filename, encoding, firstLineRange, pointer, bodyStart, bodyEnd));
    }

    public static @Nullable EmlMimePart findEnclosing(@Nullable PsiElement element) {
        if (element == null) {
            return null;
        }
        return PsiTreeUtil.getParentOfType(element, EmlMimePart.class, false);
    }

    private static boolean qualifies(
            @Nullable String contentType,
            @Nullable String contentDisposition,
            @Nullable String contentTypeName,
            @Nullable String contentTypeFilename,
            @Nullable String dispositionFilename) {
        if (contentDisposition != null && "attachment".equalsIgnoreCase(primaryToken(contentDisposition))) {
            return true;
        }
        if (dispositionFilename != null || contentTypeFilename != null || contentTypeName != null) {
            return true;
        }
        var mediaType = EmlHeaderParsing.mediaType(contentType);
        if (mediaType == null) {
            return false;
        }
        if (mediaType.startsWith("multipart/")) {
            return false;
        }
        if (mediaType.startsWith("text/")) {
            return false;
        }
        return true;
    }

    private static String primaryToken(String headerValue) {
        var semicolon = headerValue.indexOf(';');
        var head = semicolon < 0 ? headerValue : headerValue.substring(0, semicolon);
        return head.trim().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String decoded(EmlHeaderBlock headerBlock, String name) {
        EmlHeader header = headerBlock.findHeader(name);
        return header == null ? null : header.getDecodedValue();
    }

    private static @Nullable String firstNonBlank(String... candidates) {
        for (var candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static @NotNull TextRange firstLineRange(EmlMimePart part) {
        var boundaryNode = part.getNode().findChildByType(EmlTokenTypes.BOUNDARY_START);
        if (boundaryNode != null) {
            var range = boundaryNode.getTextRange();
            return trimTrailingNewline(range, part);
        }
        var fullRange = part.getTextRange();
        var file = part.getContainingFile();
        if (file == null) {
            return fullRange;
        }
        var text = file.getText();
        var newlineIndex = text.indexOf('\n', fullRange.getStartOffset());
        var endOffset =
                newlineIndex < 0 || newlineIndex > fullRange.getEndOffset() ? fullRange.getEndOffset() : newlineIndex;
        return new TextRange(fullRange.getStartOffset(), endOffset);
    }

    private static TextRange trimTrailingNewline(TextRange range, EmlMimePart part) {
        var file = part.getContainingFile();
        if (file == null) {
            return range;
        }
        var text = file.getText();
        var end = range.getEndOffset();
        while (end > range.getStartOffset() && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return new TextRange(range.getStartOffset(), end);
    }
}
