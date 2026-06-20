package com.github.ttereshchenko.mailkit.lexer;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.tree.IElementType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class EmlBoundaryParser {
    private static final Logger LOG = Logger.getInstance(EmlBoundaryParser.class);

    // Quoted branch (group 1) permits internal whitespace per RFC 2046 (bchars = bcharsnospace / " ").
    // Unquoted branch (group 2) keeps the legacy bare-token shape. The two branches are mutually
    // exclusive, so exactly one group is non-null on each match.
    private static final Pattern BOUNDARY_PATTERN =
            Pattern.compile("boundary\\s*=\\s*(?:\"([^\"]*)\"|([^\\s;]+))", Pattern.CASE_INSENSITIVE);

    private static final String CONTENT_TYPE_PREFIX = "content-type";
    private static final String RFC822_TYPE = "message/rfc822";

    private static final EmlBoundaryParser EMPTY =
            new EmlBoundaryParser(Collections.emptySet(), Collections.emptyMap(), Collections.emptySet());

    private final Set<String> rawNames;
    private final Map<Integer, IElementType> classification;
    private final Set<Integer> rfc822HeaderStartOffsets;
    // Line-start offset of the first boundary marker in the document, or Integer.MAX_VALUE when there
    // is none. Everything before it is the top-level (preamble) header block; the tolerant stray-blank
    // rescue is confined to that region (see EmlLexer.advance), since header-shaped lines inside a MIME
    // part body would otherwise be indistinguishable from a real header block.
    private final int firstBoundaryOffset;

    private EmlBoundaryParser(
            Set<String> rawNames, Map<Integer, IElementType> classification, Set<Integer> rfc822HeaderStartOffsets) {
        this.rawNames = rawNames;
        this.classification = classification;
        this.rfc822HeaderStartOffsets = rfc822HeaderStartOffsets;
        var earliest = Integer.MAX_VALUE;
        for (var offset : classification.keySet()) {
            if (offset < earliest) {
                earliest = offset;
            }
        }
        this.firstBoundaryOffset = earliest;
    }

    public static EmlBoundaryParser collect(CharSequence text) {
        Objects.requireNonNull(text, "text");
        var bufferLength = text.length();
        if (bufferLength == 0) {
            return EMPTY;
        }

        var rawNames = new HashSet<String>();
        var candidates = new ArrayList<MarkerCandidate>();
        var rfc822HeaderStartOffsets = new HashSet<Integer>();
        var inHeader = true;
        var rfc822Pending = false;
        // Whether a boundary marker has been seen yet. The tolerant stray-blank rescue (mirroring
        // EmlLexer.isBeforeFirstBoundary) is confined to the top-level header block, before any part.
        var seenBoundary = false;
        var lineStart = 0;
        // RFC 5322 §2.2.3 unfolding: a Content-Type value may be split across continuation lines
        // (any line whose first char is SP/TAB). Accumulate until the header ends — at the next
        // non-continuation line, blank line, boundary marker, or EOF — then test the unfolded
        // value against `message/rfc822`. The line-only check this replaces missed folded values
        // like `Content-Type:\n message/rfc822`, leaving the inner message mis-lexed as a flat body.
        var pendingContentTypeStart = -1;
        var pendingContentTypeValue = new StringBuilder();

        while (lineStart < bufferLength) {
            var lineEnd = lineStart;
            while (lineEnd < bufferLength && text.charAt(lineEnd) != '\n') {
                lineEnd++;
            }
            // Trailing-whitespace-stripped content end (excludes the '\n', any '\r' and trailing WSP),
            // kept as an index so the line is never allocated. Equivalent to the old
            // subSequence(...).stripTrailing(); contentEnd == lineStart means the line is blank.
            var contentEnd = lineEnd;
            while (contentEnd > lineStart && Character.isWhitespace(text.charAt(contentEnd - 1))) {
                contentEnd--;
            }

            var match = matchBoundary(text, lineStart, contentEnd, rawNames);
            if (match != null) {
                if (pendingContentTypeStart >= 0 && isRfc822Value(pendingContentTypeValue)) {
                    rfc822HeaderStartOffsets.add(pendingContentTypeStart);
                }
                pendingContentTypeStart = -1;
                pendingContentTypeValue.setLength(0);
                candidates.add(new MarkerCandidate(lineStart, match.name(), match.closing()));
                inHeader = !match.closing();
                rfc822Pending = false;
                seenBoundary = true;
            } else if (inHeader) {
                if (contentEnd == lineStart) {
                    if (pendingContentTypeStart >= 0) {
                        if (isRfc822Value(pendingContentTypeValue)) {
                            rfc822Pending = true;
                            rfc822HeaderStartOffsets.add(pendingContentTypeStart);
                        }
                        pendingContentTypeStart = -1;
                        pendingContentTypeValue.setLength(0);
                    }
                    if (rfc822Pending) {
                        rfc822Pending = false;
                    } else if (seenBoundary || !blankLineOpensAnotherHeaderBlock(text, lineEnd, bufferLength)) {
                        // Stay consistent with EmlLexer's tolerant blank-line rule (see its advance()):
                        // before the first boundary, a stray blank in the top-level header block does NOT
                        // end the headers when the lines after it still form a proper header block. Keeping
                        // `inHeader` true means a `boundary=` / `Content-Type: message/rfc822` declaration
                        // on a header typed below a stray blank is still harvested, so the lexer and this
                        // parser agree on boundary tokens and rfc822 nesting. Inside a MIME part (after a
                        // boundary) the strict rule applies, so a part body's header-like text is never
                        // harvested out of the body.
                        inHeader = false;
                    }
                } else {
                    // Genuine (short) header line. Body lines never reach this branch, so the
                    // multi-MB base64 body is never materialized here.
                    var line = text.subSequence(lineStart, contentEnd).toString();
                    var matcher = BOUNDARY_PATTERN.matcher(line);
                    while (matcher.find()) {
                        var quoted = matcher.group(1);
                        var name = quoted != null ? quoted : matcher.group(2);
                        if (!name.isEmpty()) {
                            addBoundary(rawNames, name);
                        }
                    }
                    var firstChar = line.charAt(0);
                    var continuationLine = firstChar == ' ' || firstChar == '\t';
                    if (continuationLine) {
                        if (pendingContentTypeStart >= 0) {
                            // Collapse FWS at the fold point. Strict RFC 5322 keeps the WSP
                            // verbatim, but message/rfc822 is parsed as type "/" subtype where
                            // CFWS may not appear inside a token; some MUAs nevertheless fold
                            // mid-token, and we want to detect those too.
                            pendingContentTypeValue.append(line.stripLeading());
                        }
                    } else {
                        if (pendingContentTypeStart >= 0) {
                            if (isRfc822Value(pendingContentTypeValue)) {
                                rfc822Pending = true;
                                rfc822HeaderStartOffsets.add(pendingContentTypeStart);
                            }
                            pendingContentTypeStart = -1;
                            pendingContentTypeValue.setLength(0);
                        }
                        var colonIndex = line.indexOf(':');
                        if (colonIndex > 0
                                && line.substring(0, colonIndex)
                                        .trim()
                                        .toLowerCase(Locale.ROOT)
                                        .equals(CONTENT_TYPE_PREFIX)) {
                            pendingContentTypeStart = lineStart;
                            pendingContentTypeValue.append(line, colonIndex + 1, line.length());
                        }
                    }
                }
            }

            lineStart = (lineEnd < bufferLength) ? lineEnd + 1 : lineEnd;
        }

        if (pendingContentTypeStart >= 0 && isRfc822Value(pendingContentTypeValue)) {
            rfc822HeaderStartOffsets.add(pendingContentTypeStart);
        }

        if (rawNames.isEmpty() && rfc822HeaderStartOffsets.isEmpty()) {
            return EMPTY;
        }

        return new EmlBoundaryParser(
                Collections.unmodifiableSet(rawNames),
                Collections.unmodifiableMap(resolveClassification(candidates)),
                Collections.unmodifiableSet(rfc822HeaderStartOffsets));
    }

    private static @Nullable BoundaryMatch matchBoundary(
            CharSequence text, int lineStart, int contentEnd, Set<String> knownNames) {
        // Peek for the "--" prefix without allocating: a base64 body line starts with a base64 char,
        // so it is rejected here in O(1) and never copied. Only a real "--…" line is materialized.
        if (knownNames.isEmpty()
                || contentEnd - lineStart < 2
                || contentEnd - lineStart > 100
                || text.charAt(lineStart) != '-'
                || text.charAt(lineStart + 1) != '-') {
            return null;
        }
        var stripped = text.subSequence(lineStart + 2, contentEnd).toString();
        if (stripped.length() >= 2 && stripped.endsWith("--")) {
            var endName = stripped.substring(0, stripped.length() - 2);
            if (knownNames.contains(endName)) {
                return new BoundaryMatch(endName, true);
            }
            if (knownNames.contains(stripped)) {
                // Declared boundary literally ends with "--" — this line opens it.
                return new BoundaryMatch(stripped, false);
            }
            return null;
        }
        return knownNames.contains(stripped) ? new BoundaryMatch(stripped, false) : null;
    }

    private static Map<Integer, IElementType> resolveClassification(List<MarkerCandidate> candidates) {
        // "Last close wins": for each declared boundary, the LAST `--<name>--` line is the
        // real close. Earlier closing-shaped lines are part-body text (e.g. text/plain content
        // that literally quotes the boundary). Opening-shaped lines whose offset precedes the
        // resolved close become BOUNDARY_START; opens at/after the close are body.
        var closeOffsets = new HashMap<String, Integer>();
        for (var candidate : candidates) {
            if (candidate.closing()) {
                closeOffsets.put(candidate.name(), candidate.offset());
            }
        }
        var classification = new HashMap<Integer, IElementType>();
        for (var candidate : candidates) {
            var closeOffset = closeOffsets.get(candidate.name());
            if (closeOffset == null) {
                if (!candidate.closing()) {
                    classification.put(candidate.offset(), EmlTokenTypes.BOUNDARY_START);
                }
            } else if (candidate.closing()) {
                if (candidate.offset() == closeOffset) {
                    classification.put(candidate.offset(), EmlTokenTypes.BOUNDARY_END);
                }
            } else if (candidate.offset() < closeOffset) {
                classification.put(candidate.offset(), EmlTokenTypes.BOUNDARY_START);
            }
        }
        return classification;
    }

    public boolean isEmpty() {
        return rawNames.isEmpty();
    }

    public @Nullable IElementType classifyBoundary(int lineStartOffset) {
        return classification.get(lineStartOffset);
    }

    // True when `offset` lies in the top-level (preamble) header block of a MULTIPART message, i.e.
    // strictly before the first boundary marker. Used to confine the tolerant stray-blank rescue (see
    // EmlLexer.advance) to that region. A message with no boundary marker at all returns false for every
    // offset: there is no structural anchor proving where the header section ends, so a non-multipart
    // message keeps the strict RFC 5322 rule (the first blank line is the header/body separator) and a
    // header-shaped body line is never re-promoted to a header.
    public boolean isBeforeFirstBoundary(int offset) {
        return firstBoundaryOffset != Integer.MAX_VALUE && offset < firstBoundaryOffset;
    }

    public Set<String> rawNames() {
        return rawNames;
    }

    public boolean isRfc822HeaderStart(int lineOffset) {
        return rfc822HeaderStartOffsets.contains(lineOffset);
    }

    private static boolean isRfc822Value(CharSequence accumulatedValue) {
        var value = accumulatedValue.toString();
        var separator = value.indexOf(';');
        if (separator >= 0) {
            value = value.substring(0, separator);
        }
        return value.trim().equalsIgnoreCase(RFC822_TYPE);
    }

    private static void addBoundary(Set<String> sink, String name) {
        if (!sink.add(name) && LOG.isDebugEnabled()) {
            LOG.debug("Duplicate MIME boundary declaration ignored: " + name);
        }
    }

    // Bounded, allocation-free mirror of EmlLexer.blankLineOpensAnotherHeaderBlock, kept in lock-step
    // so the parser and the lexer agree on which blank lines end the header section. `lineEndOfBlank`
    // is the index of the blank line's '\n' (or bufferLength when it has no trailing newline). Starting
    // at the line after the blank, it skips further consecutive blank lines, then walks the run of
    // header-shaped / continuation lines and reports whether that run is terminated by another blank
    // line or by EOF (a proper RFC 5322 header block, rfc5322 §2.2). A run terminated by a boundary
    // marker or by real (non-header-shaped) body text is part body, not headers, so it returns false
    // and the blank is treated as the genuine separator — `boundary=` / `message/rfc822` declarations
    // in such body text are then never harvested.
    private static boolean blankLineOpensAnotherHeaderBlock(CharSequence text, int lineEndOfBlank, int bufferLength) {
        if (lineEndOfBlank >= bufferLength) {
            return false;
        }
        var lineStart = lineEndOfBlank + 1;
        var sawHeaderLine = false;
        while (lineStart < bufferLength) {
            var lineEnd = lineStart;
            while (lineEnd < bufferLength && text.charAt(lineEnd) != '\n') {
                lineEnd++;
            }
            var contentEnd = lineEnd;
            while (contentEnd > lineStart && Character.isWhitespace(text.charAt(contentEnd - 1))) {
                contentEnd--;
            }
            if (contentEnd == lineStart) {
                if (!sawHeaderLine) {
                    if (lineEnd >= bufferLength) {
                        return false;
                    }
                    lineStart = lineEnd + 1;
                    continue;
                }
                return true;
            }
            var firstChar = text.charAt(lineStart);
            var continuationLine = firstChar == ' ' || firstChar == '\t';
            if (continuationLine) {
                if (!sawHeaderLine) {
                    return false;
                }
            } else if (isHeaderFieldLine(text, lineStart, lineEnd)) {
                sawHeaderLine = true;
            } else {
                return false;
            }
            lineStart = lineEnd >= bufferLength ? lineEnd : lineEnd + 1;
        }
        return sawHeaderLine;
    }

    // RFC 5322 §2.2 / §3.6.8: field-name = 1*ftext, ftext = %d33-57 / %d59-126 (printable US-ASCII
    // excluding ':'), followed immediately by ':'. Leading SP/TAB marks a continuation line, not a
    // field start. Stops at the first non-ftext char, so it is O(1) on body lines.
    private static boolean isHeaderFieldLine(CharSequence text, int start, int end) {
        if (start >= end) {
            return false;
        }
        var first = text.charAt(start);
        if (first == ' ' || first == '\t') {
            return false;
        }
        for (var index = start; index < end; index++) {
            var character = text.charAt(index);
            if (character == ':') {
                return index > start;
            }
            if (character < 0x21 || character > 0x7E) {
                return false;
            }
        }
        return false;
    }

    private record MarkerCandidate(int offset, String name, boolean closing) {}

    private record BoundaryMatch(String name, boolean closing) {}
}
