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
                } else if (!boundaries.isBeforeFirstBoundary(tokenStart)
                        || !blankLineOpensAnotherHeaderBlock(tokenEnd)) {
                    // RFC 5322 §2.1 / §3.5: the body is separated from the header section by an empty
                    // line. Strictly, ANY blank line ends the headers, but this is a tolerant editor for
                    // draft/malformed EML: a stray blank typed into the middle of (or before) the header
                    // block must not knock the remaining headers down to body coloring.
                    //
                    // The rescue is confined to the top-level (preamble) header block — the region before
                    // the first MIME boundary (boundaries.isBeforeFirstBoundary). Inside a MIME part body
                    // a run of header-shaped lines (journal reports: `Sender:`, `Subject:`, ...) is
                    // indistinguishable by forward lookahead from a genuine header block, so there we keep
                    // the strict rule and the part's first blank ends its headers. In the outer block we
                    // rescue the blank only when the lines after it still form a proper header block
                    // (blankLineOpensAnotherHeaderBlock); a boundary, real body text, or EOF after the
                    // blank(s) is the genuine separator and ends the header section as before.
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

    // Bounded, allocation-free lookahead that decides whether a stray blank line should be rescued
    // (kept in header mode) instead of treated as the genuine header/body separator (rfc5322 §2.1 /
    // §3.5). Starting at `offset` (the line right after the blank), it skips any further consecutive
    // blank lines, then walks the run of header-shaped / continuation lines that follows. The blank is
    // a stray separator — and is rescued — only when that run is itself terminated by another blank
    // line or by EOF, i.e. it forms a proper header block (rfc5322 §2.2: a header section is a run of
    // header fields ended by an empty line).
    //
    // False-positive bound: a MIME part's text/plain body can contain lines that merely LOOK like
    // headers (journal reports: `Sender:`, `Subject:` ...). Such a run is terminated by the part's
    // closing boundary marker, NOT by a blank line, so this method returns false and the blank stays
    // the genuine separator — the header-like body lines are correctly left as BODY_LINE. A run that
    // hits real (non-header-shaped) body text immediately, a boundary, or runs straight into a boundary
    // likewise ends the headers.
    //
    // Hot-path discipline: lines are inspected via raw char offsets, never materialized as Strings, and
    // isHeaderFieldLine bails at the first non-ftext char. A multi-MB base64 body line is non-header-
    // shaped, so the scan stops at it immediately; the walk only ever covers short header-shaped lines.
    private boolean blankLineOpensAnotherHeaderBlock(int offset) {
        var lineStart = offset;
        var sawHeaderLine = false;
        while (lineStart < bufferEnd) {
            var lineEnd = lineStart;
            while (lineEnd < bufferEnd && buffer.charAt(lineEnd) != '\n') {
                lineEnd++;
            }
            if (isBlankLine(lineStart, lineEnd)) {
                if (!sawHeaderLine) {
                    // Still skipping the run of consecutive blank lines before any header line.
                    if (lineEnd >= bufferEnd) {
                        return false;
                    }
                    lineStart = lineEnd + 1;
                    continue;
                }
                // The header-shaped run is terminated by a blank line: a proper header block. Rescue.
                return true;
            }
            // A boundary marker is a structural body-side token, never part of a header block. The
            // genuine separator precedes it (the real Apple-Mail sample has two blank lines before the
            // first `--boundary`), and a part body's header-like text is closed by its boundary.
            if (classifyBoundary(lineStart) != null) {
                return false;
            }
            var firstChar = buffer.charAt(lineStart);
            var continuationLine = firstChar == ' ' || firstChar == '\t';
            if (continuationLine) {
                if (!sawHeaderLine) {
                    // A continuation line with no preceding field is not a header block start.
                    return false;
                }
            } else if (isHeaderFieldLine(lineStart, lineEnd)) {
                sawHeaderLine = true;
            } else {
                // Real body text immediately after the blank(s): the blank is the genuine separator.
                return false;
            }
            lineStart = lineEnd >= bufferEnd ? lineEnd : lineEnd + 1;
        }
        // Reached EOF while still inside a header-shaped run: a header block ended by EOF. Rescue if we
        // actually saw at least one header field (otherwise there was nothing to rescue).
        return sawHeaderLine;
    }

    // RFC 5322 §2.2 / §3.6.8: a header field starts with a field-name = 1*ftext, where
    // ftext = %d33-57 / %d59-126 (printable US-ASCII excluding ':'), followed immediately by ':'.
    // Continuation lines (leading SP/TAB) are not field starts. Scans at most one field-name worth
    // of chars and stops at the first non-ftext character, so it is O(1) on body lines.
    private boolean isHeaderFieldLine(int start, int end) {
        if (start >= end) {
            return false;
        }
        var first = buffer.charAt(start);
        if (first == ' ' || first == '\t') {
            return false;
        }
        for (var index = start; index < end; index++) {
            var character = buffer.charAt(index);
            if (character == ':') {
                return index > start;
            }
            if (character < 0x21 || character > 0x7E) {
                return false;
            }
        }
        return false;
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
