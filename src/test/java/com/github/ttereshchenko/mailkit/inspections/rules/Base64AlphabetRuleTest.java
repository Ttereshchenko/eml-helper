package com.github.ttereshchenko.mailkit.inspections.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Base64AlphabetRuleTest {

    @Test
    void cleanInputReturnsNegative() {
        assertEquals(-1, Base64AlphabetRule.firstInvalid("SGVsbG8="));
    }

    @Test
    void whitespaceAndCrlfAccepted() {
        assertEquals(-1, Base64AlphabetRule.firstInvalid("SGVs\nbG8=\r\n"));
    }

    @Test
    void firstInvalidPointsAtTheBadChar() {
        var input = "SGV*sbG8=";
        assertEquals(3, Base64AlphabetRule.firstInvalid(input));
    }

    @Test
    void invalidRunsCoalesced() {
        var input = "AB**CD!!EF";
        var ranges = Base64AlphabetRule.invalidRuns(input);
        assertEquals(2, ranges.size());
        assertEquals(2, ranges.get(0).startOffset());
        assertEquals(4, ranges.get(0).endOffset());
        assertEquals(6, ranges.get(1).startOffset());
        assertEquals(8, ranges.get(1).endOffset());
    }

    @Test
    void emptyInputProducesNoFindings() {
        assertTrue(Base64AlphabetRule.invalidRuns("").isEmpty());
    }
}
