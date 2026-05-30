package com.github.ttereshchenko.mailkit.psi;

import com.github.ttereshchenko.mailkit.EmlLanguage;
import com.intellij.psi.tree.IElementType;

public final class EmlElementTypes {
    public static final IElementType HEADER = new IElementType("EML_HEADER", EmlLanguage.INSTANCE);
    public static final IElementType HEADER_BLOCK = new IElementType("EML_HEADER_BLOCK", EmlLanguage.INSTANCE);
    public static final IElementType MIME_PART = new IElementType("EML_MIME_PART", EmlLanguage.INSTANCE);
    public static final IElementType NESTED_MESSAGE = new IElementType("EML_NESTED_MESSAGE", EmlLanguage.INSTANCE);
    // Reparseable chameleon: a leaf part's body collapses into this single lazy node so the AST node
    // count stays bounded and in-body edits reparse only the body. See EmlBodyContentType.
    public static final IElementType BODY_TEXT = new EmlBodyContentType();

    private EmlElementTypes() {}
}
