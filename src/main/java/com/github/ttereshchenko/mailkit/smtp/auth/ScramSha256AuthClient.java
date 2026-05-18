package com.github.ttereshchenko.mailkit.smtp.auth;

/** Concrete RFC 7677 SCRAM-SHA-256 client. */
public final class ScramSha256AuthClient extends ScramAuthClient {

    public ScramSha256AuthClient(AuthCredentials credentials) {
        this(credentials, null);
    }

    /** Test seam — pass an explicit nonce to make assertions reproducible. */
    public ScramSha256AuthClient(AuthCredentials credentials, String clientNonceOverride) {
        super(
                AuthMechanism.SCRAM_SHA_256,
                credentials,
                "SHA-256",
                "HmacSHA256",
                "PBKDF2WithHmacSHA256",
                clientNonceOverride);
    }
}
