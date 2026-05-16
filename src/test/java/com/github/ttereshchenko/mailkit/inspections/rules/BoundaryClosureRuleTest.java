package com.github.ttereshchenko.mailkit.inspections.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoundaryClosureRuleTest {

    @Test
    void closingMarkerDetected() {
        assertTrue(BoundaryClosureRule.hasClosingMarker("--abc\nbody\n--abc--\n", "abc"));
    }

    @Test
    void closingMarkerDetectedWithoutFinalNewline() {
        assertTrue(BoundaryClosureRule.hasClosingMarker("--abc\nbody\n--abc--", "abc"));
    }

    @Test
    void closingMarkerWithTransportPaddingAccepted() {
        assertTrue(BoundaryClosureRule.hasClosingMarker("--abc--   \nrest\n", "abc"));
    }

    @Test
    void missingClosingMarkerReported() {
        assertFalse(BoundaryClosureRule.hasClosingMarker("--abc\nbody\n--abc\nmore\n", "abc"));
    }

    @Test
    void differentBoundaryDoesNotCount() {
        assertFalse(BoundaryClosureRule.hasClosingMarker("--xyz--\n", "abc"));
    }

    @Test
    void trailingCharactersDisqualifyMatch() {
        assertFalse(BoundaryClosureRule.hasClosingMarker("--abc--x\n", "abc"));
    }
}
