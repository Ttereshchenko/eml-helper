package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AttachmentBudget} — the shared per-message cap that bounds how much attachment data
 * a conversion buffers in memory (audit findings M1/L1). The byte budget accumulates across calls so a
 * single instance threaded through embedded-message recursion bounds the whole message tree.
 */
class AttachmentBudgetTest {

    @Test
    void countLimitIsReachedExactlyAtMaxAttachments() {
        var budget = new AttachmentBudget();
        for (var index = 0; index < AttachmentBudget.MAX_ATTACHMENT_COUNT; index++) {
            assertFalse(budget.atCountLimit(), "count limit hit early at " + index);
            budget.recordAttachment();
        }
        assertTrue(budget.atCountLimit(), "count limit not reached after MAX_ATTACHMENT_COUNT records");
    }

    @Test
    void recordBytesReportsExceededOnlyOncePastTheCap() {
        var budget = new AttachmentBudget();
        // Exactly at the cap is not "over"; the next byte tips it over.
        assertFalse(budget.recordBytes(AttachmentBudget.MAX_TOTAL_ATTACHMENT_BYTES));
        assertTrue(budget.recordBytes(1));
    }

    @Test
    void byteBudgetAccumulatesAcrossCallsAsASharedRunningTotal() {
        var budget = new AttachmentBudget();
        var half = AttachmentBudget.MAX_TOTAL_ATTACHMENT_BYTES / 2;
        assertFalse(budget.recordBytes(half), "first half should fit");
        assertFalse(budget.recordBytes(half), "two halves reach but do not exceed the cap");
        // A separate accounting per call would never trip; the running total does — this is what lets one
        // budget instance bound an embedded-message tree rather than each level independently.
        assertTrue(budget.recordBytes(1));
    }

    @Test
    void maxTotalMegabytesMatchesTheByteCap() {
        assertEquals(AttachmentBudget.MAX_TOTAL_ATTACHMENT_BYTES / (1024 * 1024), AttachmentBudget.maxTotalMegabytes());
    }
}
