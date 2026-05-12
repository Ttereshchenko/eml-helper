package com.github.ttereshchenko.mailkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class EmlLanguageTest {

    @Test
    void testInstance() {
        assertNotNull(EmlLanguage.INSTANCE);
    }

    @Test
    void testLanguageId() {
        assertEquals("EML", EmlLanguage.INSTANCE.getID());
    }
}
