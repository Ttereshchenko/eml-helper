package com.github.ttereshchenko.mailkit.smtp;

import java.util.Objects;

/**
 * Typed error raised by {@link SmtpClient}. The {@link Kind} captures *what* went wrong and the
 * {@link Phase} captures *where* in the transaction it surfaced so callers can route the failure
 * (e.g. distinguish AUTH rejection from RCPT rejection) without parsing text.
 */
public final class SmtpException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        CONNECT_FAILED,
        TLS_FAILED,
        AUTH_FAILED,
        MAIL_REJECTED,
        RCPT_REJECTED,
        DATA_REJECTED,
        PROTOCOL_VIOLATION,
        CANCELLED,
        STOPPED_AT_PHASE,
        DROPPED_AT_PHASE,
        TIMEOUT,
        IO_ERROR,
        /** A 4yz transient negative reply (rfc5321 §4.2.1) — e.g. a {@code 421} greeting/EHLO; retryable. */
        TRANSIENT
    }

    private final Kind kind;
    private final Phase phase;
    private SmtpTranscript transcript;
    private SendResult.TlsOutcome tls;

    public SmtpException(Kind kind, Phase phase, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.phase = Objects.requireNonNull(phase, "phase");
    }

    public SmtpException(Kind kind, Phase phase, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.phase = Objects.requireNonNull(phase, "phase");
    }

    public Kind kind() {
        return kind;
    }

    public Phase phase() {
        return phase;
    }

    public SmtpTranscript transcript() {
        return transcript;
    }

    /**
     * The TLS state negotiated before the failure, or {@code null} when none was attached. Lets a
     * caller record whether a failed send was actually encrypted (e.g. an AUTH/RCPT rejection that
     * occurred after STARTTLS) rather than reporting "no TLS" for every failure.
     */
    public SendResult.TlsOutcome tls() {
        return tls;
    }

    SmtpException withTranscript(SmtpTranscript snapshot) {
        this.transcript = snapshot;
        return this;
    }

    SmtpException withTls(SendResult.TlsOutcome outcome) {
        this.tls = outcome;
        return this;
    }
}
