package com.github.ttereshchenko.emlhelper.highlighting;

import com.intellij.openapi.editor.colors.TextAttributesKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.intellij.openapi.editor.DefaultLanguageHighlighterColors.*;
import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

public final class EmlHeaderTextAttributeKeys {
    public static final TextAttributesKey HEADER_FROM =
            createTextAttributesKey("EML_HEADER_FROM", INSTANCE_FIELD);
    public static final TextAttributesKey HEADER_TO =
            createTextAttributesKey("EML_HEADER_TO", STATIC_FIELD);
    public static final TextAttributesKey HEADER_SUBJECT =
            createTextAttributesKey("EML_HEADER_SUBJECT", FUNCTION_DECLARATION);
    public static final TextAttributesKey HEADER_DATE =
            createTextAttributesKey("EML_HEADER_DATE", NUMBER);
    public static final TextAttributesKey HEADER_CC =
            createTextAttributesKey("EML_HEADER_CC", PARAMETER);
    public static final TextAttributesKey HEADER_BCC =
            createTextAttributesKey("EML_HEADER_BCC", METADATA);

    private static final Map<String, TextAttributesKey> PREDEFINED = Map.of(
            "FROM", HEADER_FROM,
            "TO", HEADER_TO,
            "SUBJECT", HEADER_SUBJECT,
            "DATE", HEADER_DATE,
            "CC", HEADER_CC,
            "BCC", HEADER_BCC
    );

    private static final Map<String, TextAttributesKey> DYNAMIC_KEYS = new ConcurrentHashMap<>();

    private EmlHeaderTextAttributeKeys() {}

    public static TextAttributesKey getKey(String headerName) {
        String upper = headerName.toUpperCase();
        TextAttributesKey predefined = PREDEFINED.get(upper);
        if (predefined != null) {
            return predefined;
        }
        return DYNAMIC_KEYS.computeIfAbsent(upper,
                k -> createTextAttributesKey("EML_HEADER_" + k, INSTANCE_FIELD));
    }
}
