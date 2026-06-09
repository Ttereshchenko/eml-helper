package com.github.ttereshchenko.mailkit.smtp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Abstraction over "the bytes that go through DATA". Implementations open a fresh {@link InputStream}
 * each call so the client can retry a send without the caller re-reading the file. The bytes are
 * streamed onto the wire as-is — the SMTP client only applies LF→CRLF normalization, dot-stuffing,
 * and the terminating {@code <CRLF>.<CRLF>}, never re-encoding the body.
 *
 * <p>Implementations that know their length up front should override {@link #size()} so the client
 * can honour the server's SIZE advertisement and emit {@code MAIL FROM:<...> SIZE=...} without
 * buffering the whole message.
 */
public interface MessageSource {

    InputStream open() throws IOException;

    /** Best-effort length in bytes. Empty when the source cannot determine its length up front. */
    default OptionalLong size() {
        return OptionalLong.empty();
    }

    static MessageSource ofBytes(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        var snapshot = payload.clone();
        return new MessageSource() {
            @Override
            public InputStream open() {
                return new ByteArrayInputStream(snapshot);
            }

            @Override
            public OptionalLong size() {
                return OptionalLong.of(snapshot.length);
            }
        };
    }

    static MessageSource ofString(String payload) {
        Objects.requireNonNull(payload, "payload");
        return ofBytes(payload.getBytes(StandardCharsets.UTF_8));
    }

    static MessageSource ofPath(Path path) {
        Objects.requireNonNull(path, "path");
        return new MessageSource() {
            @Override
            public InputStream open() throws IOException {
                return Files.newInputStream(path);
            }

            @Override
            public OptionalLong size() {
                try {
                    return OptionalLong.of(Files.size(path));
                } catch (IOException ignored) {
                    return OptionalLong.empty();
                }
            }
        };
    }
}
