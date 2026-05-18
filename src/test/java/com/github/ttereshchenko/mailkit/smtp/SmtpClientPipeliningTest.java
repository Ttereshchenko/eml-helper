package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import org.junit.jupiter.api.Test;

class SmtpClientPipeliningTest {

    @Test
    void pipeliningSendsBatchedCommandsAndDeliversSuccessfully() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 PIPELINING")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:<a@example.com>", "250 OK")
                .expect("RCPT TO:<b@example.com>", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withPipelining(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);
            var envelope = SmtpEnvelope.of("from@example.com", "a@example.com", "b@example.com");

            var result = new SmtpClient().send(config, envelope, MessageSource.ofString("Subject: x\r\n\r\nbody\r\n"));

            assertTrue(result.cleanlyClosed());
            assertEquals(2, result.recipientDispositions().size());
            assertTrue(result.recipientDispositions().stream().allMatch(SendResult.RecipientDisposition::accepted));
        }
    }

    @Test
    void pipeliningIsAutoDisabledWhenServerDoesNotAdvertiseIt() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withPipelining(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: x\r\n\r\nbody\r\n"));

            assertTrue(result.cleanlyClosed());
        }
    }
}
