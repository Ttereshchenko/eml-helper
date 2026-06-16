package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Xoauth2AuthClientTest {

    @Test
    void initialResponseMatchesGoogleSpec() {
        var client =
                new Xoauth2AuthClient(AuthCredentials.bearer("user@example.com", () -> "ya29.token".toCharArray()));

        var bytes = client.initial();

        var expected = "user=user@example.comauth=Bearer ya29.token".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, bytes, "XOAUTH2 must use \\x01 separators between fields");
        assertTrue(client.isComplete());
    }

    @Test
    void rejectsControlCharactersInUsername() {
        // The fields are %x01-delimited; an injected separator in the username would forge extra
        // fields inside the base64-wrapped blob, so it is rejected before the payload is assembled.
        var client = new Xoauth2AuthClient(AuthCredentials.bearer("userx", () -> "ya29.token".toCharArray()));
        assertThrows(IllegalArgumentException.class, client::initial);
    }
}
