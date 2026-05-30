package com.github.ttereshchenko.mailkit.lexer;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlLexer extends LexerBase {
    // State encoding for incremental lexing (returned from getState()).
    // 0 keeps the historical "in headers" meaning and 1 the historical "in body" meaning;
    // 2 is "in headers AND the current block has Content-Type: message/rfc822".
    private static final int STATE_HEADER = 0;
    private static final int STATE_BODY = 1;
    private static final int STATE_HEADER_RFC822 = 2;

    private CharSequence buffer;
    private int bufferEnd;

    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    private EmlBoundaryParser boundaries;
    private boolean inHeaderMode;
    private boolean rfc822InCurrentBlock;

    // Identity-keyed single-slot cache. EmlBoundaryParser.collect is an O(N) full-document
    // walk; the IntelliJ platform restarts the lexer on every keystroke during incremental
    // relex, so without this cache each typing event scanned the entire EML on the EDT.
    // Document snapshots are immutable, so reference equality implies content equality.
    private CharSequence cachedBufferRef;
    private EmlBoundaryParser cachedBoundaries;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.bufferEnd = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.tokenType = null;
        this.inHeaderMode = (initialState != STATE_BODY);
        this.rfc822InCurrentBlock = (initialState == STATE_HEADER_RFC822);
        if (buffer != cachedBufferRef || cachedBoundaries == null) {
            cachedBoundaries = EmlBoundaryParser.collect(buffer);
            cachedBufferRef = buffer;
        }
        this.boundaries = cachedBoundaries;

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

        // The line text is only needed to tell blank/continuation/header lines apart, which a few
        // char peeks decide. Never materialize the line as a String — a multi-MB single-line base64
        // body would otherwise be copied on every keystroke (EDT relex + PSI reparse), thrashing GC.
        var boundaryType = classifyBoundary(tokenStart);
        if (boundaryType != null) {
            tokenType = boundaryType;
            inHeaderMode = (boundaryType == EmlTokenTypes.BOUNDARY_START);
            rfc822InCurrentBlock = false;
        } else if (inHeaderMode) {
            if (isBlankLine(tokenStart, tokenEnd)) {
                tokenType = EmlTokenTypes.BLANK_LINE;
                if (rfc822InCurrentBlock) {
                    // Body of a message/rfc822 part IS another RFC 822 message — stay in header mode
                    // for the nested message's own header block.
                    rfc822InCurrentBlock = false;
                } else {
                    inHeaderMode = false;
                }
            } else {
                var firstChar = buffer.charAt(tokenStart);
                if (firstChar == ' ' || firstChar == '\t') {
                    tokenType = EmlTokenTypes.HEADER_CONT_LINE;
                } else {
                    tokenType = EmlTokenTypes.HEADER_LINE;
                    if (!rfc822InCurrentBlock && boundaries.isRfc822HeaderStart(tokenStart)) {
                        rfc822InCurrentBlock = true;
                    }
                }
            }
        } else {
            tokenType = EmlTokenTypes.BODY_LINE;
        }
    }

    // A line is blank when every character up to its newline is whitespace — equivalent to the old
    // `subSequence(...).toString().stripTrailing().isEmpty()` but without allocating. Returns at the
    // first non-whitespace char, so even a megabyte-long header-mode line costs O(1) in practice.
    private boolean isBlankLine(int start, int end) {
        for (var index = start; index < end; index++) {
            if (!Character.isWhitespace(buffer.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private @Nullable IElementType classifyBoundary(int tokenStart) {
        if (boundaries.isEmpty()) {
            return null;
        }
        return boundaries.classifyBoundary(tokenStart);
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
        if (!inHeaderMode) {
            return STATE_BODY;
        }
        return rfc822InCurrentBlock ? STATE_HEADER_RFC822 : STATE_HEADER;
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
