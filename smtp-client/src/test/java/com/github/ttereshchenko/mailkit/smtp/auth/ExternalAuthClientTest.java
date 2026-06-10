package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ExternalAuthClientTest {

    @Test
    void initialResponseCarriesTheAuthzid() {
        var client = new ExternalAuthClient(AuthCredentials.external("admin@example.com"));

        assertArrayEquals("admin@example.com".getBytes(StandardCharsets.UTF_8), client.initial());
        assertTrue(client.isComplete());
    }

    @Test
    void emptyAuthzidYieldsAnEmptyButPresentInitialResponse() {
        var client = new ExternalAuthClient(AuthCredentials.external(""));

        // The SMTP layer turns a present-but-empty initial response into "=" (rfc4954 §4).
        assertArrayEquals(new byte[0], client.initial());
        assertTrue(client.isComplete());
    }

    @Test
    void externalRejectsChallenges() {
        var client = new ExternalAuthClient(AuthCredentials.external(""));
        assertThrows(IllegalStateException.class, () -> client.respond(new byte[0]));
    }
}
