package com.github.ttereshchenko.mailkit.smtp.audit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One row in the per-project SMTP send log. Captures *what* and *where to* — never credentials,
 * never message bytes, never private-key material. The retention policy is in
 * {@link SmtpAuditLog}; this record is just the data shape.
 */
public record SmtpAuditEntry(
        Instant timestamp,
        String profileName,
        String host,
        int port,
        String tlsProtocol,
        String tlsCipherSuite,
        String authMechanism,
        String envelopeFrom,
        List<Recipient> recipients,
        long sourceBytes,
        long durationMillis,
        String stopAfterPhase,
        boolean dropAfter,
        boolean success,
        String errorKind,
        String errorPhase,
        String errorMessage) {

    public SmtpAuditEntry {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(host, "host");
        recipients = recipients == null ? List.of() : List.copyOf(recipients);
        profileName = profileName == null ? "" : profileName;
        tlsProtocol = tlsProtocol == null ? "" : tlsProtocol;
        tlsCipherSuite = tlsCipherSuite == null ? "" : tlsCipherSuite;
        authMechanism = authMechanism == null ? "" : authMechanism;
        envelopeFrom = envelopeFrom == null ? "" : envelopeFrom;
        stopAfterPhase = stopAfterPhase == null ? "" : stopAfterPhase;
        errorKind = errorKind == null ? "" : errorKind;
        errorPhase = errorPhase == null ? "" : errorPhase;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public record Recipient(String address, int code, String text, boolean accepted) {
        public Recipient {
            Objects.requireNonNull(address, "address");
            text = text == null ? "" : text;
        }
    }
}
