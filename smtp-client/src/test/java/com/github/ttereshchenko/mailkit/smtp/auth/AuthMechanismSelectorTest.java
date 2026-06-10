package com.github.ttereshchenko.mailkit.smtp.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthMechanismSelectorTest {

    @Test
    void autoPicksStrongestAdvertised() {
        var picked = new AuthMechanismSelector()
                .pick(null, List.of("LOGIN", "PLAIN", "CRAM-MD5", "SCRAM-SHA-256", "DIGEST-MD5"));
        assertEquals(AuthMechanism.SCRAM_SHA_256, picked);
    }

    @Test
    void autoFallsToScramSha1WhenSha256Unavailable() {
        var picked = new AuthMechanismSelector().pick(null, List.of("LOGIN", "PLAIN", "SCRAM-SHA-1"));
        assertEquals(AuthMechanism.SCRAM_SHA_1, picked);
    }

    @Test
    void explicitChoiceIsHonouredWhenAdvertised() {
        var picked = new AuthMechanismSelector().pick(AuthMechanism.PLAIN, List.of("PLAIN", "LOGIN", "SCRAM-SHA-256"));
        assertEquals(AuthMechanism.PLAIN, picked);
    }

    @Test
    void explicitChoiceNotAdvertisedReturnsNull() {
        var picked = new AuthMechanismSelector().pick(AuthMechanism.SCRAM_SHA_256, List.of("LOGIN", "PLAIN"));
        assertNull(picked);
    }

    @Test
    void authMapRenamesVendorSpecificKeywords() {
        var selector = new AuthMechanismSelector(Map.of("EXCHANGE-AUTH", "PLAIN"));
        var picked = selector.pick(null, List.of("EXCHANGE-AUTH"));
        assertEquals(AuthMechanism.PLAIN, picked);
    }

    @Test
    void emptyAdvertisedReturnsNull() {
        var picked = new AuthMechanismSelector().pick(null, List.of());
        assertNull(picked);
    }

    @Test
    void autoWithPasswordCredentialsSkipsBearerMechanisms() {
        // XOAUTH2 outranks PLAIN in AUTO order, but a password must never be sent as a token.
        var picked = new AuthMechanismSelector()
                .pick(null, List.of("XOAUTH2", "OAUTHBEARER", "PLAIN"), AuthCredentials.Kind.PASSWORD);
        assertEquals(AuthMechanism.PLAIN, picked);
    }

    @Test
    void autoWithBearerTokenOnlyConsidersBearerMechanisms() {
        var picked = new AuthMechanismSelector()
                .pick(null, List.of("SCRAM-SHA-256", "PLAIN", "XOAUTH2"), AuthCredentials.Kind.BEARER_TOKEN);
        assertEquals(AuthMechanism.XOAUTH2, picked);
    }

    @Test
    void autoWithExternalCredentialsOnlyConsidersExternal() {
        var picked =
                new AuthMechanismSelector().pick(null, List.of("PLAIN", "EXTERNAL"), AuthCredentials.Kind.EXTERNAL);
        assertEquals(AuthMechanism.EXTERNAL, picked);
    }

    @Test
    void explicitChoiceBypassesCredentialKindFilter() {
        var picked = new AuthMechanismSelector()
                .pick(AuthMechanism.XOAUTH2, List.of("XOAUTH2"), AuthCredentials.Kind.PASSWORD);
        assertEquals(AuthMechanism.XOAUTH2, picked);
    }
}
