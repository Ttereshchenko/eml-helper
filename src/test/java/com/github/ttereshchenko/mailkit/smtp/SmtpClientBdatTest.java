package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SmtpClientBdatTest {

    @Test
    void bdatHappyPathSendsPayloadAsSingleChunk() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 CHUNKING")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expectBdat("250 OK queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withBdat(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);
            var payload = "Subject: bdat-test\r\n\r\nhello bdat world\r\n";

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString(payload));

            assertTrue(result.cleanlyClosed());
            assertEquals(Phase.QUIT, result.lastPhaseReached());
            // Verify the wire form: the BDAT command line carries LAST plus the byte count.
            var bdatLine = server.receivedLines().stream()
                    .filter(line -> line.startsWith("BDAT "))
                    .findFirst()
                    .orElseThrow();
            assertTrue(bdatLine.endsWith(" LAST"), "expected BDAT to end with LAST, got: " + bdatLine);
            assertEquals(payload, new String(server.receivedDataPayload(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void bdatFallsBackToDataWhenChunkingUnadvertised() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 OK queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withBdat(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("Subject: x\r\n\r\nbody\r\n"));

            assertTrue(result.cleanlyClosed());
            // No BDAT line should have been issued because CHUNKING was not advertised.
            assertTrue(
                    server.receivedLines().stream().noneMatch(line -> line.startsWith("BDAT ")),
                    "expected fallback to DATA, but BDAT was issued: " + server.receivedLines());
        }
    }

    @Test
    void stopAfterBdatClosesAfterServerAcksTheChunk() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 CHUNKING")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expectBdat("250 OK queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withBdat(true);
            var config = SmtpConfig.defaults("127.0.0.1")
                    .withPort(server.port())
                    .withEsmtp(esmtp)
                    .withStopAfter(Phase.BDAT, false);

            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("Subject: x\r\n\r\nbody\r\n"));
                fail("expected STOPPED_AT_PHASE for BDAT");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.STOPPED_AT_PHASE, failure.kind());
                assertEquals(Phase.BDAT, failure.phase());
            }
        }
    }
}
