/**
 * A dependency-free reader for Outlook PST/OST personal-folders files ([MS-PST]).
 *
 * <p>The public API is small: open a {@link com.github.ttereshchenko.mailkit.pst.PstFile}, walk
 * {@link com.github.ttereshchenko.mailkit.pst.Folder}s from the root node ({@code 0x122}), and read
 * {@link com.github.ttereshchenko.mailkit.pst.Message}s and their
 * {@link com.github.ttereshchenko.mailkit.pst.Attachment}s.
 * {@link com.github.ttereshchenko.mailkit.pst.MapiProperties} holds the MAPI property tags and
 * {@link com.github.ttereshchenko.mailkit.pst.PstException} reports malformed input.
 *
 * <p>Internals are layered NDB &rarr; HN &rarr; PC/TC per [MS-PST]; those classes are not part of the
 * supported API surface. Node and block lookups descend the on-disk b-trees lazily and attachment
 * content can be streamed, so large stores are not pulled into memory.
 *
 * <p>A {@link com.github.ttereshchenko.mailkit.pst.PstFile} is safe for concurrent use;
 * {@code Folder}/{@code Message}/{@code Attachment} instances are not thread-safe — confine each to
 * a single thread.
 */
package com.github.ttereshchenko.mailkit.pst;
