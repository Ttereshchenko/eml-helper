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

    public AuthMechanism pick(AuthMechanism requested, List<String> advertised) {
        Objects.requireNonNull(advertised, "advertised");
        var resolved = applyMap(advertised);
        if (requested != null) {
            return resolved.contains(requested) ? requested : null;
        }
        for (var candidate : AUTO_ORDER) {
            if (resolved.contains(candidate)) {
                return candidate;
            }
        }
        return null;
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
