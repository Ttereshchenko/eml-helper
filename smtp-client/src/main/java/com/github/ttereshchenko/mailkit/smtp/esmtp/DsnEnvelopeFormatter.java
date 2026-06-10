package com.github.ttereshchenko.mailkit.smtp.esmtp;

import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import com.github.ttereshchenko.mailkit.smtp.Xtext;
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
            // rfc3461 §4.4: ENVID is transmitted as xtext.
            builder.append(" ENVID=").append(Xtext.encode(envelope.envid()));
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
            builder.append(" ORCPT=").append(encodeOrcpt(recipient.orcpt()));
        }
        return builder.toString();
    }

    /**
     * ORCPT is {@code addr-type ";" xtext} (rfc3461 §4.2): the address-type token passes through,
     * the address after the first {@code ;} is xtext-encoded. A value without a {@code ;} is
     * encoded wholesale.
     */
    private static String encodeOrcpt(String orcpt) {
        var separator = orcpt.indexOf(';');
        if (separator < 0) {
            return Xtext.encode(orcpt);
        }
        return orcpt.substring(0, separator) + ";" + Xtext.encode(orcpt.substring(separator + 1));
    }
}
