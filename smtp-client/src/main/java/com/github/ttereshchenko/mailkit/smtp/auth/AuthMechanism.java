package com.github.ttereshchenko.mailkit.smtp.auth;

import java.util.Locale;

/**
 * SASL mechanisms supported by the client. Wire names match what servers advertise in EHLO.
 * NTLM is deferred per the Phase 2 decision; modern M365 SMTP uses OAuth2 and on-prem Exchange
 * is the only legacy NTLM-SMTP holdout.
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

    /** PLAIN, LOGIN, and CRAM-MD5 expose credentials in cleartext on the wire and require TLS. */
    public boolean isPlaintextOnTheWire() {
        return this == PLAIN || this == LOGIN || this == CRAM_MD5;
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
