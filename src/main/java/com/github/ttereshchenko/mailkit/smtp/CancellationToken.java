package com.github.ttereshchenko.mailkit.smtp;

/**
 * Cooperative cancellation signal consulted between SMTP phases and between body chunks.
 * Returning {@code true} causes {@link SmtpClient} to close the socket immediately without sending QUIT.
 */
@FunctionalInterface
public interface CancellationToken {

    CancellationToken NEVER = () -> false;

    boolean isCancelled();
}
