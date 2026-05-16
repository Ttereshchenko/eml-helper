package com.github.ttereshchenko.mailkit.inspections.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeaderRequirementRuleTest {

    @Test
    void reportsBothRequiredHeadersWhenAbsent() {
        assertEquals(List.of("From", "Date"), HeaderRequirementRule.missing(List.of()));
    }

    @Test
    void reportsOnlyFromWhenDatePresent() {
        assertEquals(List.of("From"), HeaderRequirementRule.missing(List.of("Date", "Subject")));
    }

    @Test
    void matchesCaseInsensitive() {
        assertTrue(HeaderRequirementRule.missing(List.of("FROM", "date")).isEmpty());
    }

    @Test
    void ignoresNullEntries() {
        assertEquals(List.of("From"), HeaderRequirementRule.missing(java.util.Arrays.asList(null, "Date")));
    }
}
