package com.github.ttereshchenko.emlhelper.lexer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class EmlBoundaryParser {
    private static final Pattern BOUNDARY_PATTERN =
            Pattern.compile("boundary\\s*=\\s*\"?([^\"\\s;]+)\"?", Pattern.CASE_INSENSITIVE);

    private static final EmlBoundaryParser EMPTY =
            new EmlBoundaryParser(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

    private final Set<String> rawNames;
    private final Set<String> startMarkers;
    private final Set<String> endMarkers;

    private EmlBoundaryParser(Set<String> rawNames, Set<String> startMarkers, Set<String> endMarkers) {
        this.rawNames = rawNames;
        this.startMarkers = startMarkers;
        this.endMarkers = endMarkers;
    }

    public static EmlBoundaryParser collect(CharSequence text) {
        Objects.requireNonNull(text, "text");
        var matcher = BOUNDARY_PATTERN.matcher(text);
        if (!matcher.find()) {
            return EMPTY;
        }
        var rawNames = new HashSet<String>();
        rawNames.add(matcher.group(1));
        while (matcher.find()) {
            rawNames.add(matcher.group(1));
        }
        var startMarkers = HashSet.<String>newHashSet(rawNames.size());
        var endMarkers = HashSet.<String>newHashSet(rawNames.size());
        for (var name : rawNames) {
            startMarkers.add("--" + name);
            endMarkers.add("--" + name + "--");
        }
        return new EmlBoundaryParser(
                Collections.unmodifiableSet(rawNames),
                Collections.unmodifiableSet(startMarkers),
                Collections.unmodifiableSet(endMarkers));
    }

    public boolean isEmpty() {
        return rawNames.isEmpty();
    }

    public boolean isEnd(String line) {
        return endMarkers.contains(line);
    }

    public boolean isStart(String line) {
        return startMarkers.contains(line);
    }

    public Set<String> rawNames() {
        return rawNames;
    }
}
