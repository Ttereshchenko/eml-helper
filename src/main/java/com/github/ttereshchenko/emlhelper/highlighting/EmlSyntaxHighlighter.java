package com.github.ttereshchenko.emlhelper.highlighting;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.tree.IElementType;
import com.github.ttereshchenko.emlhelper.EmlTokenTypes;
import com.github.ttereshchenko.emlhelper.lexer.EmlLexer;
import com.github.ttereshchenko.emlhelper.settings.EmlHeaderSettings;
import org.jetbrains.annotations.NotNull;

import static com.intellij.openapi.editor.DefaultLanguageHighlighterColors.*;

public final class EmlSyntaxHighlighter extends SyntaxHighlighterBase {
    public static final TextAttributesKey BOUNDARY_KEY =
            TextAttributesKey.createTextAttributesKey("EML_BOUNDARY", KEYWORD);
    public static final TextAttributesKey HEADER_KEY =
            TextAttributesKey.createTextAttributesKey("EML_HEADER", INSTANCE_FIELD);

    private static final TextAttributesKey[] BOUNDARY_KEYS = {BOUNDARY_KEY};
    private static final TextAttributesKey[] HEADER_KEYS   = {HEADER_KEY};
    private static final TextAttributesKey[] EMPTY_KEYS    = {};

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
        if (tokenType == EmlTokenTypes.HEADER_LINE) {
            return HEADER_KEYS;
        }
        return EMPTY_KEYS;
    }
}
