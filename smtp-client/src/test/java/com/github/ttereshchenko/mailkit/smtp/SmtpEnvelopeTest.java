package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for F1 (SMTP command / CR-LF injection). An envelope address or DSN parameter
 * carrying an embedded line break used to flow unchecked into {@code MAIL FROM:} / {@code RCPT TO:}
 * lines, letting a caller smuggle extra SMTP commands onto the wire (rfc5321 §4.1.1, §2.3.8). The
 * envelope now rejects CR / LF / NUL at construction.
 */
class SmtpEnvelopeTest {

    @Test
    void rejectsCrlfInMailFrom() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SmtpEnvelope.of("victim@example.com>\r\nRCPT TO:<bcc@evil.test", "to@example.com"));
    }

    @Test
    void rejectsLoneLineFeedInRecipientAddress() {
        assertThrows(
                IllegalArgumentException.class, () -> SmtpEnvelope.of("from@example.com", "to@example.com>\nDATA"));
    }

    @Test
    void rejectsCarriageReturnInRecipientConstructor() {
        assertThrows(IllegalArgumentException.class, () -> new SmtpEnvelope.Recipient("a@b\rc", List.of(), null));
    }

    @Test
    void rejectsLineBreakInEnvid() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SmtpEnvelope(
                        "from@example.com",
                        List.of(SmtpEnvelope.Recipient.of("to@example.com")),
                        "envid\r\nMAIL FROM:<x@y",
                        SmtpEnvelope.RetMode.DEFAULT));
    }

    @Test
    void rejectsLineBreakInOrcpt() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SmtpEnvelope.Recipient("to@example.com", List.of(), "rfc822;to@x\r\nRCPT TO:<y@z"));
    }

    @Test
    void rejectsNul() {
        var addressWithNul = "to@example.com" + (char) 0 + "evil";
        assertThrows(IllegalArgumentException.class, () -> SmtpEnvelope.of("from@example.com", addressWithNul));
    }

    @Test
    void acceptsOrdinaryAsciiAddresses() {
        var envelope = assertDoesNotThrow(() -> SmtpEnvelope.of("from@example.com", "to@example.com"));
        assertEquals("from@example.com", envelope.mailFrom());
        assertEquals(1, envelope.recipients().size());
        assertEquals("to@example.com", envelope.recipients().get(0).address());
    }

    @Test
    void acceptsInternationalizedUtf8Addresses() {
        // SMTPUTF8 addresses carry non-ASCII code points; an address with multibyte characters
        // must still construct cleanly.
        var envelope = assertDoesNotThrow(() -> SmtpEnvelope.of("user@例え.example", "rcpt@例え.example"));
        assertEquals("user@例え.example", envelope.mailFrom());
    }

    @Test
    void rejectsAngleBracketInMailFrom() {
        // "a@b> SIZE=0" would close the forward-path early and inject an ESMTP parameter.
        assertThrows(IllegalArgumentException.class, () -> SmtpEnvelope.of("a@example.com> SIZE=0", "to@example.com"));
    }

    @Test
    void rejectsSpaceInRecipientAddress() {
        assertThrows(
                IllegalArgumentException.class, () -> SmtpEnvelope.of("from@example.com", "to@example.com FOO=BAR"));
    }

    @Test
    void rejectsTabAndControlCharactersInAddresses() {
        assertThrows(IllegalArgumentException.class, () -> SmtpEnvelope.of("from@example.com", "to@exa\tmple.com"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SmtpEnvelope.of("from@example.com", ("to@exa" + (char) 0x07 + "mple.com")));
    }

    @Test
    void rejectsNotifyNeverCombinedWithOtherValues() {
        // rfc3461 §4.1: notify-esmtp-value = "NEVER" / 1#notify-list-element — NEVER is exclusive, so
        // NOTIFY=NEVER,FAILURE is invalid and a conformant server rejects it with a 501.
        assertThrows(
                IllegalArgumentException.class,
                () -> new SmtpEnvelope.Recipient(
                        "to@example.com", List.of(SmtpEnvelope.DsnNotify.NEVER, SmtpEnvelope.DsnNotify.FAILURE), null));
    }

    @Test
    void acceptsNotifyNeverAlone() {
        var recipient = assertDoesNotThrow(
                () -> new SmtpEnvelope.Recipient("to@example.com", List.of(SmtpEnvelope.DsnNotify.NEVER), null));
        assertEquals(List.of(SmtpEnvelope.DsnNotify.NEVER), recipient.notifyOn());
    }

    @Test
    void acceptsCombinedNonNeverNotifyValues() {
        var recipient = assertDoesNotThrow(() -> new SmtpEnvelope.Recipient(
                "to@example.com",
                List.of(SmtpEnvelope.DsnNotify.SUCCESS, SmtpEnvelope.DsnNotify.FAILURE, SmtpEnvelope.DsnNotify.DELAY),
                null));
        assertEquals(3, recipient.notifyOn().size());
    }
}
