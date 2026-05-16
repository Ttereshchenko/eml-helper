package com.github.ttereshchenko.mailkit.inspections.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Detects header names that appear more than once in a given block. */
public final class DuplicateHeaderRule {

    private DuplicateHeaderRule() {}

    public static List<Integer> duplicateIndices(List<String> headerNames, String target) {
        var result = new ArrayList<Integer>();
        if (headerNames == null || target == null) {
            return result;
        }
        var lowerTarget = target.toLowerCase(Locale.ROOT);
        var seen = false;
        for (var idx = 0; idx < headerNames.size(); idx++) {
            var name = headerNames.get(idx);
            if (name != null && name.toLowerCase(Locale.ROOT).equals(lowerTarget)) {
                if (seen) {
                    result.add(idx);
                } else {
                    seen = true;
                }
            }
        }
        return result;
    }

    public static List<String> duplicateNames(List<String> headerNames) {
        var result = new ArrayList<String>();
        if (headerNames == null) {
            return result;
        }
        var seen = new HashSet<String>();
        var reported = new HashSet<String>();
        for (var name : headerNames) {
            if (name == null) {
                continue;
            }
            var lower = name.toLowerCase(Locale.ROOT);
            if (!seen.add(lower) && reported.add(lower)) {
                result.add(lower);
            }
        }
        return result;
    }
}
