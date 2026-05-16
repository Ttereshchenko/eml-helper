package com.github.ttereshchenko.mailkit.inspections.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DuplicateHeaderRuleTest {

    @Test
    void noDuplicatesProducesEmptyList() {
        assertTrue(DuplicateHeaderRule.duplicateIndices(List.of("From", "Date"), "Message-ID")
                .isEmpty());
    }

    @Test
    void detectsLaterOccurrencesOnly() {
        var indices = DuplicateHeaderRule.duplicateIndices(
                List.of("Message-ID", "Date", "Message-ID", "Message-ID"), "Message-ID");
        assertEquals(List.of(2, 3), indices);
    }

    @Test
    void caseInsensitive() {
        var indices = DuplicateHeaderRule.duplicateIndices(List.of("message-id", "Message-ID"), "MESSAGE-ID");
        assertEquals(List.of(1), indices);
    }

    @Test
    void duplicateNamesListsEachOnce() {
        var dupes = DuplicateHeaderRule.duplicateNames(List.of("From", "From", "To", "To", "Date"));
        assertEquals(List.of("from", "to"), dupes);
    }
}
