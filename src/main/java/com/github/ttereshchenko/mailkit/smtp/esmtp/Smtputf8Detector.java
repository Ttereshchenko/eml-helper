package com.github.ttereshchenko.mailkit.smtp.esmtp;

import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;

/**
 * Decides whether SMTPUTF8 (RFC 6531) is required for an envelope: any non-ASCII byte in
 * {@code MAIL FROM} or any {@code RCPT TO} address forces SMTPUTF8 since classic SMTP forbids
 * 8-bit data in command lines.
 */
public final class Smtputf8Detector {

    private Smtputf8Detector() {}

    public static boolean requiresSmtputf8(SmtpEnvelope envelope) {
        if (containsNonAscii(envelope.mailFrom())) {
            return true;
        }
        for (var recipient : envelope.recipients()) {
            if (containsNonAscii(recipient.address())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNonAscii(String value) {
        if (value == null) {
            return false;
        }
        for (var index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7F) {
                return true;
            }
        }
        return false;
    }
}
