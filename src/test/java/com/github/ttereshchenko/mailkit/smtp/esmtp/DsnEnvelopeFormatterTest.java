package com.github.ttereshchenko.mailkit.smtp.esmtp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;

class DsnEnvelopeFormatterTest {

    @Test
    void mailFromWithoutDsnParamsIsAPlainAddressLine() {
        var envelope = SmtpEnvelope.of("sender@example.com", "to@example.com");
        var line = DsnEnvelopeFormatter.formatMailFrom(envelope, null);
        assertEquals("MAIL FROM:<sender@example.com>", line);
    }

    @Test
    void mailFromCarriesEnvidAndRetWhenSet() {
        var envelope = new SmtpEnvelope(
                "sender@example.com",
                List.of(SmtpEnvelope.Recipient.of("to@example.com")),
                "ENV-12345",
                SmtpEnvelope.RetMode.HDRS);
        var line = DsnEnvelopeFormatter.formatMailFrom(envelope, null);
        assertEquals("MAIL FROM:<sender@example.com> ENVID=ENV-12345 RET=HDRS", line);
    }

    @Test
    void mailFromAppendsTrailingParametersAfterDsn() {
        var envelope = new SmtpEnvelope(
                "sender@example.com",
                List.of(SmtpEnvelope.Recipient.of("to@example.com")),
                "ENV-1",
                SmtpEnvelope.RetMode.FULL);
        var line = DsnEnvelopeFormatter.formatMailFrom(envelope, "BODY=8BITMIME SIZE=4096");
        assertEquals("MAIL FROM:<sender@example.com> ENVID=ENV-1 RET=FULL BODY=8BITMIME SIZE=4096", line);
    }

    @Test
    void rcptToCarriesNotifyAndOrcptWhenSet() {
        var recipient = new SmtpEnvelope.Recipient(
                "to@example.com",
                List.of(SmtpEnvelope.DsnNotify.SUCCESS, SmtpEnvelope.DsnNotify.FAILURE),
                "rfc822;original@example.com");
        var line = DsnEnvelopeFormatter.formatRcptTo(recipient);
        assertEquals("RCPT TO:<to@example.com> NOTIFY=SUCCESS,FAILURE ORCPT=rfc822;original@example.com", line);
    }

    @Test
    void rcptToWithoutNotifyOrOrcptIsAPlainAddressLine() {
        var line = DsnEnvelopeFormatter.formatRcptTo(SmtpEnvelope.Recipient.of("to@example.com"));
        assertEquals("RCPT TO:<to@example.com>", line);
    }
}
