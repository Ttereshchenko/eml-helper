package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import org.junit.jupiter.api.Test;

class SmtpClientPrdrTest {

    @Test
    void prdrMixedAcceptRejectIsCapturedPerRecipient() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 PRDR")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:<accepted@example.com>", "250 OK")
                .expect("RCPT TO:<rejected@example.com>", "250 OK") // accepted at RCPT, rejected per-recipient at PRDR
                .expect("DATA", "354 go")
                .expectData(
                        "250 2.1.5 OK accepted@example.com",
                        "550 5.1.1 mailbox not found rejected@example.com",
                        "250 final OK")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withPrdr(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);
            var envelope = SmtpEnvelope.of("from@example.com", "accepted@example.com", "rejected@example.com");

            var result = new SmtpClient().send(config, envelope, MessageSource.ofString("body\r\n"));

            assertTrue(result.cleanlyClosed());
            assertEquals(2, result.recipientDispositions().size());
            assertTrue(result.recipientDispositions().get(0).accepted());
            assertEquals(250, result.recipientDispositions().get(0).code());
            assertFalse(result.recipientDispositions().get(1).accepted());
            assertEquals(550, result.recipientDispositions().get(1).code());
        }
    }
}
