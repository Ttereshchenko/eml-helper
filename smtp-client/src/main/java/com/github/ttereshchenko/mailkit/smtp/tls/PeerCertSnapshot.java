package com.github.ttereshchenko.mailkit.smtp.tls;

import java.util.List;
import java.util.Objects;

/**
 * Serializable snapshot of the server certificate chain captured immediately after the TLS
 * handshake. The UI surfaces this in the result panel without ever needing the live socket.
 */
public record PeerCertSnapshot(
        String subject,
        String issuer,
        List<String> subjectAlternativeNames,
        String fingerprintSha256,
        String chainPem) {

    public PeerCertSnapshot {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(issuer, "issuer");
        subjectAlternativeNames = List.copyOf(subjectAlternativeNames);
        Objects.requireNonNull(fingerprintSha256, "fingerprintSha256");
        Objects.requireNonNull(chainPem, "chainPem");
    }

    public static PeerCertSnapshot empty() {
        return new PeerCertSnapshot("", "", List.of(), "", "");
    }

    public boolean isEmpty() {
        return subject.isEmpty() && chainPem.isEmpty();
    }
}
