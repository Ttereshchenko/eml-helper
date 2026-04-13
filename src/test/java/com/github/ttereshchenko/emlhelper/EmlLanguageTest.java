package com.github.ttereshchenko.emlhelper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
