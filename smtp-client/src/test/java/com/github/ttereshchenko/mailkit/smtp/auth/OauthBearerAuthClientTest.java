package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void errorChallengeIsAcknowledgedWithEmptyResponse() {
        var client = new OauthBearerAuthClient(AuthCredentials.bearer("u", "t"::toCharArray));
        client.initial();
        assertArrayEquals(new byte[0], client.respond("{\"status\":\"401\"}".getBytes(StandardCharsets.UTF_8)));
    }
}
