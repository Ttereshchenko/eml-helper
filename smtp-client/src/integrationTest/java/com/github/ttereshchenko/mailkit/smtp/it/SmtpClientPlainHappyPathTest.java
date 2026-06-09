package com.github.ttereshchenko.mailkit.smtp.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.MessageSource;
import com.github.ttereshchenko.mailkit.smtp.Phase;
import com.github.ttereshchenko.mailkit.smtp.SmtpClient;
import com.github.ttereshchenko.mailkit.smtp.SmtpConfig;
import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end happy path against a real Mailpit SMTP server in Docker. Skipped automatically when
 * the host has no Docker daemon.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmtpClientPlainHappyPathTest {

    @Container
    private static final MailpitContainer MAILPIT = new MailpitContainer();

    @Test
    void sendsEmlToRealServerAndMessageAppearsInInbox() throws Exception {
        var eml = "From: sender@example.com\n"
                + "To: recipient@example.com\n"
                + "Subject: integration-test-subject\n"
                + "Message-ID: <mailkit-it-001@mailkit.local>\n"
                + "\n"
                + "Body content for the integration test.\n";
        var config = SmtpConfig.defaults(MAILPIT.getHost())
                .withPort(MAILPIT.smtpPort())
                .withEhloHost("mailkit-it.local");
        var envelope = SmtpEnvelope.of("sender@example.com", "recipient@example.com");

        var result = new SmtpClient().send(config, envelope, MessageSource.ofString(eml));

        assertEquals(Phase.QUIT, result.lastPhaseReached());
        assertTrue(result.cleanlyClosed());
        assertEquals(1, result.recipientDispositions().size());
        assertTrue(result.recipientDispositions().get(0).accepted());

        var inbox = MAILPIT.fetchMessagesJson();
        assertTrue(inbox.contains("integration-test-subject"), "Mailpit inbox missing message: " + inbox);
        assertTrue(inbox.contains("recipient@example.com"), "Mailpit inbox missing recipient: " + inbox);
    }
}
