package com.github.ttereshchenko.mailkit.highlighting;

import com.github.ttereshchenko.mailkit.EmlFileType;
import com.github.ttereshchenko.mailkit.EmlLanguage;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class EmlSyntaxHighlighterFactoryTest extends BasePlatformTestCase {

    public void testFactoryReturnsEmlSyntaxHighlighter() {
        var factory = new EmlSyntaxHighlighterFactory();
        var highlighter = factory.getSyntaxHighlighter(getProject(), null);
        assertNotNull(highlighter);
        assertInstanceOf(highlighter, EmlSyntaxHighlighter.class);
    }

    public void testFactoryRegisteredForEmlLanguage() {
        var highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(EmlLanguage.INSTANCE, getProject(), null);
        assertNotNull(highlighter);
        assertInstanceOf(highlighter, EmlSyntaxHighlighter.class);
    }

    public void testFactoryRegisteredForEmlFileType() {
        var highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(EmlFileType.INSTANCE, getProject(), null);
        assertNotNull(highlighter);
        assertInstanceOf(highlighter, EmlSyntaxHighlighter.class);
    }
}
