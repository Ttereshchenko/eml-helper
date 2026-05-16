package com.github.ttereshchenko.mailkit.inspections.rules;

import java.util.Locale;
import java.util.Set;

/**
 * Recognises the five canonical {@code Content-Transfer-Encoding} values defined
 * by RFC 2045 §6.1. Anything else is "unknown" — even if the existing
 * {@link com.github.ttereshchenko.mailkit.attachment.ContentTransferEncoding}
 * parser silently maps it to 7bit.
 */
public final class KnownEncodingRule {

    public static final Set<String> KNOWN = Set.of("7bit", "8bit", "binary", "quoted-printable", "base64");

    public static final String DEFAULT_REPLACEMENT = "8bit";

    private KnownEncodingRule() {}

    public static boolean isKnown(String value) {
        if (value == null) {
            return false;
        }
        return KNOWN.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
