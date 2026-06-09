package com.github.ttereshchenko.mailkit.smtp.auth;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Carries the bits an {@link AuthClient} needs to produce wire bytes. The password is supplied
 * lazily as a fresh {@code char[]} each time so the SMTP path never has to materialize a
 * {@code String} that would linger in the heap; for token-based mechanisms (XOAUTH2 / OAUTHBEARER)
 * the password supplier returns the bearer token.
 */
public record AuthCredentials(
        String username, Supplier<char[]> password, String authzId, Map<String, String> authExtra) {

    public static final Supplier<char[]> EMPTY_PASSWORD = () -> new char[0];

    public AuthCredentials {
        username = Objects.requireNonNullElse(username, "");
        password = Objects.requireNonNullElse(password, EMPTY_PASSWORD);
        authzId = Objects.requireNonNullElse(authzId, "");
        authExtra = authExtra == null ? Map.of() : Map.copyOf(authExtra);
    }

    public static AuthCredentials of(String username, Supplier<char[]> password) {
        return new AuthCredentials(username, password, "", Map.of());
    }

    public static AuthCredentials bearer(String username, Supplier<char[]> token) {
        return new AuthCredentials(username, token, "", Map.of());
    }

    public static AuthCredentials external(String authzId) {
        return new AuthCredentials("", EMPTY_PASSWORD, authzId, Map.of());
    }
}
