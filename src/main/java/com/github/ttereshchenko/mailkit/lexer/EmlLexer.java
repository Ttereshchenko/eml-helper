package com.github.ttereshchenko.mailkit.lexer;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import java.util.Locale;
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

    private static final String CONTENT_TYPE_PREFIX = "content-type";
    private static final String RFC822_TYPE = "message/rfc822";

    private CharSequence buffer;
    private int bufferEnd;

    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    private EmlBoundaryParser boundaries;
    private boolean inHeaderMode;
    private boolean rfc822InCurrentBlock;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.bufferEnd = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.tokenType = null;
        this.inHeaderMode = (initialState != STATE_BODY);
        this.rfc822InCurrentBlock = (initialState == STATE_HEADER_RFC822);
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

        var boundaryType = classifyBoundary(line);
        if (boundaryType != null) {
            tokenType = boundaryType;
            inHeaderMode = (boundaryType == EmlTokenTypes.BOUNDARY_START);
            rfc822InCurrentBlock = false;
        } else if (inHeaderMode) {
            if (line.isEmpty()) {
                tokenType = EmlTokenTypes.BLANK_LINE;
                if (rfc822InCurrentBlock) {
                    // Body of a message/rfc822 part IS another RFC 822 message — stay in header mode
                    // for the nested message's own header block.
                    rfc822InCurrentBlock = false;
                } else {
                    inHeaderMode = false;
                }
            } else if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                tokenType = EmlTokenTypes.HEADER_CONT_LINE;
            } else {
                tokenType = EmlTokenTypes.HEADER_LINE;
                if (!rfc822InCurrentBlock && isContentTypeRfc822(line)) {
                    rfc822InCurrentBlock = true;
                }
            }
        } else {
            tokenType = EmlTokenTypes.BODY_LINE;
        }
    }

    private @Nullable IElementType classifyBoundary(String line) {
        if (boundaries.isEmpty()) {
            return null;
        }
        if (boundaries.isEnd(line)) {
            return EmlTokenTypes.BOUNDARY_END;
        }
        if (boundaries.isStart(line)) {
            return EmlTokenTypes.BOUNDARY_START;
        }
        return null;
    }

    private static boolean isContentTypeRfc822(String line) {
        var colonIndex = line.indexOf(':');
        if (colonIndex <= 0) {
            return false;
        }
        var headerName = line.substring(0, colonIndex).trim().toLowerCase(Locale.ROOT);
        if (!headerName.equals(CONTENT_TYPE_PREFIX)) {
            return false;
        }
        var value = line.substring(colonIndex + 1);
        var separator = value.indexOf(';');
        if (separator >= 0) {
            value = value.substring(0, separator);
        }
        return value.trim().equalsIgnoreCase(RFC822_TYPE);
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
