package com.github.ttereshchenko.mailkit.psi;

import com.github.ttereshchenko.mailkit.EmlLanguage;
import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageParserDefinitions;
import com.intellij.lang.PsiBuilderFactory;
import com.intellij.lexer.LexerBase;
import com.intellij.openapi.project.Project;
import com.intellij.psi.impl.source.tree.LazyParseableElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IReparseableElementType;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reparseable chameleon type for a leaf MIME part's body — the run of body lines between a part's
 * blank-line separator and the next boundary (or EOF). The parser collapses that run into one lazy
 * node ({@code marker.collapse(BODY_TEXT)}), which bounds the AST node count regardless of how many
 * lines the body has and lets the platform reparse only the body when an edit lands inside it,
 * instead of re-lexing and rebuilding the whole file.
 *
 * <p>{@link #isParsable} is deliberately conservative: an isolated reparse is allowed only when the
 * new body text stays newline-terminated and contains no boundary-shaped line. Either condition
 * could otherwise let a body edit silently change the document's MIME structure (a line becoming a
 * boundary, or the last line merging with the following boundary), so anything riskier falls back to
 * a — now cheap — full reparse, which re-runs {@link com.github.ttereshchenko.mailkit.lexer.EmlBoundaryParser}
 * globally and is always correct (including "last close wins").
 */
public final class EmlBodyContentType extends IReparseableElementType {

    public EmlBodyContentType() {
        super("EML_BODY_TEXT", EmlLanguage.INSTANCE);
    }

    @Override
    public @NotNull ASTNode createNode(CharSequence text) {
        // Required by the reparse infrastructure: the body collapses into a lazy node whose contents
        // are (re)built by parseContents on demand.
        return new LazyParseableElement(this, text);
    }

    @Override
    public ASTNode parseContents(@NotNull ASTNode chameleon) {
        var parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(getLanguage());
        var builder = PsiBuilderFactory.getInstance()
                .createBuilder(parserDefinition, new BodyLineLexer(), chameleon.getChars());
        var root = builder.mark();
        while (!builder.eof()) {
            builder.advanceLexer();
        }
        root.done(this);
        // The returned node's children (the BODY_LINE leaves) become the chameleon's children.
        return builder.getTreeBuilt().getFirstChildNode();
    }

    @Override
    public boolean isParsable(
            @Nullable ASTNode parent,
            @NotNull CharSequence buffer,
            @NotNull Language fileLanguage,
            @NotNull Project project) {
        var length = buffer.length();
        // Must stay newline-terminated, else the last body line could merge with the following
        // boundary line on an isolated reparse and drop a BOUNDARY_END the full parse would keep.
        if (length == 0 || buffer.charAt(length - 1) != '\n') {
            return false;
        }
        // Any "--…" line could be (or become) a MIME boundary, whose classification is global
        // ("last close wins"); defer to a full reparse so EmlBoundaryParser reclassifies everything.
        if (startsWithDashes(buffer, 0)) {
            return false;
        }
        for (var index = 0; index < length - 1; index++) {
            if (buffer.charAt(index) == '\n' && startsWithDashes(buffer, index + 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithDashes(CharSequence buffer, int lineStart) {
        return lineStart + 1 < buffer.length()
                && buffer.charAt(lineStart) == '-'
                && buffer.charAt(lineStart + 1) == '-';
    }

    /**
     * Re-lexes collapsed body content: every physical line becomes one {@link EmlTokenTypes#BODY_LINE}.
     * The collapsed run is a leaf-part body the main lexer already classified as body lines, and
     * {@link #isParsable} forbids boundary-shaped lines, so no header/boundary logic is needed here.
     */
    private static final class BodyLineLexer extends LexerBase {

        private CharSequence buffer;
        private int bufferEnd;
        private int tokenStart;
        private int tokenEnd;
        private IElementType tokenType;

        @Override
        public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
            this.buffer = Objects.requireNonNull(buffer, "buffer");
            this.bufferEnd = endOffset;
            this.tokenStart = startOffset;
            this.tokenEnd = startOffset;
            this.tokenType = null;
            advance();
        }

        @Override
        public void advance() {
            tokenStart = tokenEnd;
            if (tokenStart >= bufferEnd) {
                tokenType = null;
                return;
            }
            var end = tokenStart;
            while (end < bufferEnd && buffer.charAt(end) != '\n') {
                end++;
            }
            if (end < bufferEnd) {
                end++;
            }
            tokenEnd = end;
            tokenType = EmlTokenTypes.BODY_LINE;
        }

        @Override
        public @Nullable IElementType getTokenType() {
            return tokenType;
        }

        @Override
        public int getTokenStart() {
            return tokenStart;
        }

        @Override
        public int getTokenEnd() {
            return tokenEnd;
        }

        @Override
        public int getState() {
            return 0;
        }

        @Override
        public @NotNull CharSequence getBufferSequence() {
            return buffer;
        }

        @Override
        public int getBufferEnd() {
            return bufferEnd;
        }
    }
}
