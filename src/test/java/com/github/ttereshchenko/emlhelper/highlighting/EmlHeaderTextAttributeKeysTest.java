package com.github.ttereshchenko.emlhelper.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class EmlHeaderTextAttributeKeysTest {

    // ===== Positive Tests =====

    @Test
    void testGetKeyFrom() {
        assertSame(EmlHeaderTextAttributeKeys.HEADER_FROM, EmlHeaderTextAttributeKeys.getKey("From"));
    }

    @Test
    void testGetKeyTo() {
        assertSame(EmlHeaderTextAttributeKeys.HEADER_TO, EmlHeaderTextAttributeKeys.getKey("To"));
    }

    @Test
    void testGetKeySubject() {
        assertSame(EmlHeaderTextAttributeKeys.HEADER_SUBJECT, EmlHeaderTextAttributeKeys.getKey("Subject"));
    }

    @Test
    void testGetKeyDate() {
        assertSame(EmlHeaderTextAttributeKeys.HEADER_DATE, EmlHeaderTextAttributeKeys.getKey("Date"));
    }

    @Test
    void testGetKeyCc() {
        assertSame(EmlHeaderTextAttributeKeys.HEADER_CC, EmlHeaderTextAttributeKeys.getKey("Cc"));
    }

    @Test
    void testGetKeyBcc() {
        assertSame(EmlHeaderTextAttributeKeys.HEADER_BCC, EmlHeaderTextAttributeKeys.getKey("Bcc"));
    }

    @Test
    void testCaseInsensitivityLowercase() {
        assertSame(EmlHeaderTextAttributeKeys.HEADER_FROM, EmlHeaderTextAttributeKeys.getKey("from"));
    }

    @Test
    void testCaseInsensitivityUppercase() {
        assertSame(EmlHeaderTextAttributeKeys.HEADER_FROM, EmlHeaderTextAttributeKeys.getKey("FROM"));
    }

    @Test
    void testCaseInsensitivityMixed() {
        assertSame(EmlHeaderTextAttributeKeys.HEADER_FROM, EmlHeaderTextAttributeKeys.getKey("fRoM"));
    }

    @Test
    void testDynamicKeyCreation() {
        TextAttributesKey key = EmlHeaderTextAttributeKeys.getKey("Content-Type");
        assertNotNull(key);
        assertEquals("EML_HEADER_CONTENT-TYPE", key.getExternalName());
    }

    @Test
    void testDynamicKeyCaching() {
        TextAttributesKey first = EmlHeaderTextAttributeKeys.getKey("X-Custom-Header");
        TextAttributesKey second = EmlHeaderTextAttributeKeys.getKey("X-Custom-Header");
        assertSame(first, second);
    }

    @Test
    void testDynamicKeyCaseInsensitive() {
        TextAttributesKey lower = EmlHeaderTextAttributeKeys.getKey("x-mailer");
        TextAttributesKey upper = EmlHeaderTextAttributeKeys.getKey("X-MAILER");
        assertSame(lower, upper);
    }

    @Test
    void testDynamicKeyPrefix() {
        TextAttributesKey key = EmlHeaderTextAttributeKeys.getKey("Reply-To");
        assertTrue(key.getExternalName().startsWith("EML_HEADER_"));
    }

    // ===== Negative Tests =====

    @Test
    void testEmptyStringHeaderName() {
        assertDoesNotThrow(() -> EmlHeaderTextAttributeKeys.getKey(""));
    }

    private void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
