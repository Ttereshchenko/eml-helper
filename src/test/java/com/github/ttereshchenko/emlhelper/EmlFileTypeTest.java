package com.github.ttereshchenko.emlhelper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmlFileTypeTest {

    @Test
    void testSingleton() {
        assertNotNull(EmlFileType.INSTANCE);
    }

    @Test
    void testGetName() {
        assertEquals("EML", EmlFileType.INSTANCE.getName());
    }

    @Test
    void testGetDescription() {
        assertEquals("EML email message file", EmlFileType.INSTANCE.getDescription());
    }

    @Test
    void testGetDefaultExtension() {
        assertEquals("eml", EmlFileType.INSTANCE.getDefaultExtension());
    }

    @Test
    void testGetIcon() {
        assertNull(EmlFileType.INSTANCE.getIcon());
    }
}
