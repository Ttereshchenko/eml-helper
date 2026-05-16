package com.github.ttereshchenko.mailkit.inspections.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DateParseRuleTest {

    @Test
    void parsesCanonicalRfc2822() {
        assertTrue(DateParseRule.tryParse("Mon, 1 Jan 2024 10:00:00 +0000").isPresent());
    }

    @Test
    void parsesWithoutDayOfWeek() {
        assertTrue(DateParseRule.tryParse("1 Jan 2024 10:00:00 +0000").isPresent());
    }

    @Test
    void parsesWithoutSeconds() {
        assertTrue(DateParseRule.tryParse("Mon, 1 Jan 2024 10:00 +0000").isPresent());
    }

    @Test
    void rejectsGarbage() {
        assertFalse(DateParseRule.tryParse("not-a-date").isPresent());
    }

    @Test
    void rejectsBlank() {
        assertFalse(DateParseRule.tryParse("   ").isPresent());
    }

    @Test
    void rejectsNull() {
        assertFalse(DateParseRule.tryParse(null).isPresent());
    }

    @Test
    void formatNowProducesParseableOutput() {
        var now = DateParseRule.formatNow();
        assertNotNull(now);
        assertTrue(DateParseRule.tryParse(now).isPresent());
    }
}
