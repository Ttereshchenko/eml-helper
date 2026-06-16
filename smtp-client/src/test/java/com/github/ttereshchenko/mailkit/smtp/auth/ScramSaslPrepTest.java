package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * SCRAM must prepare the username and password with SASLprep (rfc4013, the stringprep profile
 * rfc3454) before computing the client proof — rfc5802 §5.1 / §3. Without it, any non-ASCII
 * credential yields a wrong proof and AUTH fails. These tests fail on the pre-fix client, which
 * used the raw username/password bytes.
 */
class ScramSaslPrepTest {

    private static final String CLIENT_NONCE = "rOprNGfwEbeRWgbNEkqO";
    private static final String SERVER_FIRST =
            "r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,s=W22ZaJ0SNY7soEsUEjb6gQ==,i=4096";
    // The RFC 7677 §3 walk-through proof for user="user", password="pencil".
    private static final String RFC_CLIENT_FINAL = "c=biws,r=rOprNGfwEbeRWgbNEkqO%hvYDpWUa2RaTCAfuxFIlj)hNlF$k0,"
            + "p=dHzbZapWIk4jUhN+Ute9ytag9zjfMHgsqmmiz7AndVQ=";

    private static String clientFinalFor(String username, String password) {
        var client = new ScramSha256AuthClient(AuthCredentials.of(username, password::toCharArray), CLIENT_NONCE);
        client.initial();
        return new String(client.respond(SERVER_FIRST.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Test
    void softHyphenInPasswordIsRemovedSoProofMatchesPlainPassword() {
        // rfc3454 Table B.1: U+00AD SOFT HYPHEN is "commonly mapped to nothing" — SASLprep deletes
        // it, so "pen<U+00AD>cil" must derive the same salted password as "pencil" and therefore
        // produce the canonical RFC 7677 §3 proof.
        var clientFinal = clientFinalFor("user", "pen­cil");

        assertEquals(RFC_CLIENT_FINAL, clientFinal);
    }

    @Test
    void softHyphenInUsernameIsRemovedFromClientFirst() {
        // rfc5802 §5.1: the username is SASLprep-normalized before the gs2 escaping. The soft
        // hyphen is deleted, so "us<U+00AD>er" goes on the wire as plain "user".
        var client = new ScramSha256AuthClient(AuthCredentials.of("us­er", "pencil"::toCharArray), CLIENT_NONCE);

        var clientFirst = new String(client.initial(), StandardCharsets.UTF_8);

        assertEquals("n,,n=user,r=" + CLIENT_NONCE, clientFirst);
    }

    @Test
    void nonAsciiSpaceInPasswordMapsToRegularSpace() {
        // rfc3454 Table C.1.2: U+00A0 NO-BREAK SPACE is a "non-ASCII space" mapped to U+0020 by
        // SASLprep. A password with the non-ASCII space must derive the same proof as the same
        // password written with a regular ASCII space.
        var withNonAsciiSpace = clientFinalFor("user", "pa ss");
        var withRegularSpace = clientFinalFor("user", "pa ss");

        assertEquals(withRegularSpace, withNonAsciiSpace);
    }

    @Test
    void asciiCredentialIsByteIdenticalAfterSaslPrep() {
        // Regression guard: SASLprep must leave pure-ASCII credentials untouched so existing
        // deployments see identical wire bytes.
        assertEquals("user", ScramAuthClient.saslPrep("user"));
        assertEquals("p@ssw0rd!", ScramAuthClient.saslPrep("p@ssw0rd!"));
    }

    @Test
    void saslPrepDeletesMappedToNothingAndMapsSpace() {
        // U+00AD deleted (Table B.1); U+2003 EM SPACE mapped to SP (Table C.1.2).
        assertEquals("ab", ScramAuthClient.saslPrep("a­b"));
        assertEquals("a b", ScramAuthClient.saslPrep("a b"));
    }

    @Test
    void preparedUsernameStillGetsGs2Escaping() {
        // SASLprep and the SCRAM gs2 =/,-escaping are separate steps (rfc5802 §5.1): a non-ASCII
        // username that survives SASLprep is still escaped on the wire.
        var client = new ScramSha256AuthClient(AuthCredentials.of("a,b=c", "pencil"::toCharArray), CLIENT_NONCE);

        var clientFirst = new String(client.initial(), StandardCharsets.UTF_8);

        assertTrue(clientFirst.startsWith("n,,n=a=2Cb=3Dc,r="), clientFirst);
    }
}
