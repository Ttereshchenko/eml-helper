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
                builder.append("a=").append(requireNoKvsep(credentials.username(), "username"));
            }
            builder.append(",").append(CTRL_A);
            var host = credentials.authExtra().get("host");
            if (host != null && !host.isBlank()) {
                builder.append("host=").append(requireNoKvsep(host, "host")).append(CTRL_A);
            }
            var port = credentials.authExtra().get("port");
            if (port != null && !port.isBlank()) {
                builder.append("port=").append(requireNoKvsep(port, "port")).append(CTRL_A);
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
        // rfc7628 §3.2.3: after a failed initial response the server sends its base64 JSON error as
        // a continuation; the client acknowledges with a SINGLE %x01 (CTRL-A) octet — NOT an empty
        // line — so the server can then emit the final SASL/SMTP failure. (XOAUTH2, a separate
        // Google mechanism, uses an empty response here instead — see Xoauth2AuthClient.)
        return new byte[] {0x01};
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

    /**
     * Rejects a gs2 field value carrying the SASL key/value separator ({@code %x01}) or a CR/LF
     * (rfc7628 §3.1: a {@code value} must not contain {@code kvsep}). Without this an injected
     * {@code %x01} would forge extra SASL fields inside the (base64-wrapped) blob.
     */
    private static String requireNoKvsep(String value, String field) {
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == CTRL_A || character == '\r' || character == '\n') {
                throw new IllegalArgumentException("OAUTHBEARER " + field + " must not contain control characters");
            }
        }
        return value;
    }
}
