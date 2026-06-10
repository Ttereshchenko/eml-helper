package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import com.github.ttereshchenko.mailkit.smtp.fake.TestTlsResources;
import com.github.ttereshchenko.mailkit.smtp.xclient.XclientConfig;
import org.junit.jupiter.api.Test;

class SmtpClientXclientTest {

    @Test
    void xclientIsSentAfterFirstEhloAndTriggersReEhloOn220() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 XCLIENT NAME ADDR")
                .expect("XCLIENT NAME=upstream.example.com ADDR=198.51.100.7", "220 fake.local renewed")
                .expect("EHLO ", "250 fake.local renewed (post-XCLIENT)")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var xclient = XclientConfig.disabled().withAddr("198.51.100.7").withName("upstream.example.com");
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withXclient(xclient);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: x\r\n\r\nbody\r\n"));

            assertTrue(result.cleanlyClosed());
            assertEquals(Phase.QUIT, result.lastPhaseReached());
        }
    }

    @Test
    void xclientBeforeStartTlsRunsBetweenFirstEhloAndStartTls() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250-XCLIENT NAME ADDR", "250 STARTTLS")
                .expect("XCLIENT NAME=upstream.example.com", "250 OK")
                .expectStartTls("220 ready")
                .expect("EHLO ", "250 fake.local (secure)")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var xclient =
                    XclientConfig.disabled().withName("upstream.example.com").withBeforeStartTls(true);
            var config = SmtpConfig.defaults("localhost")
                    .withPort(server.port())
                    .withTls(TestTlsResources.clientStartTlsConfig())
                    .withXclient(xclient);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: x\r\n\r\nbody\r\n"));

            assertTrue(result.tls().active());
            assertTrue(result.cleanlyClosed());
        }
    }

    @Test
    void xclient250ContinuesWithoutReEhlo() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 XCLIENT NAME ADDR")
                .expect("XCLIENT NAME=upstream.example.com", "250 OK go ahead")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var xclient = XclientConfig.disabled().withName("upstream.example.com");
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withXclient(xclient);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("body\r\n"));

            assertTrue(result.cleanlyClosed());
            var ehloCount = server.receivedLines().stream()
                    .filter(line -> line.startsWith("EHLO "))
                    .count();
            assertEquals(1, ehloCount, "a 250 XCLIENT reply must not trigger a re-EHLO");
        }
    }

    @Test
    void xclientRejectionSurfacesProtocolViolation() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 XCLIENT NAME ADDR")
                .expect("XCLIENT NAME=upstream.example.com", "550 not authorized")
                .start()) {
            var xclient = XclientConfig.disabled().withName("upstream.example.com");
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withXclient(xclient);
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected PROTOCOL_VIOLATION");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
                assertTrue(failure.getMessage().contains("XCLIENT rejected"), failure.getMessage());
            }
        }
    }

    @Test
    void xclientUnadvertisedIsSkippedWhenOptional() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var xclient = XclientConfig.disabled().withName("upstream.example.com"); // optional stays true
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withXclient(xclient);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("body\r\n"));

            assertTrue(result.cleanlyClosed());
            var transcriptText = result.transcript().render(false);
            assertTrue(
                    transcriptText.contains("XCLIENT requested but not advertised"),
                    "optional skip should be noted in the transcript:\n" + transcriptText);
        }
    }

    @Test
    void xclientUnadvertisedFailsWhenNotOptional() throws Exception {
        try (var server =
                FakeSmtpServer.builder().expect("EHLO ", "250 fake.local").start()) {
            var xclient = new XclientConfig(
                    "198.51.100.7",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    java.util.Map.of(),
                    null,
                    false,
                    false);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withXclient(xclient);

            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected PROTOCOL_VIOLATION when XCLIENT not advertised and not optional");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
                assertTrue(failure.getMessage().contains("XCLIENT"), failure.getMessage());
            }
        }
    }
}
