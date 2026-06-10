package com.github.ttereshchenko.mailkit.smtp.auth;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Picks an {@link AuthMechanism} from those advertised by the server. AUTO chooses the strongest
 * mechanism the server offers; an explicit choice short-circuits selection and is honored if
 * advertised. The optional {@code authMap} renames non-standard server keywords onto known
 * mechanisms (e.g. servers that advertise {@code AUTHENTICATE} or vendor-prefixed names).
 */
public final class AuthMechanismSelector {

    /** Strength order, strongest first. NTLM intentionally absent. */
    private static final List<AuthMechanism> AUTO_ORDER = List.of(
            AuthMechanism.SCRAM_SHA_256,
            AuthMechanism.SCRAM_SHA_1,
            AuthMechanism.DIGEST_MD5,
            AuthMechanism.CRAM_MD5,
            AuthMechanism.XOAUTH2,
            AuthMechanism.OAUTHBEARER,
            AuthMechanism.EXTERNAL,
            AuthMechanism.PLAIN,
            AuthMechanism.LOGIN);

    private final Map<String, String> authMap;

    public AuthMechanismSelector(Map<String, String> authMap) {
        this.authMap = authMap == null ? Map.of() : Map.copyOf(authMap);
    }

    /** No remapping; AUTO picks the strongest advertised. */
    public AuthMechanismSelector() {
        this(Map.of());
    }

    /** AUTO selection without credential-kind filtering — prefer {@link #pick(AuthMechanism, List, AuthCredentials.Kind)}. */
    public AuthMechanism pick(AuthMechanism requested, List<String> advertised) {
        return pick(requested, advertised, null);
    }

    /**
     * Picks a mechanism. An explicit {@code requested} mechanism is honored when advertised,
     * regardless of credential kind. In AUTO mode ({@code requested == null}) candidates that
     * cannot work with the supplied credential kind are skipped — a password is never sent as a
     * bearer token and vice versa. A null {@code credentialKind} disables the filter.
     */
    public AuthMechanism pick(AuthMechanism requested, List<String> advertised, AuthCredentials.Kind credentialKind) {
        Objects.requireNonNull(advertised, "advertised");
        var resolved = applyMap(advertised);
        if (requested != null) {
            return resolved.contains(requested) ? requested : null;
        }
        for (var candidate : AUTO_ORDER) {
            if (resolved.contains(candidate) && isCompatible(candidate, credentialKind)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isCompatible(AuthMechanism mechanism, AuthCredentials.Kind credentialKind) {
        if (credentialKind == null) {
            return true;
        }
        return switch (credentialKind) {
            case PASSWORD ->
                switch (mechanism) {
                    case SCRAM_SHA_256, SCRAM_SHA_1, DIGEST_MD5, CRAM_MD5, PLAIN, LOGIN -> true;
                    case XOAUTH2, OAUTHBEARER, EXTERNAL -> false;
                };
            case BEARER_TOKEN -> mechanism == AuthMechanism.XOAUTH2 || mechanism == AuthMechanism.OAUTHBEARER;
            case EXTERNAL -> mechanism == AuthMechanism.EXTERNAL;
        };
    }

    private List<AuthMechanism> applyMap(List<String> advertised) {
        return advertised.stream()
                .map(name -> authMap.getOrDefault(name.toUpperCase(Locale.ROOT), name))
                .map(AuthMechanism::fromWireName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
