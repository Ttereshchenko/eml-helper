package com.github.ttereshchenko.emlhelper.lexer;

import com.github.ttereshchenko.emlhelper.EmlTokenTypes;
import com.intellij.psi.tree.TokenSet;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class EmlParserDefinitionTest extends BasePlatformTestCase {

    private EmlParserDefinition parserDefinition;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        parserDefinition = new EmlParserDefinition();
    }

    public void testCreateLexer() {
        assertInstanceOf(parserDefinition.createLexer(getProject()), EmlLexer.class);
    }

    public void testGetFileNodeType() {
        assertSame(EmlTokenTypes.FILE, parserDefinition.getFileNodeType());
    }

    public void testWhitespaceTokensEmpty() {
        assertEquals(TokenSet.EMPTY, parserDefinition.getWhitespaceTokens());
    }

    public void testCommentTokensEmpty() {
        assertEquals(TokenSet.EMPTY, parserDefinition.getCommentTokens());
    }

    public void testStringLiteralElementsEmpty() {
        assertEquals(TokenSet.EMPTY, parserDefinition.getStringLiteralElements());
    }
}
