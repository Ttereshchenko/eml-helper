package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OauthBearerAuthClientTest {

    private static final char CTRL_A = (char) 0x01;

    @Test
    void initialResponseMatchesRfc7628Layout() {
        var credentials = new AuthCredentials(
                "user@example.com",
                "vF9dft4qmTc2Nvb3RlckBhbHRhdmlzdGEuY29tCg"::toCharArray,
                "",
                Map.of("host", "server.example.com", "port", "587"));
        var client = new OauthBearerAuthClient(credentials);

        var bytes = client.initial();

        var expected = "n,a=user@example.com," + CTRL_A
                + "host=server.example.com" + CTRL_A
                + "port=587" + CTRL_A
                + "auth=Bearer vF9dft4qmTc2Nvb3RlckBhbHRhdmlzdGEuY29tCg" + CTRL_A + CTRL_A;
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), bytes);
        assertTrue(client.isComplete());
    }

    @Test
    void hostAndPortAreOmittedWhenAbsent() {
        var credentials = AuthCredentials.bearer("", "token123"::toCharArray);
        var client = new OauthBearerAuthClient(credentials);

        var text = new String(client.initial(), StandardCharsets.UTF_8);

        assertEquals("n,," + CTRL_A + "auth=Bearer token123" + CTRL_A + CTRL_A, text);
    }

    @Test
    void errorChallengeIsAcknowledgedWithSingleCtrlAOctet() {
        // rfc7628 §3.2.3: the client acknowledges the server's error continuation with a single
        // %x01 (CTRL-A) octet — base64 "AQ==" — not an empty line, so the server can return the
        // final SASL failure. (The pre-fix client returned an empty byte[0].)
        var client = new OauthBearerAuthClient(AuthCredentials.bearer("u", "t"::toCharArray));
        client.initial();
        assertArrayEquals(new byte[] {0x01}, client.respond("{\"status\":\"401\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsKvsepInGs2Fields() {
        // rfc7628 §3.1: a gs2 field value must not contain the kvsep (%x01). An injected separator
        // would forge extra SASL fields inside the base64-wrapped blob, so it is rejected.
        var injectedUser =
                new OauthBearerAuthClient(new AuthCredentials("userinjected", "t"::toCharArray, "", Map.of()));
        assertThrows(IllegalArgumentException.class, injectedUser::initial);

        var injectedHost = new OauthBearerAuthClient(
                new AuthCredentials("user", "t"::toCharArray, "", Map.of("host", "hx", "port", "25")));
        assertThrows(IllegalArgumentException.class, injectedHost::initial);
    }
}
