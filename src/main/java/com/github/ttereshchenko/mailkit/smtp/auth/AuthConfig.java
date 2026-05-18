package com.github.ttereshchenko.mailkit.smtp.auth;

import java.util.Map;
import java.util.Objects;

/**
 * AUTH knobs attached to {@link com.github.ttereshchenko.mailkit.smtp.SmtpConfig SmtpConfig}. The
 * mechanism may be {@code null} (AUTO mode — pick strongest advertised). Plaintext-vulnerable
 * mechanisms over a non-TLS socket are refused unless {@link #allowPlaintextAuth} is explicitly
 * set per-send.
 */
public record AuthConfig(
        AuthMechanism mechanism,
        AuthCredentials credentials,
        Map<String, String> authMap,
        boolean allowPlaintextAuth,
        boolean optional,
        boolean optionalStrict) {

    public AuthConfig {
        Objects.requireNonNull(credentials, "credentials");
        authMap = authMap == null ? Map.of() : Map.copyOf(authMap);
    }

    /** No authentication — used when the server does not require it. */
    public static AuthConfig disabled() {
        return new AuthConfig(
                null, AuthCredentials.of("", AuthCredentials.EMPTY_PASSWORD), Map.of(), false, true, false);
    }

    public static AuthConfig auto(AuthCredentials credentials) {
        return new AuthConfig(null, credentials, Map.of(), false, false, false);
    }

    public static AuthConfig forMechanism(AuthMechanism mechanism, AuthCredentials credentials) {
        return new AuthConfig(mechanism, credentials, Map.of(), false, false, false);
    }

    public boolean isDisabled() {
        return mechanism == null && optional;
    }

    public AuthConfig withAllowPlaintextAuth(boolean allow) {
        return new AuthConfig(mechanism, credentials, authMap, allow, optional, optionalStrict);
    }
}
