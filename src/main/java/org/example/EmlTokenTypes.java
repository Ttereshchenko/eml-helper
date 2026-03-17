package org.example;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;

public final class EmlTokenTypes {
    public static final IFileElementType FILE = new IFileElementType("EML_FILE", EmlLanguage.INSTANCE);

    public static final IElementType HEADER_LINE    = new IElementType("HEADER_LINE",    EmlLanguage.INSTANCE);
    public static final IElementType BLANK_LINE     = new IElementType("BLANK_LINE",     EmlLanguage.INSTANCE);
    public static final IElementType BOUNDARY_START = new IElementType("BOUNDARY_START", EmlLanguage.INSTANCE);
    public static final IElementType BOUNDARY_END   = new IElementType("BOUNDARY_END",   EmlLanguage.INSTANCE);
    public static final IElementType BODY_LINE      = new IElementType("BODY_LINE",      EmlLanguage.INSTANCE);

    private EmlTokenTypes() {}
}
