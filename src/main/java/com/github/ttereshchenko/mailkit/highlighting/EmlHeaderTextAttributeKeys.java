package com.github.ttereshchenko.mailkit.highlighting;

import static com.intellij.openapi.editor.DefaultLanguageHighlighterColors.FUNCTION_DECLARATION;
import static com.intellij.openapi.editor.DefaultLanguageHighlighterColors.INSTANCE_FIELD;
import static com.intellij.openapi.editor.DefaultLanguageHighlighterColors.METADATA;
import static com.intellij.openapi.editor.DefaultLanguageHighlighterColors.NUMBER;
import static com.intellij.openapi.editor.DefaultLanguageHighlighterColors.PARAMETER;
import static com.intellij.openapi.editor.DefaultLanguageHighlighterColors.STATIC_FIELD;
import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EmlHeaderTextAttributeKeys {
    // Each key carries a default fallback so headers stay colored on every scheme
    // (New UI Light/Dark, Islands Dark/Light, etc.), not just the Default/Darcula
    // schemes covered by the bundled additionalTextAttributes. See colorSchemes/*.xml.
    public static final TextAttributesKey HEADER_FROM = createTextAttributesKey("EML_HEADER_FROM", INSTANCE_FIELD);
    public static final TextAttributesKey HEADER_TO = createTextAttributesKey("EML_HEADER_TO", STATIC_FIELD);
    public static final TextAttributesKey HEADER_SUBJECT =
            createTextAttributesKey("EML_HEADER_SUBJECT", FUNCTION_DECLARATION);
    public static final TextAttributesKey HEADER_DATE = createTextAttributesKey("EML_HEADER_DATE", NUMBER);
    public static final TextAttributesKey HEADER_CC = createTextAttributesKey("EML_HEADER_CC", PARAMETER);
    public static final TextAttributesKey HEADER_BCC = createTextAttributesKey("EML_HEADER_BCC", METADATA);

    private static final Map<String, TextAttributesKey> PREDEFINED = Map.of(
            "FROM", HEADER_FROM,
            "TO", HEADER_TO,
            "SUBJECT", HEADER_SUBJECT,
            "DATE", HEADER_DATE,
            "CC", HEADER_CC,
            "BCC", HEADER_BCC);

    private static final Map<String, TextAttributesKey> DYNAMIC_KEYS = new ConcurrentHashMap<>();

    private EmlHeaderTextAttributeKeys() {}

    public static TextAttributesKey getKey(String headerName) {
        String upper = headerName.toUpperCase(Locale.ROOT);
        TextAttributesKey predefined = PREDEFINED.get(upper);
        if (predefined != null) {
            return predefined;
        }
        return DYNAMIC_KEYS.computeIfAbsent(upper, key -> createTextAttributesKey("EML_HEADER_" + key, INSTANCE_FIELD));
    }
}
