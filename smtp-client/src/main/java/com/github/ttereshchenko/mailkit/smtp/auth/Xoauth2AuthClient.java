package com.github.ttereshchenko.mailkit.smtp.auth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Google's XOAUTH2 SMTP auth: a single-shot base64 of
 * {@code user=<u>auth=Bearer <token>}.
 */
public final class Xoauth2AuthClient implements AuthClient {

    private static final char CTRL_A = (char) 0x01;

    private final AuthCredentials credentials;
    private boolean complete;

    public Xoauth2AuthClient(AuthCredentials credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public AuthMechanism mechanism() {
        return AuthMechanism.XOAUTH2;
    }

    @Override
    public byte[] initial() {
        var tokenChars = credentials.password().get();
        var token = new String(tokenChars);
        try {
            complete = true;
            var payload = "user=" + requireNoKvsep(credentials.username()) + CTRL_A + "auth=Bearer " + token + CTRL_A
                    + CTRL_A;
            return payload.getBytes(StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(tokenChars, '\0');
        }
    }

    @Override
    public byte[] respond(byte[] challenge) {
        // Servers MAY send a base64 JSON error blob after a failed bearer — RFC says return an
        // empty client response to ack; the SMTP-level 5xx then surfaces the actual failure.
        return new byte[0];
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

    /**
     * Rejects a username carrying the field separator ({@code %x01}) or a CR/LF: the blob's fields are
     * {@code %x01}-delimited, so an injected separator would forge extra fields inside the
     * (base64-wrapped) credential.
     */
    private static String requireNoKvsep(String value) {
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == CTRL_A || character == '\r' || character == '\n') {
                throw new IllegalArgumentException("XOAUTH2 username must not contain control characters");
            }
        }
        return value;
    }
}
