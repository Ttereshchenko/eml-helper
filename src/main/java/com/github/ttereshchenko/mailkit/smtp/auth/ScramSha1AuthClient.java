package com.github.ttereshchenko.mailkit.smtp.auth;

/** Concrete RFC 5802 SCRAM-SHA-1 client. */
public final class ScramSha1AuthClient extends ScramAuthClient {

    public ScramSha1AuthClient(AuthCredentials credentials) {
        this(credentials, null);
    }

    /** Test seam — pass an explicit nonce to make assertions reproducible. */
    public ScramSha1AuthClient(AuthCredentials credentials, String clientNonceOverride) {
        super(AuthMechanism.SCRAM_SHA_1, credentials, "SHA-1", "HmacSHA1", "PBKDF2WithHmacSHA1", clientNonceOverride);
    }
}
