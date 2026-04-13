package com.github.ttereshchenko.emlhelper.highlighting;

import com.github.ttereshchenko.emlhelper.EmlTokenTypes;
import com.github.ttereshchenko.emlhelper.lexer.EmlLexer;
import com.github.ttereshchenko.emlhelper.settings.EmlHeaderSettings;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class EmlSyntaxHighlighterTest extends BasePlatformTestCase {

    private EmlSyntaxHighlighter highlighter;
    private boolean originalHighlightingEnabled;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        highlighter = new EmlSyntaxHighlighter();
        originalHighlightingEnabled = EmlHeaderSettings.getInstance().isHighlightingEnabled();
        EmlHeaderSettings.getInstance().setHighlightingEnabled(true);
    }

    @Override
    protected void tearDown() throws Exception {
        EmlHeaderSettings.getInstance().setHighlightingEnabled(originalHighlightingEnabled);
        super.tearDown();
    }

    public void testGetHighlightingLexer() {
        assertInstanceOf(highlighter.getHighlightingLexer(), EmlLexer.class);
    }

    public void testBoundaryStartHighlight() {
        TextAttributesKey[] keys = highlighter.getTokenHighlights(EmlTokenTypes.BOUNDARY_START);
        assertEquals(1, keys.length);
        assertEquals(EmlSyntaxHighlighter.BOUNDARY_KEY, keys[0]);
    }

    public void testBoundaryEndHighlight() {
        TextAttributesKey[] keys = highlighter.getTokenHighlights(EmlTokenTypes.BOUNDARY_END);
        assertEquals(1, keys.length);
        assertEquals(EmlSyntaxHighlighter.BOUNDARY_KEY, keys[0]);
    }

    public void testHeaderLineHighlight() {
        TextAttributesKey[] keys = highlighter.getTokenHighlights(EmlTokenTypes.HEADER_LINE);
        assertEquals(1, keys.length);
        assertEquals(EmlSyntaxHighlighter.HEADER_KEY, keys[0]);
    }

    public void testBodyLineNoHighlight() {
        TextAttributesKey[] keys = highlighter.getTokenHighlights(EmlTokenTypes.BODY_LINE);
        assertEquals(0, keys.length);
    }

    public void testBlankLineNoHighlight() {
        TextAttributesKey[] keys = highlighter.getTokenHighlights(EmlTokenTypes.BLANK_LINE);
        assertEquals(0, keys.length);
    }

    public void testHighlightingDisabledReturnsEmpty() {
        EmlHeaderSettings.getInstance().setHighlightingEnabled(false);

        assertEquals(0, highlighter.getTokenHighlights(EmlTokenTypes.BOUNDARY_START).length);
        assertEquals(0, highlighter.getTokenHighlights(EmlTokenTypes.BOUNDARY_END).length);
        assertEquals(0, highlighter.getTokenHighlights(EmlTokenTypes.HEADER_LINE).length);
        assertEquals(0, highlighter.getTokenHighlights(EmlTokenTypes.BODY_LINE).length);
        assertEquals(0, highlighter.getTokenHighlights(EmlTokenTypes.BLANK_LINE).length);
    }
}
