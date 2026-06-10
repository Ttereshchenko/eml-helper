package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import com.github.ttereshchenko.mailkit.smtp.fake.TestTlsResources;
import com.github.ttereshchenko.mailkit.smtp.tls.TlsConfig;
import org.junit.jupiter.api.Test;

class SmtpClientStartTlsTest {

    @Test
    void starttlsHappyPathUpgradesSocketAndReissuesEhlo() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local Hello", "250-STARTTLS", "250 PIPELINING")
                .expectStartTls("220 Ready to start TLS")
                .expect("EHLO ", "250-fake.local Hello (secure)", "250 PIPELINING")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientStartTlsConfig());
            var envelope = SmtpEnvelope.of("from@example.com", "to@example.com");

            var result = new SmtpClient().send(config, envelope, MessageSource.ofString("Subject: hi\n\nbody\n"));

            server.awaitCompletion();
            assertTrue(result.tls().active());
            assertNotNull(result.tls().protocol());
            assertFalse(result.tls().protocol().isBlank());
            assertNotNull(result.tls().cipherSuite());
            assertFalse(result.tls().cipherSuite().isBlank());
            // The peer certificate snapshot must survive past the closed socket.
            var peer = result.tls().peer();
            assertFalse(peer.isEmpty(), "peer certificate snapshot should be captured");
            assertFalse(peer.subject().isBlank(), "subject DN expected");
            assertEquals(64, peer.fingerprintSha256().length(), "SHA-256 hex fingerprint expected");
            assertTrue(peer.chainPem().contains("-----BEGIN CERTIFICATE-----"), "PEM chain expected");
            assertTrue(result.cleanlyClosed());
            assertEquals(Phase.QUIT, result.lastPhaseReached());
        }
    }

    @Test
    void starttlsRequiredFailsWhenServerDoesNotAdvertiseIt() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local Hello")
                .expect("QUIT", "221 bye")
                .start()) {
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientStartTlsConfig());
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected TLS_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.TLS_FAILED, failure.kind());
                assertEquals(Phase.STARTTLS, failure.phase());
            }
        }
    }

    @Test
    void starttlsOptionalContinuesPlainWhenUnadvertised() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local Hello")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var tls = TlsConfig.starttlsOptional().withAllowSelfSigned(true);
            var config =
                    SmtpConfig.defaults("localhost").withPort(server.port()).withTls(tls);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("body"));

            server.awaitCompletion();
            assertFalse(result.tls().active());
            assertTrue(result.cleanlyClosed());
        }
    }

    @Test
    void stopAfterStartTlsClosesBeforeHandshake() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 STARTTLS")
                .expect("STARTTLS", "220 ready")
                .expect("QUIT", "221 bye")
                .start()) {
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientStartTlsConfig())
                    .withStopAfter(Phase.STARTTLS, false);
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected STOPPED_AT_PHASE");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.STOPPED_AT_PHASE, failure.kind());
                assertEquals(Phase.STARTTLS, failure.phase());
            }
        }
    }
}
