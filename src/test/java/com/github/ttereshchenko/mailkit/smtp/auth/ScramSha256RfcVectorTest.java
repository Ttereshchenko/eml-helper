package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Reproduces the SCRAM-SHA-256 walk-through from RFC 7677 §3. */
class ScramSha256RfcVectorTest {

    @Test
    void rfc7677Section3() {
        var credentials = AuthCredentials.of("user", () -> "pencil".toCharArray());
        var client = new ScramSha256AuthClient(credentials, "rOprNGfwEbeRWgbNEkqO");

        var clientFirst = client.initial();
        assertEquals("n,,n=user,r=rOprNGfwEbeRWgbNEkqO", new String(clientFirst, StandardCharsets.UTF_8));

        var serverFirst = "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096"
                .getBytes(StandardCharsets.UTF_8);
        var clientFinal = client.respond(serverFirst);
        assertEquals(
                "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,"
                        + "p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=",
                new String(clientFinal, StandardCharsets.UTF_8));

        var serverFinal = "v=6rriTRBi23WpRR/wtup+mMhUZUn/dB5nLTJRsjl95G4=".getBytes(StandardCharsets.UTF_8);
        client.respond(serverFinal);
        assertTrue(client.isComplete());
    }
}
