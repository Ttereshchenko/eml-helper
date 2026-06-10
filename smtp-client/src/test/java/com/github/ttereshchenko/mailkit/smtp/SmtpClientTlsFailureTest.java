package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import com.github.ttereshchenko.mailkit.smtp.fake.TestTlsResources;
import com.github.ttereshchenko.mailkit.smtp.tls.TlsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for F1: {@code SSLHandshakeException} is an {@code IOException}, so a failed
 * handshake (untrusted chain, hostname mismatch) used to surface as {@code IO_ERROR} instead of
 * {@code TLS_FAILED}. Also proves end-to-end that hostname verification stays active when chain
 * trust is relaxed, and that a CA bundle actually establishes trust.
 */
class SmtpClientTlsFailureTest {

    @TempDir
    Path tempDir;

    private static TlsConfig pinProtocols(TlsConfig config) {
        return config.withProtocols(List.of("TLSv1.2", "TLSv1.3"));
    }

    @Test
    void untrustedCertOverStartTlsSurfacesTlsFailedNotIoError() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 STARTTLS")
                .expectStartTls("220 ready")
                .start()) {
            // Default trust store, self-signed server cert: the handshake must fail.
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(pinProtocols(TlsConfig.starttlsRequired()));
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected TLS_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.TLS_FAILED, failure.kind(), failure.getMessage());
                assertEquals(Phase.TLS, failure.phase());
            }
        }
    }

    @Test
    void untrustedCertOnTlsOnConnectSurfacesTlsFailed() throws Exception {
        try (var server = FakeSmtpServer.builder().tlsOnConnect().start()) {
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(pinProtocols(TlsConfig.tlsOnConnect()));
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected TLS_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.TLS_FAILED, failure.kind(), failure.getMessage());
                assertEquals(Phase.TLS, failure.phase());
            }
        }
    }

    @Test
    void hostnameMismatchFailsHandshakeEvenWithChainTrustRelaxed() throws Exception {
        // allowSelfSigned relaxes only chain trust; identifying the peer as a host the test cert
        // does not cover must still abort the handshake (the cert covers localhost/127.0.0.1).
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 STARTTLS")
                .expectStartTls("220 ready")
                .start()) {
            var tls = pinProtocols(
                    TlsConfig.starttlsRequired().withAllowSelfSigned(true).withHostnameOverride("wrong.example.com"));
            var config =
                    SmtpConfig.defaults("localhost").withPort(server.port()).withTls(tls);
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected TLS_FAILED on hostname mismatch");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.TLS_FAILED, failure.kind(), failure.getMessage());
            }
        }
    }

    @Test
    void caBundleEstablishesTrustWithFullVerification() throws Exception {
        var bundle = tempDir.resolve("test-ca.pem");
        Files.writeString(bundle, TestTlsResources.serverCertPem());
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 STARTTLS")
                .expectStartTls("220 ready")
                .expect("EHLO ", "250 fake.local (secure)")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            // verifyCa stays ON: trust comes solely from the CA bundle containing the test cert.
            var tls = pinProtocols(TlsConfig.starttlsRequired().withCaBundle(bundle));
            var config =
                    SmtpConfig.defaults("localhost").withPort(server.port()).withTls(tls);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: x\n\nbody\n"));

            assertTrue(result.tls().active());
            assertTrue(result.cleanlyClosed());
        }
    }
}
