package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AttachmentTest {

    // F12 regression: the old check compared PR_ATTACH_FLAGS against 0x7FFE — the property *tag* of
    // PR_ATTACHMENT_HIDDEN, never a flag value — and ignored the real hidden property entirely.
    @Test
    void hiddenAttachmentIsInline() {
        assertTrue(Attachment.isInline(null, Boolean.TRUE, null));
        assertFalse(Attachment.isInline(null, Boolean.FALSE, null));
    }

    @Test
    void attachFlagsMarkInline() {
        assertTrue(Attachment.isInline(null, null, 0x4), "ATT_MHTML_REF means cid-referenced");
        assertTrue(Attachment.isInline(null, null, 0x1), "ATT_INVISIBLE_IN_HTML means hidden inline part");
        assertFalse(Attachment.isInline(null, null, 0x2), "ATT_INVISIBLE_IN_RTF alone is not inline");
        assertFalse(Attachment.isInline(null, null, 0x7FFE & ~0x5), "0x7FFE is a property tag, not a flag value");
    }

    @Test
    void explicitDispositionWins() {
        assertTrue(Attachment.isInline("inline", null, null));
        assertTrue(Attachment.isInline(" INLINE ", null, null));
        assertFalse(Attachment.isInline("attachment", Boolean.TRUE, 0x4), "explicit disposition overrides the rest");
    }

    @Test
    void nothingSetMeansRegularAttachment() {
        assertFalse(Attachment.isInline(null, null, null));
        assertFalse(Attachment.isInline(42, "not-a-bool", "not-an-int"));
    }

    // The protected no-arg constructor is the documented test seam and leaves no property context;
    // the size/content-location getters must tolerate that, since converter stubs rely on them.
    @Test
    void sizeAndContentLocationAreNullSafeWithoutPropertyContext() {
        var bare = new Attachment() {};
        assertNull(bare.getSize());
        assertNull(bare.getContentLocation());
    }
}
