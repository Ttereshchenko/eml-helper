package com.github.ttereshchenko.emlhelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.intellij.psi.tree.IElementType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EmlTokenTypesTest {

    @Test
    void testAllTokenTypesNonNull() {
        assertNotNull(EmlTokenTypes.HEADER_LINE);
        assertNotNull(EmlTokenTypes.BLANK_LINE);
        assertNotNull(EmlTokenTypes.BOUNDARY_START);
        assertNotNull(EmlTokenTypes.BOUNDARY_END);
        assertNotNull(EmlTokenTypes.BODY_LINE);
    }

    @Test
    void testFileElementTypeNonNull() {
        assertNotNull(EmlTokenTypes.FILE);
    }

    @Test
    void testTokenTypesAreDistinct() {
        Set<IElementType> types = Set.of(
                EmlTokenTypes.HEADER_LINE,
                EmlTokenTypes.BLANK_LINE,
                EmlTokenTypes.BOUNDARY_START,
                EmlTokenTypes.BOUNDARY_END,
                EmlTokenTypes.BODY_LINE);
        assertEquals(5, types.size());
    }
}
