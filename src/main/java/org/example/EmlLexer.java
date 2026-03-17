package org.example;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmlLexer extends LexerBase {
    private static final Pattern BOUNDARY_PATTERN =
            Pattern.compile("boundary\\s*=\\s*\"?([^\"\\s;]+)\"?", Pattern.CASE_INSENSITIVE);

    private CharSequence buffer;
    private int bufferEnd;

    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    private List<String> boundaries;
    private boolean inHeaders;

    @Override
    public void start(@NotNull CharSequence buffer, int startOffset, int endOffset, int initialState) {
        this.buffer = buffer;
        this.bufferEnd = endOffset;
        this.tokenStart = startOffset;
        this.tokenEnd = startOffset;
        this.tokenType = null;
        this.inHeaders = (initialState == 0);

        // Collect all boundary strings from the full buffer
        this.boundaries = new ArrayList<>();
        Matcher m = BOUNDARY_PATTERN.matcher(buffer);
        while (m.find()) {
            String boundary = m.group(1);
            if (!boundaries.contains(boundary)) {
                boundaries.add(boundary);
            }
        }

        advance();
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        if (tokenStart >= bufferEnd) {
            tokenType = null;
            return;
        }

        // Find end of current line (include the \n)
        int end = tokenStart;
        while (end < bufferEnd && buffer.charAt(end) != '\n') {
            end++;
        }
        if (end < bufferEnd) {
            end++; // include the \n
        }
        tokenEnd = end;

        String line = buffer.subSequence(tokenStart, tokenEnd).toString().stripTrailing();

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
        for (String boundary : boundaries) {
            if (line.equals("--" + boundary + "--")) {
                return EmlTokenTypes.BOUNDARY_END;
            }
            if (line.equals("--" + boundary)) {
                return EmlTokenTypes.BOUNDARY_START;
            }
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
