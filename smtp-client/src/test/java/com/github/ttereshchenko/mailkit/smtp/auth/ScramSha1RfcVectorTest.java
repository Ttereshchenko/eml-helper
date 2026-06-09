package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the SCRAM-SHA-1 walk-through from RFC 5802 §5. The fixed client nonce, server salt
 * and iteration count are taken verbatim from the RFC; passing this proves the four-message
 * exchange and the client-proof computation are correct end-to-end.
 */
class ScramSha1RfcVectorTest {

    @Test
    void rfc5802Section5() {
        var credentials = AuthCredentials.of("user", () -> "pencil".toCharArray());
        var client = new ScramSha1AuthClient(credentials, "fyko+d2lbbFgONRv9qkxdawL");

        var clientFirst = client.initial();
        assertEquals("n,,n=user,r=fyko+d2lbbFgONRv9qkxdawL", new String(clientFirst, StandardCharsets.UTF_8));

        var serverFirst = "r=fyko+d2lbbFgONRv9qkxdawL3rfcNHYJY1ZVvWVs7j,s=QSXCR+Q6sek8bf92,i=4096"
                .getBytes(StandardCharsets.UTF_8);
        var clientFinal = client.respond(serverFirst);
        assertEquals(
                "c=biws,r=fyko+d2lbbFgONRv9qkxdawL3rfcNHYJY1ZVvWVs7j,p=v0X8v3Bz2T0CJGbJQyF0X+HI4Ts=",
                new String(clientFinal, StandardCharsets.UTF_8));

        var serverFinal = "v=rmF9pqV8S7suAoZWja4dJRkFsKQ=".getBytes(StandardCharsets.UTF_8);
        client.respond(serverFinal);
        assertTrue(client.isComplete(), "client must be complete after verifying server signature");
    }
}
