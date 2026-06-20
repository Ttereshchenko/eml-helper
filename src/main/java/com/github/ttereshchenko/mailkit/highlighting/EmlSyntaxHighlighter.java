package com.github.ttereshchenko.mailkit.highlighting;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.github.ttereshchenko.mailkit.lexer.EmlLexer;
import com.github.ttereshchenko.mailkit.settings.EmlHeaderSettings;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public final class EmlSyntaxHighlighter extends SyntaxHighlighterBase {
    // Fallback to KEYWORD so the boundary stays colored on schemes not covered by
    // the bundled additionalTextAttributes (Default/Darcula). See colorSchemes/*.xml.
    public static final TextAttributesKey BOUNDARY_KEY =
            TextAttributesKey.createTextAttributesKey("EML_BOUNDARY", DefaultLanguageHighlighterColors.KEYWORD);

    private static final TextAttributesKey[] BOUNDARY_KEYS = {BOUNDARY_KEY};
    private static final TextAttributesKey[] EMPTY_KEYS = {};

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new EmlLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (!EmlHeaderSettings.getInstance().isHighlightingEnabled()) {
            return EMPTY_KEYS;
        }
        if (tokenType == EmlTokenTypes.BOUNDARY_START || tokenType == EmlTokenTypes.BOUNDARY_END) {
            return BOUNDARY_KEYS;
        }
        return EMPTY_KEYS;
    }
}
