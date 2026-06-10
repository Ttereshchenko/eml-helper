package com.github.ttereshchenko.mailkit.smtp.auth;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Carries the bits an {@link AuthClient} needs to produce wire bytes. The password is supplied
 * lazily as a fresh {@code char[]} each time and the clients zero their copies after use —
 * best-effort hygiene only, since base64 encoding and the (redacted-by-default) transcript still
 * materialize transient copies. For token-based mechanisms (XOAUTH2 / OAUTHBEARER) the password
 * supplier returns the bearer token.
 *
 * <p>{@link Kind} records what the secret actually is, so AUTO mechanism selection never pairs a
 * password with a bearer-token mechanism or vice versa.
 */
public record AuthCredentials(
        String username, Supplier<char[]> password, String authzId, Map<String, String> authExtra, Kind kind) {

    /** What the {@link #password()} supplier actually yields. */
    public enum Kind {
        PASSWORD,
        BEARER_TOKEN,
        EXTERNAL
    }

    public static final Supplier<char[]> EMPTY_PASSWORD = () -> new char[0];

    public AuthCredentials {
        username = Objects.requireNonNullElse(username, "");
        password = Objects.requireNonNullElse(password, EMPTY_PASSWORD);
        authzId = Objects.requireNonNullElse(authzId, "");
        authExtra = authExtra == null ? Map.of() : Map.copyOf(authExtra);
        kind = Objects.requireNonNullElse(kind, Kind.PASSWORD);
    }

    /** Compatibility constructor — assumes a {@link Kind#PASSWORD} secret. */
    public AuthCredentials(String username, Supplier<char[]> password, String authzId, Map<String, String> authExtra) {
        this(username, password, authzId, authExtra, Kind.PASSWORD);
    }

    public static AuthCredentials of(String username, Supplier<char[]> password) {
        return new AuthCredentials(username, password, "", Map.of(), Kind.PASSWORD);
    }

    public static AuthCredentials bearer(String username, Supplier<char[]> token) {
        return new AuthCredentials(username, token, "", Map.of(), Kind.BEARER_TOKEN);
    }

    public static AuthCredentials external(String authzId) {
        return new AuthCredentials("", EMPTY_PASSWORD, authzId, Map.of(), Kind.EXTERNAL);
    }
}
