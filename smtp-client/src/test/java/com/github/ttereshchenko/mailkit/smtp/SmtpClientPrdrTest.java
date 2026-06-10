package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import org.junit.jupiter.api.Test;

/**
 * PRDR servers (draft-hall-prdr, implemented by Exim) reply to the data terminator either with a
 * single uniform verdict, or with a {@code 353} intermediate reply followed by one reply per
 * accepted recipient and a closing overall reply. The old client assumed bare per-recipient
 * replies with no 353, which only ever interoperated with its own test script.
 */
class SmtpClientPrdrTest {

    @Test
    void prdrMixedAcceptRejectAfter353IsCapturedPerRecipient() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 PRDR")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:<accepted@example.com>", "250 OK")
                .expect("RCPT TO:<rejected@example.com>", "250 OK") // accepted at RCPT, rejected per-recipient at PRDR
                .expect("DATA", "354 go")
                .expectData(
                        "353 PRDR replies follow",
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

    @Test
    void prdrUniformSingleReplyKeepsRcptDispositions() throws Exception {
        // The PRDR draft lets the server skip the 353 form entirely when every recipient shares
        // the same fate — the client must treat the single 250 as the overall verdict.
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 PRDR")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:<one@example.com>", "250 OK")
                .expect("RCPT TO:<two@example.com>", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 all queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withPrdr(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);
            var envelope = SmtpEnvelope.of("from@example.com", "one@example.com", "two@example.com");

            var result = new SmtpClient().send(config, envelope, MessageSource.ofString("body\r\n"));

            assertTrue(result.cleanlyClosed());
            assertTrue(result.recipientDispositions().stream().allMatch(SendResult.RecipientDisposition::accepted));
        }
    }

    @Test
    void prdrUniformRejectionSurfacesDataRejected() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 PRDR")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:<one@example.com>", "250 OK")
                .expect("DATA", "354 go")
                .expectData("550 message refused for all recipients")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withPrdr(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "one@example.com"),
                                MessageSource.ofString("body\r\n"));
                fail("expected DATA_REJECTED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.DATA_REJECTED, failure.kind());
                assertEquals(Phase.DOT, failure.phase());
            }
        }
    }

    @Test
    void prdrCombinedWithPipeliningReadsPerRecipientReplies() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250-PIPELINING", "250 PRDR")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:<good@example.com>", "250 OK")
                .expect("RCPT TO:<bad@example.com>", "250 OK")
                .expect("DATA", "354 go")
                .expectData(
                        "353 PRDR replies follow",
                        "250 2.1.5 ok good@example.com",
                        "550 5.1.1 no bad@example.com",
                        "250 final OK")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withPrdr(true).withPipelining(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);
            var envelope = SmtpEnvelope.of("from@example.com", "good@example.com", "bad@example.com");

            var result = new SmtpClient().send(config, envelope, MessageSource.ofString("body\r\n"));

            assertTrue(result.cleanlyClosed());
            assertTrue(result.recipientDispositions().get(0).accepted());
            assertFalse(result.recipientDispositions().get(1).accepted());
            assertEquals(550, result.recipientDispositions().get(1).code());
        }
    }
}
