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

    private EmlBoundaryParser(
            Set<String> rawNames, Map<Integer, IElementType> classification, Set<Integer> rfc822HeaderStartOffsets) {
        this.rawNames = rawNames;
        this.classification = classification;
        this.rfc822HeaderStartOffsets = rfc822HeaderStartOffsets;
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
            var line = text.subSequence(lineStart, lineEnd).toString().stripTrailing();

            var match = matchBoundary(line, rawNames);
            if (match != null) {
                if (pendingContentTypeStart >= 0 && isRfc822Value(pendingContentTypeValue)) {
                    rfc822HeaderStartOffsets.add(pendingContentTypeStart);
                }
                pendingContentTypeStart = -1;
                pendingContentTypeValue.setLength(0);
                candidates.add(new MarkerCandidate(lineStart, match.name(), match.closing()));
                inHeader = !match.closing();
                rfc822Pending = false;
            } else if (inHeader) {
                if (line.isEmpty()) {
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
                    } else {
                        inHeader = false;
                    }
                } else {
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

    private static @Nullable BoundaryMatch matchBoundary(String line, Set<String> knownNames) {
        if (knownNames.isEmpty() || !line.startsWith("--")) {
            return null;
        }
        var stripped = line.substring(2);
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

    private record MarkerCandidate(int offset, String name, boolean closing) {}

    private record BoundaryMatch(String name, boolean closing) {}
}
