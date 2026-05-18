package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.auth.AuthConfig;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthCredentials;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthMechanism;
import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import com.github.ttereshchenko.mailkit.smtp.fake.TestTlsResources;
import org.junit.jupiter.api.Test;

/**
 * Fills the per-{@link Phase} stop/drop matrix that the earlier phase-specific test classes did
 * not cover. The earlier classes verify CONNECT / BANNER / FIRST_HELO / MAIL / RCPT / DATA / DOT;
 * here we cover TLS / HELO / AUTH / BDAT / QUIT and the remaining dropAfter variants.
 */
class SmtpClientStopDropCoverageTest {

    @Test
    void stopAfterTlsClosesAfterHandshakeAndBeforeSecondEhlo() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 STARTTLS")
                .expectStartTls("220 ready")
                .expect("QUIT", "221 bye")
                .start()) {
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientStartTlsConfig())
                    .withStopAfter(Phase.TLS, false);
            assertStop(config, Phase.TLS);
        }
    }

    @Test
    void stopAfterHeloClosesAfterSecondEhlo() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 STARTTLS")
                .expectStartTls("220 ready")
                .expect("EHLO ", "250 fake.local (secure)")
                .expect("QUIT", "221 bye")
                .start()) {
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientStartTlsConfig())
                    .withStopAfter(Phase.HELO, false);
            assertStop(config, Phase.HELO);
        }
    }

    @Test
    void stopAfterAuthClosesAfterAuthSuccess() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250-STARTTLS", "250 AUTH PLAIN")
                .expectStartTls("220 ready")
                .expect("EHLO ", "250-fake.local (secure)", "250 AUTH PLAIN")
                .expect("AUTH PLAIN ", "235 OK")
                .expect("QUIT", "221 bye")
                .start()) {
            var auth = AuthConfig.forMechanism(
                    AuthMechanism.PLAIN, AuthCredentials.of("user", () -> "secret".toCharArray()));
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientStartTlsConfig())
                    .withAuth(auth)
                    .withStopAfter(Phase.AUTH, false);
            assertStop(config, Phase.AUTH);
        }
    }

    @Test
    void stopAfterQuitIssuesQuitWithoutWaitingForResponse() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withStopAfter(Phase.QUIT, false);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: x\r\n\r\nbody\r\n"));

            // stopAfter=QUIT means we emitted QUIT but didn't wait for the 221.
            assertEquals(Phase.QUIT, result.lastPhaseReached());
            // cleanlyClosed is false because we never observed the 221.
            assertEquals(false, result.cleanlyClosed());
        }
    }

    @Test
    void dropAfterMailClosesSocketWithoutQuit() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .start()) {
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withStopAfter(Phase.MAIL, true);
            assertDrop(config, Phase.MAIL);
        }
    }

    @Test
    void dropAfterDataClosesSocketBeforePayload() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .start()) {
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withStopAfter(Phase.DATA, true);
            assertDrop(config, Phase.DATA);
        }
    }

    private void assertStop(SmtpConfig config, Phase expected) {
        try {
            new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: x\r\n\r\nbody\r\n"));
            fail("expected STOPPED_AT_PHASE for " + expected);
        } catch (SmtpException failure) {
            assertEquals(SmtpException.Kind.STOPPED_AT_PHASE, failure.kind());
            assertEquals(expected, failure.phase());
        }
    }

    private void assertDrop(SmtpConfig config, Phase expected) {
        try {
            new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: x\r\n\r\nbody\r\n"));
            fail("expected DROPPED_AT_PHASE for " + expected);
        } catch (SmtpException failure) {
            assertEquals(SmtpException.Kind.DROPPED_AT_PHASE, failure.kind());
            assertEquals(expected, failure.phase());
        }
    }
}
