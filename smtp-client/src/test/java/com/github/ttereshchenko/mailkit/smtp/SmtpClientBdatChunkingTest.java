package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Streaming CHUNKING: payloads beyond one chunk go out as multiple BDAT commands (F18). */
class SmtpClientBdatChunkingTest {

    @Test
    void payloadLargerThanChunkSizeIsSentAsMultipleBdatCommands() throws Exception {
        // 300 KiB of CRLF-terminated lines: > 256 KiB chunk size → one intermediate + one LAST.
        var line = "x".repeat(98) + "\r\n";
        var payload = line.repeat(3 * 1024); // 300 KiB
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 CHUNKING")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expectBdat("250 chunk accepted")
                .expectBdat("250 message queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withBdat(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofBytes(payload.getBytes(StandardCharsets.US_ASCII)));

            server.awaitCompletion();
            assertNull(server.serverFailure(), String.valueOf(server.serverFailure()));
            assertTrue(result.cleanlyClosed());
            var bdatLines = server.receivedLines().stream()
                    .filter(received -> received.startsWith("BDAT "))
                    .toList();
            assertEquals(2, bdatLines.size(), "expected two BDAT commands: " + bdatLines);
            assertFalse(bdatLines.get(0).endsWith(" LAST"), "first chunk must not be LAST: " + bdatLines);
            assertTrue(bdatLines.get(1).endsWith(" LAST"), "second chunk must be LAST: " + bdatLines);
        }
    }

    @Test
    void bdatWithPrdrReadsPerRecipientRepliesAfterLastChunk() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250-CHUNKING", "250 PRDR")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:<good@example.com>", "250 OK")
                .expect("RCPT TO:<bad@example.com>", "250 OK")
                .expectBdat("353 PRDR replies follow")
                .pushLines("250 ok good@example.com", "550 no bad@example.com", "250 final OK")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withBdat(true).withPrdr(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);
            var envelope = SmtpEnvelope.of("from@example.com", "good@example.com", "bad@example.com");

            var result = new SmtpClient().send(config, envelope, MessageSource.ofString("body\r\n"));

            assertTrue(result.cleanlyClosed());
            assertTrue(result.recipientDispositions().get(0).accepted());
            assertFalse(result.recipientDispositions().get(1).accepted());
            assertEquals(550, result.recipientDispositions().get(1).code());
        }
    }
}
