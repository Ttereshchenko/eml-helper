package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LoginAuthClientTest {

    @Test
    void twoRoundsExchangeUsernameThenPassword() {
        var client = new LoginAuthClient(AuthCredentials.of("admin", () -> "s3cret".toCharArray()));

        assertNull(client.initial(), "LOGIN is challenge-first");

        var round1 = client.respond("Username:".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("admin".getBytes(StandardCharsets.UTF_8), round1);
        assertFalse(client.isComplete());

        var round2 = client.respond("Password:".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("s3cret".getBytes(StandardCharsets.UTF_8), round2);
        assertTrue(client.isComplete());
    }

    @Test
    void appliesSaslPrepToNonAsciiUsernameAndPassword() {
        // SASLprep (rfc3454 Table C.1.2) maps a NO-BREAK SPACE (U+00A0) to an ASCII space, so the
        // wire bytes carry the mapped 0x20 space rather than the raw 0xC2 0xA0 sequence.
        var client = new LoginAuthClient(AuthCredentials.of("ad min", () -> "s cret".toCharArray()));

        assertArrayEquals(
                "ad min".getBytes(StandardCharsets.UTF_8),
                client.respond("Username:".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(
                "s cret".getBytes(StandardCharsets.UTF_8),
                client.respond("Password:".getBytes(StandardCharsets.UTF_8)));
    }
}
