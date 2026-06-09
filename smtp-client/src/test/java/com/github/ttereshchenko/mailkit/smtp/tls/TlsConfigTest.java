package com.github.ttereshchenko.mailkit.smtp.tls;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link TlsConfig#guaranteesEncryption()}, the predicate behind the Send dialog's
 * insecure-transport warning: only REQUIRED STARTTLS and TLS-on-connect guarantee an encrypted
 * channel; NONE is cleartext and the OPTIONAL STARTTLS modes can be downgraded.
 */
class TlsConfigTest {

    @Test
    void requiredStartTlsAndTlsOnConnectGuaranteeEncryption() {
        assertTrue(TlsConfig.starttlsRequired().guaranteesEncryption());
        assertTrue(TlsConfig.tlsOnConnect().guaranteesEncryption());
    }

    @Test
    void noneAndOptionalModesDoNotGuaranteeEncryption() {
        assertFalse(TlsConfig.none().guaranteesEncryption());
        assertFalse(TlsConfig.starttlsOptional().guaranteesEncryption());
        assertFalse(TlsConfig.none()
                .withMode(TlsConfig.Mode.STARTTLS_OPTIONAL_STRICT)
                .guaranteesEncryption());
    }
}
