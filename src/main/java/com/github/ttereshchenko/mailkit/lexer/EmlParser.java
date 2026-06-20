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

    // Hard cap on MIME nesting depth (multipart parts and message/rfc822 chains). Real messages
    // nest only a handful of levels; a crafted message with thousands of nested parts would
    // otherwise recurse until the parser stack overflows. Past the cap, the remaining content is
    // consumed as flat body text so parsing still completes (and stays well-formed) on such input.
    private static final int MAX_NESTING_DEPTH = 100;

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
        parseBody(builder, contentTypeValue, null, 0);
    }

    private static @Nullable String parseHeaderBlock(PsiBuilder builder) {
        if (!headerBlockStartsHere(builder)) {
            // Empty header block — still wrap to keep PSI shape predictable.
            var emptyMarker = builder.mark();
            emptyMarker.done(EmlElementTypes.HEADER_BLOCK);
            return null;
        }
        var blockMarker = builder.mark();
        String contentTypeValue = null;
        while (true) {
            if (builder.getTokenType() == EmlTokenTypes.HEADER_LINE) {
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
                        contentTypeValue = EmlHeaderParsing.joinValue(
                                firstLine, continuations == null ? List.of() : continuations);
                    }
                }
            } else if (builder.getTokenType() == EmlTokenTypes.BLANK_LINE
                    && !EmlHeaderParsing.isMessageRfc822(contentTypeValue)
                    && blankPrecedesMoreHeaders(builder)) {
                // A stray blank line inside the header block: the lexer kept header mode across it (its
                // tolerant pre-first-boundary rescue — see EmlLexer.advance), so a HEADER_LINE follows.
                // Fold it into the block so the headers after it are still parsed as HEADER elements
                // (and stay highlighted). The genuine header/body separator is instead followed by body
                // text, a boundary, or EOF, so blankPrecedesMoreHeaders is false there and it is left
                // for the caller to consume.
                //
                // Exception: when this block is a message/rfc822 container, the lexer ALSO keeps header
                // mode across the blank that introduces the nested message (its body is itself an RFC 822
                // message), so a HEADER_LINE follows here too. That blank is the structural separator, not
                // a stray one — leave it for parseNestedMessage so the nested headers form their own block.
                builder.advanceLexer();
            } else {
                break;
            }
        }
        blockMarker.done(EmlElementTypes.HEADER_BLOCK);
        return contentTypeValue;
    }

    // The header block begins at the current position when the token is a header line, or a stray blank
    // line that the lexer kept in header mode (a header line follows it, possibly after more blanks).
    private static boolean headerBlockStartsHere(PsiBuilder builder) {
        var type = builder.getTokenType();
        return type == EmlTokenTypes.HEADER_LINE
                || (type == EmlTokenTypes.BLANK_LINE && blankPrecedesMoreHeaders(builder));
    }

    // Looks past the current run of blank lines (BLANK_LINE is a real token here — getWhitespaceTokens()
    // is empty, so it is never auto-skipped) and reports whether the next token is a header line. The
    // lexer only emits a HEADER_LINE after a blank when it rescued that blank as stray, so following the
    // token stream keeps this parser's header block exactly consistent with the lexer's classification.
    private static boolean blankPrecedesMoreHeaders(PsiBuilder builder) {
        var steps = 1;
        var ahead = builder.rawLookup(steps);
        while (ahead == EmlTokenTypes.BLANK_LINE) {
            steps++;
            ahead = builder.rawLookup(steps);
        }
        return ahead == EmlTokenTypes.HEADER_LINE;
    }

    private static void parseBody(
            PsiBuilder builder, @Nullable String contentTypeValue, @Nullable String enclosingBoundary, int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            // Stop descending into pathologically nested MIME so a crafted message cannot overflow
            // the parser stack. Remaining tokens are consumed as flat body text here; the top-level
            // parse() also collapses any trailing tokens into BODY_TEXT, so the tree stays valid.
            parsePlainBodyText(builder);
            return;
        }
        if (EmlHeaderParsing.isMultipart(contentTypeValue)) {
            var boundary = EmlHeaderParsing.mediaTypeParam(contentTypeValue, "boundary");
            if (boundary != null) {
                parseMultipartBody(builder, boundary, enclosingBoundary, depth);
                return;
            }
        }
        if (EmlHeaderParsing.isMessageRfc822(contentTypeValue)) {
            parseNestedMessage(builder, enclosingBoundary, depth);
            return;
        }
        parsePlainBodyText(builder);
    }

    private static void parseMultipartBody(
            PsiBuilder builder, @NotNull String boundary, @Nullable String enclosingBoundary, int depth) {
        consumePreambleOrEpilogue(builder);

        while (builder.getTokenType() == EmlTokenTypes.BOUNDARY_START
                && boundaryName(builder.getTokenText()).equals(boundary)) {
            var partMarker = builder.mark();
            builder.advanceLexer();
            var partContentType = parseHeaderBlock(builder);
            if (builder.getTokenType() == EmlTokenTypes.BLANK_LINE) {
                builder.advanceLexer();
            }
            parseBody(builder, partContentType, boundary, depth + 1);
            partMarker.done(EmlElementTypes.MIME_PART);
        }

        if (builder.getTokenType() == EmlTokenTypes.BOUNDARY_END
                && boundaryName(builder.getTokenText()).equals(boundary)) {
            builder.advanceLexer();
        }

        consumePreambleOrEpilogue(builder);
    }

    private static void parseNestedMessage(PsiBuilder builder, @Nullable String enclosingBoundary, int depth) {
        var marker = builder.mark();
        var contentTypeValue = parseHeaderBlock(builder);
        if (builder.getTokenType() == EmlTokenTypes.BLANK_LINE) {
            builder.advanceLexer();
        }
        parseBody(builder, contentTypeValue, enclosingBoundary, depth + 1);
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
