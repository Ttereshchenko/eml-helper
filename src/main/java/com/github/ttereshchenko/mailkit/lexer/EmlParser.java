package com.github.ttereshchenko.mailkit.lexer;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.github.ttereshchenko.mailkit.psi.EmlElementTypes;
import com.github.ttereshchenko.mailkit.psi.EmlHeaderParsing;
import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class EmlParser implements PsiParser {

    private final IFileElementType fileElementType;

    EmlParser(IFileElementType fileElementType) {
        this.fileElementType = fileElementType;
    }

    @Override
    public @NotNull ASTNode parse(@NotNull IElementType root, @NotNull PsiBuilder builder) {
        var fileMarker = builder.mark();
        parseMessage(builder);
        // Anything left is unstructured trailing content; consume it as body text.
        if (!builder.eof()) {
            var tail = builder.mark();
            while (!builder.eof()) {
                builder.advanceLexer();
            }
            tail.collapse(EmlElementTypes.BODY_TEXT);
        }
        fileMarker.done(fileElementType);
        return builder.getTreeBuilt();
    }

    private static void parseMessage(PsiBuilder builder) {
        var contentTypeValue = parseHeaderBlock(builder);
        if (builder.getTokenType() == EmlTokenTypes.BLANK_LINE) {
            builder.advanceLexer();
        }
        parseBody(builder, contentTypeValue, null);
    }

    private static @Nullable String parseHeaderBlock(PsiBuilder builder) {
        if (builder.getTokenType() != EmlTokenTypes.HEADER_LINE) {
            // Empty header block — still wrap to keep PSI shape predictable.
            var emptyMarker = builder.mark();
            emptyMarker.done(EmlElementTypes.HEADER_BLOCK);
            return null;
        }
        var blockMarker = builder.mark();
        String contentTypeValue = null;
        while (builder.getTokenType() == EmlTokenTypes.HEADER_LINE) {
            var headerMarker = builder.mark();
            var firstLineRaw = builder.getTokenText();
            var firstLine = firstLineRaw == null ? "" : firstLineRaw.stripTrailing();
            builder.advanceLexer();
            List<String> continuations = null;
            while (builder.getTokenType() == EmlTokenTypes.HEADER_CONT_LINE) {
                if (continuations == null) {
                    continuations = new ArrayList<>(2);
                }
                var contRaw = builder.getTokenText();
                continuations.add(contRaw == null ? "" : contRaw.stripTrailing());
                builder.advanceLexer();
            }
            headerMarker.done(EmlElementTypes.HEADER);

            if (contentTypeValue == null) {
                var name = EmlHeaderParsing.headerName(firstLine);
                if (EmlHeaderParsing.CONTENT_TYPE.equalsIgnoreCase(name)) {
                    contentTypeValue =
                            EmlHeaderParsing.joinValue(firstLine, continuations == null ? List.of() : continuations);
                }
            }
        }
        blockMarker.done(EmlElementTypes.HEADER_BLOCK);
        return contentTypeValue;
    }

    private static void parseBody(
            PsiBuilder builder, @Nullable String contentTypeValue, @Nullable String enclosingBoundary) {
        if (EmlHeaderParsing.isMultipart(contentTypeValue)) {
            var boundary = EmlHeaderParsing.mediaTypeParam(contentTypeValue, "boundary");
            if (boundary != null) {
                parseMultipartBody(builder, boundary, enclosingBoundary);
                return;
            }
        }
        if (EmlHeaderParsing.isMessageRfc822(contentTypeValue)) {
            parseNestedMessage(builder, enclosingBoundary);
            return;
        }
        parsePlainBodyText(builder);
    }

    private static void parseMultipartBody(
            PsiBuilder builder, @NotNull String boundary, @Nullable String enclosingBoundary) {
        consumePreambleOrEpilogue(builder);

        while (builder.getTokenType() == EmlTokenTypes.BOUNDARY_START
                && boundaryName(builder.getTokenText()).equals(boundary)) {
            var partMarker = builder.mark();
            builder.advanceLexer();
            var partContentType = parseHeaderBlock(builder);
            if (builder.getTokenType() == EmlTokenTypes.BLANK_LINE) {
                builder.advanceLexer();
            }
            parseBody(builder, partContentType, boundary);
            partMarker.done(EmlElementTypes.MIME_PART);
        }

        if (builder.getTokenType() == EmlTokenTypes.BOUNDARY_END
                && boundaryName(builder.getTokenText()).equals(boundary)) {
            builder.advanceLexer();
        }

        consumePreambleOrEpilogue(builder);
    }

    private static void parseNestedMessage(PsiBuilder builder, @Nullable String enclosingBoundary) {
        var marker = builder.mark();
        var contentTypeValue = parseHeaderBlock(builder);
        if (builder.getTokenType() == EmlTokenTypes.BLANK_LINE) {
            builder.advanceLexer();
        }
        parseBody(builder, contentTypeValue, enclosingBoundary);
        marker.done(EmlElementTypes.NESTED_MESSAGE);
    }

    private static void parsePlainBodyText(PsiBuilder builder) {
        if (builder.eof() || isBoundary(builder.getTokenType())) {
            return;
        }
        var marker = builder.mark();
        while (!builder.eof() && !isBoundary(builder.getTokenType())) {
            builder.advanceLexer();
        }
        marker.collapse(EmlElementTypes.BODY_TEXT);
    }

    private static void consumePreambleOrEpilogue(PsiBuilder builder) {
        if (builder.eof() || isBoundary(builder.getTokenType())) {
            return;
        }
        var marker = builder.mark();
        while (!builder.eof() && !isBoundary(builder.getTokenType())) {
            builder.advanceLexer();
        }
        marker.collapse(EmlElementTypes.BODY_TEXT);
    }

    private static boolean isBoundary(@Nullable IElementType type) {
        return type == EmlTokenTypes.BOUNDARY_START || type == EmlTokenTypes.BOUNDARY_END;
    }

    private static String boundaryName(@Nullable String tokenText) {
        if (tokenText == null) {
            return "";
        }
        var trimmed = tokenText.stripTrailing();
        if (trimmed.endsWith("--") && trimmed.length() > 4) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        return trimmed.startsWith("--") ? trimmed.substring(2) : trimmed;
    }
}
