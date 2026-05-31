package com.github.ttereshchenko.mailkit.smtp.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression coverage for F3 (the "Verify CA chain" profile knob was inert). {@code verifyCa} was
 * persisted and plumbed into {@link TlsConfig}, but {@link TlsContextFactory} never read it — so
 * unchecking it had no effect and the JDK default trust store still validated the chain. The factory
 * now honors {@code verifyCa}: clearing it installs a trust-everything manager (skipping chain
 * validation) while hostname identification stays governed by {@code verifyHostname}.
 *
 * <p>Also covers F10 (the {@code allowSelfSigned} trust-everything + hostname-off footgun): relaxing
 * chain trust no longer doubles as a silent hostname-check bypass — the two controls are split.
 */
class TlsContextFactoryTest {

    @Test
    void verifyCaEnabledByDefaultUsesSystemTrustStore() throws Exception {
        // verifyCa defaults to true; with no CA bundle and allowSelfSigned off, trust resolution defers
        // to the JDK default trust store, signalled by a null TrustManager array.
        var managers = TlsContextFactory.resolveTrustManagers(TlsConfig.starttlsRequired());
        assertNull(managers, "verifyCa=true with no CA bundle must defer to the JDK default trust store");
    }

    @Test
    void verifyCaDisabledInstallsTrustEverythingManager() throws Exception {
        // The bug: this used to return null (chain still validated) regardless of the checkbox.
        var managers = TlsContextFactory.resolveTrustManagers(
                TlsConfig.starttlsRequired().withVerifyCa(false));
        assertNotNull(managers, "verifyCa=false must override trust resolution, not fall through to the trust store");
        assertEquals(1, managers.length);
        assertTrue(
                managers[0] instanceof TlsContextFactory.TrustEverythingManager,
                "verifyCa=false must skip chain validation via a trust-everything manager");
    }

    @Test
    void allowSelfSignedStillInstallsTrustEverythingManager() throws Exception {
        // The pre-existing per-send escape hatch must keep working unchanged.
        var managers = TlsContextFactory.resolveTrustManagers(
                TlsConfig.starttlsRequired().withAllowSelfSigned(true));
        assertNotNull(managers);
        assertTrue(managers[0] instanceof TlsContextFactory.TrustEverythingManager);
    }

    @Test
    void verifyCaDisabledStillVerifiesHostname() throws Exception {
        // Disabling CA-chain validation must NOT silently disable hostname identification — that coupling
        // is reserved for the aggressive per-send allowSelfSigned. With verifyHostname left on, the built
        // SSLParameters must still request HTTPS endpoint identification.
        var built = TlsContextFactory.build(TlsConfig.tlsOnConnect().withVerifyCa(false));
        assertEquals("HTTPS", built.parameters().getEndpointIdentificationAlgorithm());
    }

    @Test
    void allowSelfSignedStillVerifiesHostnameUnlessHostnameCheckIsExplicitlyOff() throws Exception {
        // F10: allowSelfSigned used to ALSO switch off hostname identification under one flag — a
        // trust-everything + hostname-off footgun. The two concerns are now split: accepting a
        // self-signed cert relaxes only chain trust, so with verifyHostname left on the built
        // SSLParameters must still request HTTPS endpoint identification (this assertion fails on the
        // pre-fix code, which forced the algorithm to null whenever allowSelfSigned was set).
        var selfSignedOnly = TlsContextFactory.build(TlsConfig.tlsOnConnect().withAllowSelfSigned(true));
        assertEquals(
                "HTTPS",
                selfSignedOnly.parameters().getEndpointIdentificationAlgorithm(),
                "allowSelfSigned must relax chain trust only, not silently disable the hostname check");

        // Disabling the hostname check is now its own explicit decision, independent of chain trust.
        var hostnameOffToo = TlsContextFactory.build(
                TlsConfig.tlsOnConnect().withAllowSelfSigned(true).withVerifyHostname(false));
        assertNull(
                hostnameOffToo.parameters().getEndpointIdentificationAlgorithm(),
                "clearing verifyHostname is the explicit way to turn the hostname check off");
    }
}
