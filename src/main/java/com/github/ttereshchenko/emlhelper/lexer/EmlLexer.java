package com.github.ttereshchenko.emlhelper.lexer;

import com.github.ttereshchenko.emlhelper.EmlTokenTypes;
import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class EmlLexer extends LexerBase {
    private CharSequence buffer;
    private int bufferEnd;

    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    private EmlBoundaryParser boundaries;
    private boolean inHeaders;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.bufferEnd = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.tokenType = null;
        this.inHeaders = (initialState == 0);
        this.boundaries = EmlBoundaryParser.collect(buffer);

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

        var line = buffer.subSequence(tokenStart, tokenEnd).toString().stripTrailing();

        if (inHeaders) {
            if (line.isEmpty()) {
                inHeaders = false;
                tokenType = EmlTokenTypes.BLANK_LINE;
            } else {
                tokenType = EmlTokenTypes.HEADER_LINE;
            }
        } else {
            tokenType = classifyBodyLine(line);
        }
    }

    private IElementType classifyBodyLine(String line) {
        if (boundaries.isEmpty()) {
            return EmlTokenTypes.BODY_LINE;
        }
        if (boundaries.isEnd(line)) {
            return EmlTokenTypes.BOUNDARY_END;
        }
        if (boundaries.isStart(line)) {
            return EmlTokenTypes.BOUNDARY_START;
        }
        return EmlTokenTypes.BODY_LINE;
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
        return inHeaders ? 0 : 1;
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
