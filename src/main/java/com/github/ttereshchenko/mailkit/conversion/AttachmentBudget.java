package com.github.ttereshchenko.mailkit.conversion;

/**
 * A mutable, per-top-level-message budget that bounds how much attachment data a single conversion
 * buffers in memory before the EML is written.
 *
 * <p>Both the MSG and PST converters build every attachment part in memory (base64-encoded) through
 * {@link EmlSerializer} before the first byte is written, so a crafted container declaring thousands
 * of attachments — or a few very large ones — can exhaust the heap with an {@link OutOfMemoryError}
 * that escapes the converters' per-item {@code catch}. The count and aggregate-byte caps make such
 * input degrade (logged and truncated) instead of failing the whole conversion.
 *
 * <p>A single instance is threaded through embedded-message recursion so the caps bound the entire
 * message tree rather than each nesting level independently: without sharing, an N-level chain of
 * messages each just under the per-message cap could still buffer N times the limit at once.
 */
public final class AttachmentBudget {

    /** Aggregate attachment-byte ceiling for one top-level message tree. */
    public static final long MAX_TOTAL_ATTACHMENT_BYTES = 256L * 1024 * 1024;

    /** Attachment-count ceiling for one top-level message tree (counts nested messages too). */
    public static final int MAX_ATTACHMENT_COUNT = 1000;

    private long totalBytes;
    private int count;

    /** Whether the attachment-count cap is already reached; the caller should stop adding. */
    public boolean atCountLimit() {
        return count >= MAX_ATTACHMENT_COUNT;
    }

    /** Records one attachment slot consumed, independent of its byte size (also counts nested messages). */
    public void recordAttachment() {
        count++;
    }

    /**
     * Adds {@code bytes} to the running aggregate and reports whether the byte cap is now exceeded.
     *
     * @return {@code true} once the aggregate exceeds {@link #MAX_TOTAL_ATTACHMENT_BYTES}, meaning the
     *     caller should stop adding further attachments
     */
    public boolean recordBytes(long bytes) {
        totalBytes += bytes;
        return totalBytes > MAX_TOTAL_ATTACHMENT_BYTES;
    }

    /** The aggregate byte ceiling expressed in whole megabytes, for log messages. */
    public static long maxTotalMegabytes() {
        return MAX_TOTAL_ATTACHMENT_BYTES / (1024 * 1024);
    }
}
