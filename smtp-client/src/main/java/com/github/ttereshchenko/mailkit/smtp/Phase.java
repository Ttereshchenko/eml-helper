package com.github.ttereshchenko.mailkit.smtp;

/**
 * Lifecycle phases of an SMTP transaction, matching swaks's {@code --quit-after} / {@code --drop-after}
 * phase names so users with swaks muscle memory get the same control points.
 */
public enum Phase {
    CONNECT,
    BANNER,
    FIRST_HELO,
    STARTTLS,
    TLS,
    HELO,
    AUTH,
    MAIL,
    RCPT,
    DATA,
    BDAT,
    DOT,
    QUIT
}
