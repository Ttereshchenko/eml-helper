package com.github.ttereshchenko.mailkit.smtp.esmtp;

import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import java.util.stream.Collectors;

/**
 * Serialises RFC 3461 DSN parameters onto the MAIL / RCPT lines. The formatter never *invents*
 * parameters — when no DSN is configured on the envelope it returns a plain {@code MAIL FROM:<>}
 * / {@code RCPT TO:<>} line. Other ESMTP extension parameters (SIZE, BODY, SMTPUTF8, PRDR) are
 * appended by their dedicated negotiators.
 */
public final class DsnEnvelopeFormatter {

    private DsnEnvelopeFormatter() {}

    public static String formatMailFrom(SmtpEnvelope envelope, String trailingParameters) {
        var builder =
                new StringBuilder("MAIL FROM:<").append(envelope.mailFrom()).append('>');
        if (envelope.envid() != null && !envelope.envid().isBlank()) {
            builder.append(" ENVID=").append(envelope.envid());
        }
        if (envelope.ret() != SmtpEnvelope.RetMode.DEFAULT) {
            builder.append(" RET=").append(envelope.ret().name());
        }
        if (trailingParameters != null && !trailingParameters.isBlank()) {
            builder.append(' ').append(trailingParameters.trim());
        }
        return builder.toString();
    }

    public static String formatRcptTo(SmtpEnvelope.Recipient recipient) {
        var builder = new StringBuilder("RCPT TO:<").append(recipient.address()).append('>');
        if (!recipient.notifyOn().isEmpty()) {
            var joined = recipient.notifyOn().stream().map(Enum::name).collect(Collectors.joining(","));
            builder.append(" NOTIFY=").append(joined);
        }
        if (recipient.orcpt() != null && !recipient.orcpt().isBlank()) {
            builder.append(" ORCPT=").append(recipient.orcpt());
        }
        return builder.toString();
    }
}
