package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * F9 regression — the server-supplied SCRAM {@code i=} (PBKDF2 iteration count) must be bounded
 * before it reaches the (non-cancellation-aware) key derivation, so a hostile or buggy server
 * cannot pin the send thread in a multi-billion-round PBKDF2 CPU-burn.
 */
class ScramIterationCountClampTest {

    private static final String CLIENT_NONCE = "rOprNGfwEbeRWgbNEkqO";

    private static ScramSha256AuthClient newClientAtServerFirst() {
        var credentials = AuthCredentials.of("user", () -> "pencil".toCharArray());
        var client = new ScramSha256AuthClient(credentials, CLIENT_NONCE);
        client.initial();
        return client;
    }

    private static byte[] serverFirstWithIterations(String iterations) {
        return ("r=" + CLIENT_NONCE + "%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=" + iterations)
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void hostileIterationCountIsRejectedBeforePbkdf2() {
        var client = newClientAtServerFirst();
        var failure = assertThrows(
                IllegalStateException.class, () -> client.respond(serverFirstWithIterations("2000000000")));
        assertTrue(
                failure.getMessage().contains("iteration count out of range"),
                "expected an out-of-range iteration-count rejection, got: " + failure.getMessage());
    }

    @Test
    void nonPositiveIterationCountIsRejected() {
        var client = newClientAtServerFirst();
        assertThrows(IllegalStateException.class, () -> client.respond(serverFirstWithIterations("0")));
    }

    @Test
    void legitimateIterationCountStillSucceeds() {
        var client = newClientAtServerFirst();
        // 4096 is the RFC 7677 §3 walk-through value — must remain accepted.
        assertDoesNotThrow(() -> client.respond(serverFirstWithIterations("4096")));
    }
}
