package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import com.github.ttereshchenko.mailkit.smtp.fake.TestTlsResources;
import org.junit.jupiter.api.Test;

class SmtpClientTlsOnConnectTest {

    @Test
    void tlsOnConnectHandshakeHappensBeforeBanner() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .tlsOnConnect()
                .expect("EHLO ", "250 fake.local Hello")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientTlsOnConnectConfig());
            var envelope = SmtpEnvelope.of("from@example.com", "to@example.com");

            var result = new SmtpClient().send(config, envelope, MessageSource.ofString("Subject: hi\n\nbody\n"));

            server.awaitCompletion();
            assertTrue(result.tls().active());
            assertTrue(result.cleanlyClosed());
            assertEquals(Phase.QUIT, result.lastPhaseReached());
        }
    }
}
