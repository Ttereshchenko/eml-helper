package com.github.ttereshchenko.mailkit.inspections.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoundaryQuotingRuleTest {

    @Test
    void simpleAlnumBoundaryNeedsNoQuoting() {
        assertNull(BoundaryQuotingRule.scan("multipart/mixed; boundary=abc123"));
    }

    @Test
    void quotedBoundaryIsAccepted() {
        assertNull(BoundaryQuotingRule.scan("multipart/mixed; boundary=\"a b\""));
    }

    @Test
    void unquotedSpaceTriggersDetection() {
        var input = "multipart/mixed; boundary=has space; charset=utf-8";
        var hit = BoundaryQuotingRule.scan(input);
        assertNotNull(hit);
        assertEquals("has space", hit.value());
        assertEquals(input.substring(hit.valueStart(), hit.valueEnd()), hit.value());
    }

    @Test
    void unquotedSemicolonSafeButTspecialPresent() {
        var hit = BoundaryQuotingRule.scan("multipart/mixed; boundary=a(b)c");
        assertNotNull(hit);
        assertEquals("a(b)c", hit.value());
    }

    @Test
    void requiresQuotingDetectsTspecials() {
        assertTrue(BoundaryQuotingRule.requiresQuoting("a b"));
        assertTrue(BoundaryQuotingRule.requiresQuoting("a;b"));
        assertFalse(BoundaryQuotingRule.requiresQuoting("abc123_-"));
    }
}
