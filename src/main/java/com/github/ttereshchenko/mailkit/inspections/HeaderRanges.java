package com.github.ttereshchenko.mailkit.inspections;

import com.github.ttereshchenko.mailkit.EmlTokenTypes;
import com.github.ttereshchenko.mailkit.psi.EmlHeader;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.Nullable;

/** Small helpers for mapping inside-header offsets back to absolute document ranges. */
public final class HeaderRanges {

    private HeaderRanges() {}

    /**
     * Range covering everything to the right of the {@code :} on the first line of
     * {@code header}, trimmed to skip the leading whitespace. Returns null when the
     * header has no colon or no first line.
     */
    public static @Nullable TextRange valueRangeOnFirstLine(EmlHeader header) {
        var firstLine = firstLineNode(header);
        if (firstLine == null) {
            return null;
        }
        var text = firstLine.getText();
        var colon = text.indexOf(':');
        if (colon < 0) {
            return null;
        }
        var valueStart = colon + 1;
        while (valueStart < text.length() && (text.charAt(valueStart) == ' ' || text.charAt(valueStart) == '\t')) {
            valueStart++;
        }
        var valueEnd = text.length();
        while (valueEnd > valueStart && (text.charAt(valueEnd - 1) == '\r' || text.charAt(valueEnd - 1) == '\n')) {
            valueEnd--;
        }
        if (valueEnd <= valueStart) {
            return null;
        }
        var absoluteStart = firstLine.getTextRange().getStartOffset() + valueStart;
        return new TextRange(absoluteStart, firstLine.getTextRange().getStartOffset() + valueEnd);
    }

    /** Offset (within the document) of the first character of the value on the first line. */
    public static int valueStartOffset(EmlHeader header) {
        var range = valueRangeOnFirstLine(header);
        return range == null ? header.getTextRange().getEndOffset() : range.getStartOffset();
    }

    public static @Nullable ASTNode firstLineNode(EmlHeader header) {
        for (var child : header.getNode().getChildren(null)) {
            if (child.getElementType() == EmlTokenTypes.HEADER_LINE) {
                return child;
            }
        }
        return null;
    }
}
