package com.github.ttereshchenko.mailkit.attachment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the dangerous-extension guard behind the "Open Attachment with System App" confirmation.
 * Manual-verification sample: {@code src/test/resources/samples/eml/edge/executable_attachment.eml}.
 */
class DangerousAttachmentExtensionsTest {

    @Test
    void flagsExecutableAndScriptExtensions() {
        assertTrue(DangerousAttachmentExtensions.isDangerous("setup.exe"));
        assertTrue(DangerousAttachmentExtensions.isDangerous("payload.js"));
        assertTrue(DangerousAttachmentExtensions.isDangerous("report.html"));
        assertTrue(DangerousAttachmentExtensions.isDangerous("budget.docm"));
        assertTrue(DangerousAttachmentExtensions.isDangerous("install.sh"));
    }

    @Test
    void flagsDoubleExtensionDisguise() {
        assertTrue(DangerousAttachmentExtensions.isDangerous("invoice.pdf.exe"));
    }

    @Test
    void isCaseInsensitive() {
        assertTrue(DangerousAttachmentExtensions.isDangerous("SETUP.EXE"));
        assertTrue(DangerousAttachmentExtensions.isDangerous("Report.HtMl"));
    }

    @Test
    void treatsCommonDocumentTypesAsSafe() {
        assertFalse(DangerousAttachmentExtensions.isDangerous("invoice.pdf"));
        assertFalse(DangerousAttachmentExtensions.isDangerous("photo.png"));
        assertFalse(DangerousAttachmentExtensions.isDangerous("notes.txt"));
        assertFalse(DangerousAttachmentExtensions.isDangerous("archive.zip"));
    }

    @Test
    void handlesNamesWithoutUsableExtension() {
        assertFalse(DangerousAttachmentExtensions.isDangerous("README"));
        assertFalse(DangerousAttachmentExtensions.isDangerous("trailingdot."));
        assertFalse(DangerousAttachmentExtensions.isDangerous(null));
        assertFalse(DangerousAttachmentExtensions.isDangerous(""));
    }
}
