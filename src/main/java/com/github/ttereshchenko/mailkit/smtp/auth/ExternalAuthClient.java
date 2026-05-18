package com.github.ttereshchenko.mailkit.smtp.auth;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * RFC 4422 EXTERNAL: the SASL layer accepts an identity verified out-of-band — typically the
 * subject CN of a mTLS client certificate. The initial response carries the optional authzid.
 */
public final class ExternalAuthClient implements AuthClient {

    private final AuthCredentials credentials;
    private boolean complete;

    public ExternalAuthClient(AuthCredentials credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public AuthMechanism mechanism() {
        return AuthMechanism.EXTERNAL;
    }

    @Override
    public byte[] initial() {
        complete = true;
        return credentials.authzId().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] respond(byte[] challenge) {
        throw new IllegalStateException("EXTERNAL does not accept challenges");
    }

    @Override
    public boolean isComplete() {
        return complete;
    }
}
