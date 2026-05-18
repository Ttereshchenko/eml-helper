package com.github.ttereshchenko.mailkit.smtp.auth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * RFC 7628 OAUTHBEARER: single-shot
 * {@code n,a=<authzid>,host=<host>port=<port>auth=Bearer <token>}.
 * Host / port come from {@link AuthCredentials#authExtra()} under keys {@code host} and
 * {@code port}; both are optional.
 */
public final class OauthBearerAuthClient implements AuthClient {

    private static final char CTRL_A = (char) 0x01;

    private final AuthCredentials credentials;
    private boolean complete;

    public OauthBearerAuthClient(AuthCredentials credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public AuthMechanism mechanism() {
        return AuthMechanism.OAUTHBEARER;
    }

    @Override
    public byte[] initial() {
        var tokenChars = credentials.password().get();
        var token = new String(tokenChars);
        try {
            var builder = new StringBuilder();
            builder.append("n,");
            if (!credentials.username().isBlank()) {
                builder.append("a=").append(credentials.username());
            }
            builder.append(",").append(CTRL_A);
            var host = credentials.authExtra().get("host");
            if (host != null && !host.isBlank()) {
                builder.append("host=").append(host).append(CTRL_A);
            }
            var port = credentials.authExtra().get("port");
            if (port != null && !port.isBlank()) {
                builder.append("port=").append(port).append(CTRL_A);
            }
            builder.append("auth=Bearer ").append(token).append(CTRL_A).append(CTRL_A);
            complete = true;
            return builder.toString().getBytes(StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(tokenChars, '\0');
        }
    }

    @Override
    public byte[] respond(byte[] challenge) {
        return new byte[0];
    }

    @Override
    public boolean isComplete() {
        return complete;
    }
}
