package com.github.ttereshchenko.mailkit.smtp;

import com.github.ttereshchenko.mailkit.smtp.tls.PeerCertSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of a single send: per-recipient disposition, total wall time, the last phase reached,
 * the server's advertised capabilities from EHLO, and the negotiated TLS state.
 */
public record SendResult(
        SmtpTranscript transcript,
        List<RecipientDisposition> recipientDispositions,
        Duration duration,
        Phase lastPhaseReached,
        boolean cleanlyClosed,
        Map<String, List<String>> serverCapabilities,
        TlsOutcome tls) {

    public record RecipientDisposition(String address, int code, String text, boolean accepted) {
        public RecipientDisposition {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(text, "text");
        }
    }

    /** Captures what was negotiated on the wire (or {@link #none()} when TLS was not used). */
    public record TlsOutcome(boolean active, String protocol, String cipherSuite, PeerCertSnapshot peer) {
        public TlsOutcome {
            Objects.requireNonNull(protocol, "protocol");
            Objects.requireNonNull(cipherSuite, "cipherSuite");
            Objects.requireNonNull(peer, "peer");
        }

        public static TlsOutcome none() {
            return new TlsOutcome(false, "", "", PeerCertSnapshot.empty());
        }
    }

    public SendResult {
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(recipientDispositions, "recipientDispositions");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(lastPhaseReached, "lastPhaseReached");
        Objects.requireNonNull(serverCapabilities, "serverCapabilities");
        Objects.requireNonNull(tls, "tls");
        recipientDispositions = List.copyOf(recipientDispositions);
        serverCapabilities = Map.copyOf(serverCapabilities);
    }
}
