package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import org.junit.jupiter.api.Test;

/** HELO-only protocol mode, the EHLO→HELO fallback, and the LMTP guard. */
class SmtpClientHeloTest {

    private static FakeSmtpServer.Builder happyTail(FakeSmtpServer.Builder builder) {
        return builder.expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye");
    }

    @Test
    void protocolSmtpSpeaksHeloNotEhlo() throws Exception {
        try (var server = happyTail(FakeSmtpServer.builder().expect("HELO ", "250 fake.local"))
                .start()) {
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withProtocol(SmtpConfig.Protocol.SMTP);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("body"));

            server.awaitCompletion();
            assertTrue(result.cleanlyClosed());
            assertTrue(
                    server.receivedLines().stream().anyMatch(line -> line.startsWith("HELO ")),
                    "expected HELO on the wire: " + server.receivedLines());
        }
    }

    @Test
    void ehloRejectedWithPermanentErrorFallsBackToHelo() throws Exception {
        // rfc5321 §3.2: pre-ESMTP servers answer EHLO with 500/502 — the client must retry HELO.
        try (var server = happyTail(FakeSmtpServer.builder()
                        .expect("EHLO ", "502 command not implemented")
                        .expect("HELO ", "250 fake.local"))
                .start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("body"));

            server.awaitCompletion();
            assertTrue(result.cleanlyClosed());
            assertTrue(
                    server.receivedLines().stream().anyMatch(line -> line.startsWith("HELO ")),
                    "expected HELO fallback on the wire: " + server.receivedLines());
        }
    }

    @Test
    void lmtpProtocolIsRejectedAtConfigurationTime() {
        // rfc2033 LMTP (LHLO, per-recipient DATA replies) is unimplemented — failing fast beats
        // silently speaking ESMTP at an LMTP server.
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> SmtpConfig.defaults("localhost").withProtocol(SmtpConfig.Protocol.LMTP));
        assertTrue(failure.getMessage().contains("LMTP"), failure.getMessage());
    }
}
