package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.auth.AuthConfig;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthCredentials;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthMechanism;
import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import com.github.ttereshchenko.mailkit.smtp.fake.TestTlsResources;
import org.junit.jupiter.api.Test;

class SmtpClientAuthTest {

    @Test
    void authPlainOverStartTlsSucceeds() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250-STARTTLS", "250 AUTH PLAIN LOGIN")
                .expectStartTls("220 ready")
                .expect("EHLO ", "250-fake.local (secure)", "250 AUTH PLAIN LOGIN")
                .expect("AUTH PLAIN ", "235 2.7.0 Authentication successful")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var auth = AuthConfig.forMechanism(
                    AuthMechanism.PLAIN, AuthCredentials.of("user", () -> "secret".toCharArray()));
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientStartTlsConfig())
                    .withAuth(auth);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: x\n\nbody\n"));

            server.awaitCompletion();
            assertTrue(result.tls().active());
            assertTrue(result.cleanlyClosed());
            assertEquals(Phase.QUIT, result.lastPhaseReached());
        }
    }

    @Test
    void plaintextAuthRefusedOverNonTlsSocketBeforeAnyByteLeaves() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 AUTH PLAIN LOGIN")
                .start()) {
            var auth = AuthConfig.forMechanism(
                    AuthMechanism.PLAIN, AuthCredentials.of("user", () -> "secret".toCharArray()));
            var config =
                    SmtpConfig.defaults("localhost").withPort(server.port()).withAuth(auth);

            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected AUTH_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.AUTH_FAILED, failure.kind());
                assertEquals(Phase.AUTH, failure.phase());
                assertTrue(
                        failure.getMessage().toLowerCase().contains("non-tls"),
                        "expected refusal reason to mention non-TLS but was: " + failure.getMessage());
                for (var line : server.receivedLines()) {
                    assertFalse(
                            line.startsWith("AUTH "),
                            "no AUTH bytes must leave the socket when plaintext-auth is refused, but got: " + line);
                }
            }
        }
    }

    @Test
    void plaintextAuthOverNonTlsSocketAllowedWhenExplicitlyOverridden() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 AUTH PLAIN")
                .expect("AUTH PLAIN ", "235 OK")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var auth = AuthConfig.forMechanism(
                            AuthMechanism.PLAIN, AuthCredentials.of("user", () -> "secret".toCharArray()))
                    .withAllowPlaintextAuth(true);
            var config =
                    SmtpConfig.defaults("localhost").withPort(server.port()).withAuth(auth);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: hi\n\nbody\n"));

            server.awaitCompletion();
            assertTrue(result.cleanlyClosed());
        }
    }

    @Test
    void authLoginOverStartTlsExchangesUsernameThenPassword() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250-STARTTLS", "250 AUTH LOGIN")
                .expectStartTls("220 ready")
                .expect("EHLO ", "250-fake.local", "250 AUTH LOGIN")
                .expect("AUTH LOGIN", "334 VXNlcm5hbWU6")
                .expect("dXNlcg==", "334 UGFzc3dvcmQ6")
                .expect("c2VjcmV0", "235 OK")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var auth = AuthConfig.forMechanism(
                    AuthMechanism.LOGIN, AuthCredentials.of("user", () -> "secret".toCharArray()));
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientStartTlsConfig())
                    .withAuth(auth);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: hi\n\nbody\n"));

            server.awaitCompletion();
            assertTrue(result.tls().active());
            assertTrue(result.cleanlyClosed());
        }
    }

    @Test
    void autoSelectionPicksStrongestAndPlainAuthBytesAreRedactedInTranscript() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250-STARTTLS", "250 AUTH PLAIN LOGIN")
                .expectStartTls("220 ready")
                .expect("EHLO ", "250-fake.local", "250 AUTH PLAIN LOGIN")
                .expect("AUTH PLAIN ", "235 OK")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var auth = AuthConfig.auto(AuthCredentials.of("user", () -> "secret".toCharArray()));
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientStartTlsConfig())
                    .withAuth(auth);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: hi\n\nbody\n"));

            server.awaitCompletion();
            assertTrue(result.cleanlyClosed());
            // AUTH lines must be redacted in the default rendering (revealAuth=false).
            var rendered = result.transcript().render(false);
            assertTrue(
                    rendered.contains("<auth credentials scrubbed>"),
                    "rendered transcript should redact AUTH lines:\n" + rendered);
            assertFalse(
                    rendered.contains("AUTH PLAIN A"),
                    "redacted transcript must not include the AUTH base64 payload:\n" + rendered);
        }
    }
}
