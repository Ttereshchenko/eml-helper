package com.github.ttereshchenko.mailkit.inspections.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KnownEncodingRuleTest {

    @Test
    void recognisesCanonicalEncodings() {
        for (var value : KnownEncodingRule.KNOWN) {
            assertTrue(KnownEncodingRule.isKnown(value));
            assertTrue(KnownEncodingRule.isKnown(value.toUpperCase(java.util.Locale.ROOT)));
        }
    }

    @Test
    void trimsWhitespace() {
        assertTrue(KnownEncodingRule.isKnown("  base64  "));
    }

    @Test
    void rejectsUnknown() {
        assertFalse(KnownEncodingRule.isKnown("rot13"));
        assertFalse(KnownEncodingRule.isKnown(""));
        assertFalse(KnownEncodingRule.isKnown(null));
    }
}
