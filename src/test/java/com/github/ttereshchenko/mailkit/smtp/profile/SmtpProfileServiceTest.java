package com.github.ttereshchenko.mailkit.smtp.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SmtpProfileServiceTest {

    @Test
    void upsertAddsNewProfileAndMakesFirstOneDefault() {
        var service = new SmtpProfileService();
        var profile = new SmtpProfile();
        profile.name = "primary";
        profile.host = "smtp.example.com";

        service.upsert(profile);

        var stored = service.getProfiles();
        assertEquals(1, stored.size());
        assertTrue(stored.get(0).isDefault, "first inserted profile becomes the default");
    }

    @Test
    void upsertReplacesProfileWithSameId() {
        var service = new SmtpProfileService();
        var first = new SmtpProfile();
        first.identifier = "fixed-id";
        first.name = "v1";
        service.upsert(first);

        var update = new SmtpProfile();
        update.identifier = "fixed-id";
        update.name = "v2";
        service.upsert(update);

        var stored = service.getProfiles();
        assertEquals(1, stored.size());
        assertEquals("v2", stored.get(0).name);
    }

    @Test
    void removeReassignsDefaultToNextProfile() {
        var service = new SmtpProfileService();
        var first = new SmtpProfile();
        first.name = "a";
        service.upsert(first);
        var second = new SmtpProfile();
        second.name = "b";
        service.upsert(second);

        var profiles = service.getProfiles();
        var firstId = profiles.get(0).identifier;
        service.remove(firstId);

        var after = service.getProfiles();
        assertEquals(1, after.size());
        assertTrue(after.get(0).isDefault, "remaining profile takes default after removal of the previous default");
    }

    @Test
    void setDefaultMakesExactlyOneProfileDefault() {
        var service = new SmtpProfileService();
        var first = new SmtpProfile();
        first.name = "a";
        service.upsert(first);
        var second = new SmtpProfile();
        second.name = "b";
        service.upsert(second);

        var profiles = service.getProfiles();
        var secondId = profiles.get(1).identifier;
        service.setDefault(secondId);

        var after = service.getProfiles();
        assertFalse(after.get(0).isDefault);
        assertTrue(after.get(1).isDefault);
    }

    @Test
    void loadStateRoundTripsAllFields() {
        var service = new SmtpProfileService();
        var state = new SmtpProfileService.State();
        state.egressEnabled = false;
        state.showEditorToolbarButton = false;
        var profile = new SmtpProfile();
        profile.identifier = "abc";
        profile.name = "Mailpit";
        profile.host = "localhost";
        profile.port = 1025;
        profile.isDefault = true;
        profile.authzId = "alice@example.com";
        profile.authOptionalStrict = true;
        profile.declareSizeOnMail = false;
        profile.protocols.add("TLSv1.3");
        profile.cipherSuites.add("TLS_AES_256_GCM_SHA384");
        profile.proxyProtocol.version = SmtpProfile.ProxyVersion.V1;
        profile.proxyProtocol.sourceAddress = "10.0.0.1";
        profile.proxyProtocol.sourcePort = 12345;
        profile.proxyProtocol.destAddress = "10.0.0.2";
        profile.proxyProtocol.destPort = 25;
        profile.xclient.addr = "10.0.0.3";
        profile.xclient.extra.add(new SmtpProfile.DefaultHeader("VENDOR", "v"));
        state.profiles.add(profile);

        service.loadState(state);

        assertFalse(service.isEgressEnabled());
        assertFalse(service.isShowEditorToolbarButton());
        var loaded = service.getProfiles();
        assertEquals(1, loaded.size());
        var roundTripped = loaded.get(0);
        assertEquals("Mailpit", roundTripped.name);
        assertEquals(1025, roundTripped.port);
        assertTrue(roundTripped.isDefault);
        assertEquals("alice@example.com", roundTripped.authzId);
        assertTrue(roundTripped.authOptionalStrict);
        assertFalse(roundTripped.declareSizeOnMail);
        assertEquals(List.of("TLSv1.3"), roundTripped.protocols);
        assertEquals(List.of("TLS_AES_256_GCM_SHA384"), roundTripped.cipherSuites);
        assertEquals(SmtpProfile.ProxyVersion.V1, roundTripped.proxyProtocol.version);
        assertEquals("10.0.0.1", roundTripped.proxyProtocol.sourceAddress);
        assertEquals(12345, roundTripped.proxyProtocol.sourcePort);
        assertEquals("10.0.0.3", roundTripped.xclient.addr);
        assertEquals(1, roundTripped.xclient.extra.size());
        assertEquals("VENDOR", roundTripped.xclient.extra.get(0).name);
    }

    @Test
    void newProfileDefaultsProxyAndXclientDisabled() {
        var profile = new SmtpProfile();
        assertEquals(SmtpProfile.ProxyVersion.NONE, profile.proxyProtocol.version);
        assertTrue(profile.xclient.optional);
        assertTrue(profile.xclient.addr.isEmpty());
        assertTrue(profile.xclient.extra.isEmpty());
        assertTrue(profile.protocols.isEmpty());
        assertTrue(profile.cipherSuites.isEmpty());
    }

    @Test
    void copyDeepCopiesProxyAndXclient() {
        var profile = new SmtpProfile();
        profile.proxyProtocol.version = SmtpProfile.ProxyVersion.V2;
        profile.proxyProtocol.sourceAddress = "10.0.0.1";
        profile.xclient.addr = "10.0.0.3";
        profile.xclient.extra.add(new SmtpProfile.DefaultHeader("VENDOR", "v"));

        var clone = profile.copy();
        clone.proxyProtocol.version = SmtpProfile.ProxyVersion.NONE;
        clone.proxyProtocol.sourceAddress = "changed";
        clone.xclient.addr = "changed";
        clone.xclient.extra.get(0).value = "changed";

        assertEquals(SmtpProfile.ProxyVersion.V2, profile.proxyProtocol.version);
        assertEquals("10.0.0.1", profile.proxyProtocol.sourceAddress);
        assertEquals("10.0.0.3", profile.xclient.addr);
        assertEquals("v", profile.xclient.extra.get(0).value);
    }

    @Test
    void egressToggleDefaultsToOn() {
        var service = new SmtpProfileService();
        assertTrue(service.isEgressEnabled());
        service.setEgressEnabled(false);
        assertFalse(service.isEgressEnabled());
    }

    @Test
    void showEditorToolbarButtonDefaultsToTrue() {
        var service = new SmtpProfileService();
        assertTrue(service.isShowEditorToolbarButton());
        service.setShowEditorToolbarButton(false);
        assertFalse(service.isShowEditorToolbarButton());
    }

    @Test
    void newProfileSeedsFromAndToDefaultHeadersWithEmptyValues() {
        var profile = new SmtpProfile();

        assertEquals(2, profile.defaultHeaders.size());
        assertEquals("From", profile.defaultHeaders.get(0).name);
        assertEquals("To", profile.defaultHeaders.get(1).name);
        assertEquals("", profile.findDefaultHeaderValue("From"));
    }

    @Test
    void newProfileDefaultsProtocolToEsmtp() {
        var profile = new SmtpProfile();
        assertEquals(SmtpProfile.Protocol.ESMTP, profile.protocol);
    }

    @Test
    void findDefaultHeaderValueIsCaseInsensitiveAndReturnsEmptyWhenAbsent() {
        var profile = new SmtpProfile();
        profile.defaultHeaders.get(0).value = "sender@example.com";

        assertEquals("sender@example.com", profile.findDefaultHeaderValue("FROM"));
        assertEquals("sender@example.com", profile.findDefaultHeaderValue("from"));
        profile.defaultHeaders.clear();
        assertEquals("", profile.findDefaultHeaderValue("From"));
    }

    @Test
    void copyDeepCopiesDefaultHeaders() {
        var profile = new SmtpProfile();
        profile.defaultHeaders.get(0).value = "a@b";

        var clone = profile.copy();
        clone.defaultHeaders.get(0).value = "changed";

        assertEquals("a@b", profile.defaultHeaders.get(0).value);
        assertEquals("changed", clone.defaultHeaders.get(0).value);
    }

    @Test
    void findDefaultReturnsDefaultProfile() {
        var service = new SmtpProfileService();
        var profile = new SmtpProfile();
        profile.name = "default-one";
        service.upsert(profile);

        var resolved = service.findDefault();
        assertTrue(resolved.isPresent());
        assertEquals("default-one", resolved.get().name);
    }
}
