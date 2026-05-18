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
        IO_ERROR
    }

    private final Kind kind;
    private final Phase phase;
    private SmtpTranscript transcript;

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

    SmtpException withTranscript(SmtpTranscript snapshot) {
        this.transcript = snapshot;
        return this;
    }
}
