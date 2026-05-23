package com.github.ttereshchenko.mailkit.smtp.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.proxy.ProxyConfig;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link SmtpProfiles#toConfig(SmtpProfile, SmtpCredentialStore)} maps every
 * newly persisted profile field (PROXY, XCLIENT, TLS protocols/ciphers, ESMTP declareSizeOnMail)
 * into the runtime {@code SmtpConfig}. AuthMechanism is left as {@code DISABLED} so the
 * credential-store branch is never taken — that lets the test run as a plain JUnit Jupiter case.
 */
class SmtpProfilesToConfigTest {

    @Test
    void defaultProfileMapsToDisabledProxyAndXclientAndEmptyTlsLists() {
        var profile = baseProfile();

        var config = SmtpProfiles.toConfig(profile, null);

        assertFalse(config.proxy().isEnabled());
        assertFalse(config.xclient().isEnabled());
        assertTrue(config.tls().protocols().isEmpty());
        assertTrue(config.tls().cipherSuites().isEmpty());
        assertTrue(config.esmtp().declareSizeOnMail());
    }

    @Test
    void v1ProxyAddressesPropagate() {
        var profile = baseProfile();
        profile.proxyProtocol.version = SmtpProfile.ProxyVersion.V1;
        profile.proxyProtocol.command = SmtpProfile.ProxyCommand.PROXY;
        profile.proxyProtocol.family = SmtpProfile.ProxyFamily.TCP4;
        profile.proxyProtocol.sourceAddress = "10.0.0.1";
        profile.proxyProtocol.sourcePort = 1111;
        profile.proxyProtocol.destAddress = "10.0.0.2";
        profile.proxyProtocol.destPort = 2222;

        var proxy = SmtpProfiles.toConfig(profile, null).proxy();

        assertEquals(ProxyConfig.Version.V1, proxy.version());
        assertEquals(ProxyConfig.Family.TCP4, proxy.family());
        assertEquals("10.0.0.1", proxy.sourceAddress());
        assertEquals(1111, proxy.sourcePort());
        assertEquals("10.0.0.2", proxy.destAddress());
        assertEquals(2222, proxy.destPort());
    }

    @Test
    void xclientAddrAndExtraAttributesPropagate() {
        var profile = baseProfile();
        profile.xclient.addr = "203.0.113.7";
        profile.xclient.helo = "client.example";
        profile.xclient.optional = false;
        profile.xclient.beforeStartTls = true;
        profile.xclient.extra.add(new SmtpProfile.DefaultHeader("VENDOR", "v1"));

        var xclient = SmtpProfiles.toConfig(profile, null).xclient();

        assertTrue(xclient.isEnabled());
        assertEquals("203.0.113.7", xclient.addr());
        assertEquals("client.example", xclient.helo());
        assertFalse(xclient.optional());
        assertTrue(xclient.beforeStartTls());
        assertEquals("v1", xclient.extra().get("VENDOR"));
    }

    @Test
    void emptyXclientSettingsStayDisabled() {
        var profile = baseProfile();
        // All XCLIENT fields blank by default; verify toConfig returns the disabled sentinel.
        var xclient = SmtpProfiles.toConfig(profile, null).xclient();
        assertFalse(xclient.isEnabled());
    }

    @Test
    void tlsProtocolsAndCipherSuitesPropagate() {
        var profile = baseProfile();
        profile.protocols.add("TLSv1.3");
        profile.protocols.add("TLSv1.2");
        profile.cipherSuites.add("TLS_AES_256_GCM_SHA384");

        var tls = SmtpProfiles.toConfig(profile, null).tls();

        assertEquals(java.util.List.of("TLSv1.3", "TLSv1.2"), tls.protocols());
        assertEquals(java.util.List.of("TLS_AES_256_GCM_SHA384"), tls.cipherSuites());
    }

    @Test
    void declareSizeOnMailToggleFlowsThrough() {
        var profile = baseProfile();
        profile.declareSizeOnMail = false;

        var esmtp = SmtpProfiles.toConfig(profile, null).esmtp();

        assertFalse(esmtp.declareSizeOnMail());
    }

    private static SmtpProfile baseProfile() {
        var profile = new SmtpProfile();
        profile.host = "smtp.example.com";
        profile.port = 587;
        profile.authMechanism = SmtpProfile.AuthMechanismChoice.DISABLED;
        return profile;
    }
}
