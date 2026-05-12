package com.github.ttereshchenko.mailkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

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
        assertNotNull(EmlFileType.INSTANCE.getIcon());
    }
}
