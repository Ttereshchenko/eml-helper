package com.github.ttereshchenko.mailkit.inspections.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Detects required RFC 5322 headers that are missing from a message's header block. */
public final class HeaderRequirementRule {

    public static final List<String> REQUIRED_HEADERS = List.of("From", "Date");

    private HeaderRequirementRule() {}

    public static List<String> missing(Collection<String> presentHeaderNames) {
        var lower = new java.util.HashSet<String>();
        for (var name : presentHeaderNames) {
            if (name != null) {
                lower.add(name.toLowerCase(Locale.ROOT));
            }
        }
        var missing = new ArrayList<String>();
        for (var required : REQUIRED_HEADERS) {
            if (!lower.contains(required.toLowerCase(Locale.ROOT))) {
                missing.add(required);
            }
        }
        return missing;
    }
}
