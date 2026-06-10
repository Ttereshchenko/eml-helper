package com.github.ttereshchenko.mailkit.smtp.auth;

import java.util.Locale;

/**
 * SASL mechanisms supported by the client. Wire names match what servers advertise in EHLO.
 * NTLM is intentionally unsupported: modern M365 SMTP uses OAuth2 and on-prem Exchange is the
 * only legacy NTLM-SMTP holdout.
 */
public enum AuthMechanism {
    PLAIN("PLAIN"),
    LOGIN("LOGIN"),
    CRAM_MD5("CRAM-MD5"),
    DIGEST_MD5("DIGEST-MD5"),
    SCRAM_SHA_1("SCRAM-SHA-1"),
    SCRAM_SHA_256("SCRAM-SHA-256"),
    EXTERNAL("EXTERNAL"),
    XOAUTH2("XOAUTH2"),
    OAUTHBEARER("OAUTHBEARER");

    private final String wireName;

    AuthMechanism(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /**
     * Mechanisms that put a directly reusable credential on the wire and therefore require TLS
     * (or an explicit {@code allowPlaintextAuth} override): PLAIN and LOGIN carry the password
     * itself, XOAUTH2 and OAUTHBEARER carry a live bearer token (as good as a password to anyone
     * sniffing), and CRAM-MD5 is included conservatively — it does not reveal the password but is
     * cheap to crack offline from a captured exchange.
     */
    public boolean isPlaintextOnTheWire() {
        return this == PLAIN || this == LOGIN || this == CRAM_MD5 || this == XOAUTH2 || this == OAUTHBEARER;
    }

    public static AuthMechanism fromWireName(String name) {
        var upper = name.toUpperCase(Locale.ROOT);
        for (var mechanism : values()) {
            if (mechanism.wireName.equalsIgnoreCase(upper)) {
                return mechanism;
            }
        }
        return null;
    }
}
