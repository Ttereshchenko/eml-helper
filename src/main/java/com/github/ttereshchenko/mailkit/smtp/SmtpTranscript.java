package com.github.ttereshchenko.mailkit.smtp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Append-only record of every send / receive on the SMTP socket with monotonic nanosecond timestamps.
 * Lines flagged as {@link Direction#CLIENT_AUTH} are redacted in the rendered output by default —
 * the raw bytes are still retained so a per-send "show plaintext" toggle can reveal them on demand.
 */
public final class SmtpTranscript {

    public enum Direction {
        CLIENT,
        SERVER,
        CLIENT_AUTH,
        INFO
    }

    public record Entry(Direction direction, byte[] bytes, long nanoTimestamp, Phase phase) {
        public Entry {
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(phase, "phase");
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onEntry(Entry entry);
    }

    public static final Listener NULL_LISTENER = entry -> {};

    private static final String REDACTED = "<auth credentials scrubbed>";

    private final List<Entry> entries = new ArrayList<>();
    private final Listener listener;

    public SmtpTranscript() {
        this(NULL_LISTENER);
    }

    public SmtpTranscript(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized void append(Direction direction, byte[] bytes, Phase phase) {
        var entry = new Entry(direction, bytes, System.nanoTime(), phase);
        entries.add(entry);
        listener.onEntry(entry);
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * Render the transcript as text. AUTH lines are replaced with a placeholder when
     * {@code revealAuth} is false; UI consumers default to redacted.
     */
    public synchronized String render(boolean revealAuth) {
        var builder = new StringBuilder();
        for (var entry : entries) {
            var prefix =
                    switch (entry.direction()) {
                        case CLIENT, CLIENT_AUTH -> "C: ";
                        case SERVER -> "S: ";
                        case INFO -> "# ";
                    };
            builder.append(prefix);
            if (entry.direction() == Direction.CLIENT_AUTH && !revealAuth) {
                builder.append(REDACTED);
            } else {
                builder.append(new String(entry.bytes(), StandardCharsets.UTF_8));
            }
            if (builder.length() == 0 || builder.charAt(builder.length() - 1) != '\n') {
                builder.append('\n');
            }
        }
        return builder.toString();
    }
}
