package com.github.ttereshchenko.mailkit.smtp;

import com.github.ttereshchenko.mailkit.smtp.tls.PeerCertSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Per-connection state: the server's greeting, advertised ESMTP capabilities, and the current
 * lifecycle {@link Phase}. The capabilities map is keyed by upper-cased keyword (e.g. {@code AUTH},
 * {@code STARTTLS}, {@code SIZE}) so callers can interrogate it without worrying about case.
 */
public final class SmtpSession {

    private String greeting = "";
    private final Map<String, List<String>> capabilities = new LinkedHashMap<>();
    private Phase currentPhase = Phase.CONNECT;
    private boolean tlsActive;

    public synchronized String greeting() {
        return greeting;
    }

    public synchronized void setGreeting(String value) {
        greeting = Objects.requireNonNullElse(value, "");
    }

    public synchronized void replaceCapabilities(Map<String, List<String>> advertised) {
        Objects.requireNonNull(advertised, "advertised");
        capabilities.clear();
        for (var entry : advertised.entrySet()) {
            capabilities.put(entry.getKey().toUpperCase(Locale.ROOT), List.copyOf(entry.getValue()));
        }
    }

    public synchronized boolean supports(String keyword) {
        return capabilities.containsKey(keyword.toUpperCase(Locale.ROOT));
    }

    public synchronized List<String> capabilityArguments(String keyword) {
        var values = capabilities.get(keyword.toUpperCase(Locale.ROOT));
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }

    public synchronized Map<String, List<String>> capabilities() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
    }

    public synchronized Phase currentPhase() {
        return currentPhase;
    }

    public synchronized void enterPhase(Phase phase) {
        currentPhase = Objects.requireNonNull(phase, "phase");
    }

    public synchronized boolean tlsActive() {
        return tlsActive;
    }

    private String tlsProtocol = "";
    private String tlsCipherSuite = "";
    private PeerCertSnapshot tlsPeer = PeerCertSnapshot.empty();

    public synchronized void markTlsActive(String protocol, String cipherSuite, PeerCertSnapshot peer) {
        tlsActive = true;
        tlsProtocol = Objects.requireNonNullElse(protocol, "");
        tlsCipherSuite = Objects.requireNonNullElse(cipherSuite, "");
        tlsPeer = peer == null ? PeerCertSnapshot.empty() : peer;
    }

    public synchronized SendResult.TlsOutcome tlsOutcome() {
        if (!tlsActive) {
            return SendResult.TlsOutcome.none();
        }
        return new SendResult.TlsOutcome(true, tlsProtocol, tlsCipherSuite, tlsPeer);
    }
}
