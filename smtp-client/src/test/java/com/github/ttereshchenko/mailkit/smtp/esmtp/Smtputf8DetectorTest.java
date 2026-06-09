package com.github.ttereshchenko.mailkit.smtp.esmtp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import org.junit.jupiter.api.Test;

class Smtputf8DetectorTest {

    @Test
    void asciiEnvelopeDoesNotRequireSmtpUtf8() {
        assertFalse(Smtputf8Detector.requiresSmtputf8(SmtpEnvelope.of("from@example.com", "to@example.com")));
    }

    @Test
    void nonAsciiInMailFromRequiresSmtpUtf8() {
        assertTrue(Smtputf8Detector.requiresSmtputf8(SmtpEnvelope.of("é@example.com", "to@example.com")));
    }

    @Test
    void nonAsciiInAnyRecipientRequiresSmtpUtf8() {
        assertTrue(Smtputf8Detector.requiresSmtputf8(
                SmtpEnvelope.of("from@example.com", "ok@example.com", "rené@example.com")));
    }
}
