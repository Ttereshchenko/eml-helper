package com.github.ttereshchenko.mailkit.smtp.auth;

import javax.security.sasl.SaslException;

/** Builds a fresh {@link AuthClient} per send so per-connection state never leaks across sends. */
public final class AuthClients {

    private AuthClients() {}

    public static AuthClient create(AuthMechanism mechanism, AuthCredentials credentials, String serverName)
            throws SaslException {
        return switch (mechanism) {
            case PLAIN -> new PlainAuthClient(credentials);
            case LOGIN -> new LoginAuthClient(credentials);
            case CRAM_MD5, DIGEST_MD5 -> new SaslAuthClient(mechanism, credentials, serverName);
            case SCRAM_SHA_1 -> new ScramSha1AuthClient(credentials);
            case SCRAM_SHA_256 -> new ScramSha256AuthClient(credentials);
            case EXTERNAL -> new ExternalAuthClient(credentials);
            case XOAUTH2 -> new Xoauth2AuthClient(credentials);
            case OAUTHBEARER -> new OauthBearerAuthClient(credentials);
        };
    }
}
