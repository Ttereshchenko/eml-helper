package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * CRAM-MD5 through the JDK SASL provider ({@link SaslAuthClient}), pinned to the RFC 2195 §2
 * test vector: user {@code tim}, secret {@code tanstaaftanstaaf}, the published challenge, and
 * the expected digest response.
 */
class CramMd5AuthClientTest {

    @Test
    void rfc2195VectorProducesThePublishedDigest() throws Exception {
        var credentials = AuthCredentials.of("tim", "tanstaaftanstaaf"::toCharArray);
        var client = AuthClients.create(AuthMechanism.CRAM_MD5, credentials, "postoffice.reston.mci.net");

        assertNull(client.initial(), "CRAM-MD5 is challenge-first");
        var challenge = "<1896.697170952@postoffice.reston.mci.net>".getBytes(StandardCharsets.UTF_8);
        var response = new String(client.respond(challenge), StandardCharsets.UTF_8);

        assertEquals("tim b913a602c7eda7a495b4e6e7334d3890", response);
        assertTrue(client.isComplete());
    }
}
