package com.github.ttereshchenko.mailkit.inspections.rules;

/** A character-offset range inside the inspected text. */
public record LineRange(int startOffset, int endOffset) {

    public LineRange {
        if (endOffset < startOffset) {
            throw new IllegalArgumentException("endOffset (" + endOffset + ") < startOffset (" + startOffset + ")");
        }
    }
}
