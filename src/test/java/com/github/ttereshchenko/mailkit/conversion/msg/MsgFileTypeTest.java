package com.github.ttereshchenko.mailkit.conversion.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MsgFileTypeTest {

    @Test
    void singletonExposed() {
        assertNotNull(MsgFileType.INSTANCE);
    }

    @Test
    void nameAndDescription() {
        assertEquals("MSG", MsgFileType.INSTANCE.getName());
        assertEquals("Outlook MSG email message file", MsgFileType.INSTANCE.getDescription());
    }

    @Test
    void iconLoaded() {
        assertNotNull(MsgFileType.INSTANCE.getIcon());
    }

    @Test
    void reportsBinary() {
        assertTrue(MsgFileType.INSTANCE.isBinary());
    }
}
