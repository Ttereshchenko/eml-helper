package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageSourceTest {

    @TempDir
    Path tempDir;

    @Test
    void ofBytesTakesADefensiveCopyAndReportsSize() throws Exception {
        var payload = "hello".getBytes(StandardCharsets.UTF_8);
        var source = MessageSource.ofBytes(payload);
        payload[0] = 'X'; // mutating the caller's array must not affect the source

        assertArrayEquals(
                "hello".getBytes(StandardCharsets.UTF_8), source.open().readAllBytes());
        assertEquals(5L, source.size().orElseThrow());
    }

    @Test
    void ofBytesCanBeOpenedRepeatedly() throws Exception {
        var source = MessageSource.ofBytes("again".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(source.open().readAllBytes(), source.open().readAllBytes());
    }

    @Test
    void ofStringEncodesUtf8() throws Exception {
        var source = MessageSource.ofString("café");
        assertArrayEquals("café".getBytes(StandardCharsets.UTF_8), source.open().readAllBytes());
    }

    @Test
    void ofPathStreamsFileContentAndSize() throws Exception {
        var file = tempDir.resolve("message.eml");
        Files.writeString(file, "Subject: x\r\n\r\nbody\r\n");
        var source = MessageSource.ofPath(file);

        assertEquals(Files.size(file), source.size().orElseThrow());
        assertArrayEquals(Files.readAllBytes(file), source.open().readAllBytes());
    }

    @Test
    void ofPathSizeIsEmptyWhenFileVanishes() {
        var source = MessageSource.ofPath(tempDir.resolve("never-created.eml"));
        assertTrue(source.size().isEmpty(), "size of a missing file must be empty, not an exception");
    }
}
