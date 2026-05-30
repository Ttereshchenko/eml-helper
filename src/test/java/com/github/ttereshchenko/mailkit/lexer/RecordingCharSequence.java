package com.github.ttereshchenko.mailkit.lexer;

/**
 * Test-only {@link CharSequence} that records the length of the largest slice ever requested via
 * {@link #subSequence}. Used to prove that the lexer / boundary scan never materializes a
 * multi-megabyte single-line body as a {@code String} — the allocation that used to lag typing on
 * EML files with base64 attachments.
 */
final class RecordingCharSequence implements CharSequence {

    private final CharSequence backing;
    private int maxSliceLength;

    RecordingCharSequence(CharSequence backing) {
        this.backing = backing;
    }

    int maxSliceLength() {
        return maxSliceLength;
    }

    @Override
    public int length() {
        return backing.length();
    }

    @Override
    public char charAt(int index) {
        return backing.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        maxSliceLength = Math.max(maxSliceLength, end - start);
        return backing.subSequence(start, end);
    }

    @Override
    public String toString() {
        return backing.toString();
    }
}
