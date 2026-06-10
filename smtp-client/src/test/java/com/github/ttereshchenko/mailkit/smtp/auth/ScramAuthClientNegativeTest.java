package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Security-relevant rejection branches of the SCRAM client, driven with the RFC 5802 §5 vector
 * (user {@code user}, password {@code pencil}, fixed client nonce).
 */
class ScramAuthClientNegativeTest {

    private static final String CLIENT_NONCE = "fyko+d2lbbFgONRv9qkxdawL";
    private static final String SERVER_FIRST = "r=fyko+d2lbbFgONRv9qkxdawL3rfcNHYJY1ZVvWVs7j,s=QSXCR+Q6sek8bf92,i=4096";

    private static ScramSha1AuthClient freshClientPastServerFirst() {
        var client = new ScramSha1AuthClient(AuthCredentials.of("user", "pencil"::toCharArray), CLIENT_NONCE);
        client.initial();
        client.respond(SERVER_FIRST.getBytes(StandardCharsets.UTF_8));
        return client;
    }

    @Test
    void serverNonceNotEchoingClientNonceIsRejected() {
        var client = new ScramSha1AuthClient(AuthCredentials.of("user", "pencil"::toCharArray), CLIENT_NONCE);
        client.initial();
        var foreignNonce = "r=attackerNonce,s=QSXCR+Q6sek8bf92,i=4096";

        var failure = assertThrows(
                IllegalStateException.class, () -> client.respond(foreignNonce.getBytes(StandardCharsets.UTF_8)));
        assertTrue(failure.getMessage().contains("nonce"), failure.getMessage());
    }

    @Test
    void malformedServerFirstIsRejected() {
        var client = new ScramSha1AuthClient(AuthCredentials.of("user", "pencil"::toCharArray), CLIENT_NONCE);
        client.initial();

        var failure = assertThrows(
                IllegalStateException.class, () -> client.respond("garbage".getBytes(StandardCharsets.UTF_8)));
        assertTrue(failure.getMessage().contains("malformed"), failure.getMessage());
    }

    @Test
    void tamperedServerSignatureIsRejected() {
        var client = freshClientPastServerFirst();
        // Correct vector value is v=rmF9pqV8S7suAoZWja4dJRkFsKQ= — flip the payload.
        var tampered = "v=AAAAAAAAAAAAAAAAAAAAAAAAAAA=";

        var failure = assertThrows(
                IllegalStateException.class, () -> client.respond(tampered.getBytes(StandardCharsets.UTF_8)));
        assertTrue(failure.getMessage().contains("signature"), failure.getMessage());
    }

    @Test
    void serverErrorAttributeIsSurfaced() {
        var client = freshClientPastServerFirst();

        var failure = assertThrows(
                IllegalStateException.class, () -> client.respond("e=invalid-proof".getBytes(StandardCharsets.UTF_8)));
        assertTrue(failure.getMessage().contains("invalid-proof"), failure.getMessage());
    }
}
