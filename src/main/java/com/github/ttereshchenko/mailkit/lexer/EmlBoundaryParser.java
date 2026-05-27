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

    private static final Pattern BOUNDARY_PATTERN =
            Pattern.compile("boundary\\s*=\\s*\"?([^\"\\s;]+)\"?", Pattern.CASE_INSENSITIVE);

    private static final String CONTENT_TYPE_PREFIX = "content-type";
    private static final String RFC822_TYPE = "message/rfc822";

    private static final EmlBoundaryParser EMPTY =
            new EmlBoundaryParser(Collections.emptySet(), Collections.emptyMap());

    private final Set<String> rawNames;
    private final Map<Integer, IElementType> classification;

    private EmlBoundaryParser(Set<String> rawNames, Map<Integer, IElementType> classification) {
        this.rawNames = rawNames;
        this.classification = classification;
    }

    public static EmlBoundaryParser collect(CharSequence text) {
        Objects.requireNonNull(text, "text");
        var bufferLength = text.length();
        if (bufferLength == 0) {
            return EMPTY;
        }

        var rawNames = new HashSet<String>();
        var candidates = new ArrayList<MarkerCandidate>();
        var inHeader = true;
        var rfc822Pending = false;
        var lineStart = 0;

        while (lineStart < bufferLength) {
            var lineEnd = lineStart;
            while (lineEnd < bufferLength && text.charAt(lineEnd) != '\n') {
                lineEnd++;
            }
            var line = text.subSequence(lineStart, lineEnd).toString().stripTrailing();

            var match = matchBoundary(line, rawNames);
            if (match != null) {
                candidates.add(new MarkerCandidate(lineStart, match.name(), match.closing()));
                inHeader = !match.closing();
                rfc822Pending = false;
            } else if (inHeader) {
                if (line.isEmpty()) {
                    if (rfc822Pending) {
                        rfc822Pending = false;
                    } else {
                        inHeader = false;
                    }
                } else {
                    var matcher = BOUNDARY_PATTERN.matcher(line);
                    while (matcher.find()) {
                        addBoundary(rawNames, matcher.group(1));
                    }
                    var firstChar = line.charAt(0);
                    var continuationLine = firstChar == ' ' || firstChar == '\t';
                    if (!rfc822Pending && !continuationLine && isContentTypeRfc822(line)) {
                        rfc822Pending = true;
                    }
                }
            }

            lineStart = (lineEnd < bufferLength) ? lineEnd + 1 : lineEnd;
        }

        if (rawNames.isEmpty()) {
            return EMPTY;
        }

        return new EmlBoundaryParser(
                Collections.unmodifiableSet(rawNames), Collections.unmodifiableMap(resolveClassification(candidates)));
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

    static boolean isContentTypeRfc822(String line) {
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

    private static void addBoundary(Set<String> sink, String name) {
        if (!sink.add(name) && LOG.isDebugEnabled()) {
            LOG.debug("Duplicate MIME boundary declaration ignored: " + name);
        }
    }

    private record MarkerCandidate(int offset, String name, boolean closing) {}

    private record BoundaryMatch(String name, boolean closing) {}
}
