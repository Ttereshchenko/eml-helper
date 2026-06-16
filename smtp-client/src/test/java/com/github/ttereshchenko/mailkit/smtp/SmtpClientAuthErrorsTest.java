package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.auth.AuthConfig;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthCredentials;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthMechanism;
import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** AUTH failure paths and the optional / optional-strict semantics through the wire. */
class SmtpClientAuthErrorsTest {

    private static AuthCredentials passwordCredentials() {
        return AuthCredentials.of("user", () -> "secret".toCharArray());
    }

    private static FakeSmtpServer.Builder happyTail(FakeSmtpServer.Builder builder) {
        return builder.expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye");
    }

    private static SendResult send(FakeSmtpServer server, AuthConfig auth) throws SmtpException {
        var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withAuth(auth);
        return new SmtpClient()
                .send(config, SmtpEnvelope.of("from@example.com", "to@example.com"), MessageSource.ofString("body"));
    }

    @Test
    void authRejectionSurfacesAuthFailed() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 AUTH PLAIN")
                .expect("AUTH PLAIN ", "535 5.7.8 bad credentials")
                .start()) {
            var auth = AuthConfig.forMechanism(AuthMechanism.PLAIN, passwordCredentials())
                    .withAllowPlaintextAuth(true);
            try {
                send(server, auth);
                fail("expected AUTH_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.AUTH_FAILED, failure.kind());
                assertEquals(Phase.AUTH, failure.phase());
                assertTrue(failure.getMessage().contains("535"), failure.getMessage());
            }
        }
    }

    @Test
    void authRejectionIsToleratedWhenOptional() throws Exception {
        // swaks --auth-optional: attempt auth, but a rejection must not abort the send.
        try (var server = happyTail(FakeSmtpServer.builder()
                        .expect("EHLO ", "250-fake.local", "250 AUTH PLAIN")
                        .expect("AUTH PLAIN ", "535 nope"))
                .start()) {
            var auth = new AuthConfig(AuthMechanism.PLAIN, passwordCredentials(), Map.of(), true, true, false);

            var result = send(server, auth);

            assertTrue(result.cleanlyClosed());
        }
    }

    @Test
    void authRejectionIsFatalWhenOptionalStrict() throws Exception {
        // swaks --auth-optional-strict: a missing mechanism is fine, an attempted-and-rejected
        // AUTH is fatal.
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 AUTH PLAIN")
                .expect("AUTH PLAIN ", "535 nope")
                .start()) {
            var auth = new AuthConfig(AuthMechanism.PLAIN, passwordCredentials(), Map.of(), true, true, true);
            try {
                send(server, auth);
                fail("expected AUTH_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.AUTH_FAILED, failure.kind());
            }
        }
    }

    @Test
    void missingMechanismIsToleratedWhenOptionalStrict() throws Exception {
        try (var server = happyTail(FakeSmtpServer.builder().expect("EHLO ", "250 fake.local"))
                .start()) {
            var auth = new AuthConfig(AuthMechanism.PLAIN, passwordCredentials(), Map.of(), true, true, true);

            var result = send(server, auth);

            assertTrue(result.cleanlyClosed());
        }
    }

    @Test
    void missingMechanismIsFatalWhenAuthIsRequired() throws Exception {
        try (var server =
                FakeSmtpServer.builder().expect("EHLO ", "250 fake.local").start()) {
            var auth = AuthConfig.forMechanism(AuthMechanism.PLAIN, passwordCredentials());
            try {
                send(server, auth);
                fail("expected AUTH_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.AUTH_FAILED, failure.kind());
                assertTrue(failure.getMessage().contains("no usable AUTH mechanism"), failure.getMessage());
            }
        }
    }

    @Test
    void invalidBase64InChallengeCancelsExchangeWithStarThenSurfacesAuthFailed() throws Exception {
        // rfc4954 §4: an un-decodable 334 challenge must be aborted with a single "*" so the server
        // can reject the AUTH, rather than the client just dropping the handshake mid-exchange.
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 AUTH LOGIN")
                .expect("AUTH LOGIN", "334 !!!not-base64!!!")
                .expect("*", "501 5.5.2 cannot decode challenge")
                .start()) {
            var auth = AuthConfig.forMechanism(AuthMechanism.LOGIN, passwordCredentials())
                    .withAllowPlaintextAuth(true);
            try {
                send(server, auth);
                fail("expected AUTH_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.AUTH_FAILED, failure.kind());
                assertTrue(failure.getMessage().contains("base64"), failure.getMessage());
            }
            server.awaitCompletion();
            assertTrue(
                    server.receivedLines().contains("*"),
                    "client must cancel the SASL exchange with '*': " + server.receivedLines());
        }
    }

    @Test
    void unexpectedExtraChallengeIsWrappedAsAuthFailedNotRuntimeException() throws Exception {
        // Regression for F5: LoginAuthClient throws IllegalStateException on a third round — it
        // must surface as SmtpException(AUTH_FAILED), not escape unchecked.
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 AUTH LOGIN")
                .expect("AUTH LOGIN", "334 VXNlcm5hbWU6")
                .expect("dXNlcg==", "334 UGFzc3dvcmQ6")
                .expect("c2VjcmV0", "334 b25lIG1vcmU/")
                .expect("", "535 confused")
                .start()) {
            var auth = AuthConfig.forMechanism(AuthMechanism.LOGIN, passwordCredentials())
                    .withAllowPlaintextAuth(true);
            try {
                send(server, auth);
                fail("expected AUTH_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.AUTH_FAILED, failure.kind());
                assertTrue(failure.getMessage().contains("LOGIN"), failure.getMessage());
            }
        }
    }

    @Test
    void externalWithEmptyAuthzidSendsEqualsSignInitialResponse() throws Exception {
        // rfc4954 §4: an empty initial response must go on the wire as "=".
        try (var server = happyTail(FakeSmtpServer.builder()
                        .expect("EHLO ", "250-fake.local", "250 AUTH EXTERNAL")
                        .expect("AUTH EXTERNAL =", "235 OK"))
                .start()) {
            var auth = AuthConfig.forMechanism(AuthMechanism.EXTERNAL, AuthCredentials.external(""));

            var result = send(server, auth);

            assertTrue(result.cleanlyClosed());
            assertTrue(
                    server.receivedLines().stream().anyMatch("AUTH EXTERNAL ="::equals),
                    "expected 'AUTH EXTERNAL =' on the wire, got: " + server.receivedLines());
        }
    }

    @Test
    void bearerTokenMechanismRefusedOverPlaintextSocket() throws Exception {
        // Regression for F2: a bearer token is a reusable credential — XOAUTH2 must hit the same
        // non-TLS gate as PLAIN/LOGIN, before any AUTH byte leaves the socket.
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 AUTH XOAUTH2")
                .start()) {
            var auth = AuthConfig.forMechanism(
                    AuthMechanism.XOAUTH2, AuthCredentials.bearer("user", () -> "ya29.token".toCharArray()));
            try {
                send(server, auth);
                fail("expected AUTH_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.AUTH_FAILED, failure.kind());
                assertTrue(failure.getMessage().toLowerCase().contains("non-tls"), failure.getMessage());
                for (var line : server.receivedLines()) {
                    assertFalse(line.startsWith("AUTH "), "no AUTH bytes may leave the socket: " + line);
                }
            }
        }
    }

    @Test
    void autoSelectionWithBearerTokenPicksBearerMechanismOverPlain() throws Exception {
        try (var server = happyTail(FakeSmtpServer.builder()
                        .expect("EHLO ", "250-fake.local", "250 AUTH PLAIN XOAUTH2")
                        .expect("AUTH XOAUTH2 ", "235 OK"))
                .start()) {
            var auth = AuthConfig.auto(AuthCredentials.bearer("user", () -> "token".toCharArray()))
                    .withAllowPlaintextAuth(true);

            var result = send(server, auth);

            assertTrue(result.cleanlyClosed());
            assertTrue(
                    server.receivedLines().stream().anyMatch(line -> line.startsWith("AUTH XOAUTH2 ")),
                    "AUTO with a bearer token must pick XOAUTH2: " + server.receivedLines());
        }
    }

    @Test
    void autoSelectionWithPasswordNeverPicksBearerMechanism() throws Exception {
        // Regression for F9: XOAUTH2 outranks PLAIN in AUTO order, but a password must never be
        // sent as a bearer token.
        try (var server = happyTail(FakeSmtpServer.builder()
                        .expect("EHLO ", "250-fake.local", "250 AUTH XOAUTH2 PLAIN")
                        .expect("AUTH PLAIN ", "235 OK"))
                .start()) {
            var auth = AuthConfig.auto(passwordCredentials()).withAllowPlaintextAuth(true);

            var result = send(server, auth);

            assertTrue(result.cleanlyClosed());
            assertTrue(
                    server.receivedLines().stream().anyMatch(line -> line.startsWith("AUTH PLAIN ")),
                    "AUTO with a password must pick PLAIN, not XOAUTH2: " + server.receivedLines());
        }
    }
}
