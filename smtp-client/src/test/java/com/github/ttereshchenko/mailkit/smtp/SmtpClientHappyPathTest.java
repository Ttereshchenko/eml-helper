package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SmtpClientHappyPathTest {

    private static final String SAMPLE_EML =
            "From: sender@example.com\nTo: recipient@example.com\nSubject: hi\n\nbody line\n";

    @Test
    void plainEsmtpTransactionSendsBytesAsIs() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local Hello", "250 PIPELINING")
                .expect("MAIL FROM:<sender@example.com>", "250 OK")
                .expect("RCPT TO:<recipient@example.com>", "250 OK")
                .expect("DATA", "354 Start mail input; end with <CRLF>.<CRLF>")
                .expectData("250 OK queued as ABC123")
                .expect("QUIT", "221 fake.local closing")
                .start()) {
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEhloHost("mailkit-test.local");
            var envelope = SmtpEnvelope.of("sender@example.com", "recipient@example.com");

            var result = new SmtpClient().send(config, envelope, MessageSource.ofString(SAMPLE_EML));

            server.awaitCompletion();
            assertNull(server.serverFailure());
            assertEquals(1, result.recipientDispositions().size());
            assertTrue(result.recipientDispositions().get(0).accepted());
            assertEquals(250, result.recipientDispositions().get(0).code());
            assertTrue(result.cleanlyClosed());
            assertEquals(Phase.QUIT, result.lastPhaseReached());
            assertTrue(result.duration().toNanos() > 0);

            var receivedPayload = new String(server.receivedDataPayload(), StandardCharsets.UTF_8);
            // Server records the de-stuffed payload — should be the original .eml with CRLF endings.
            var expected = SAMPLE_EML.replace("\n", "\r\n");
            assertEquals(expected, receivedPayload);

            assertTrue(result.serverCapabilities().containsKey("PIPELINING"));
        }
    }

    @Test
    void multipleRecipientsAllReceiveRcpt() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local Hello")
                .expect("MAIL FROM:<sender@example.com>", "250 OK")
                .expect("RCPT TO:<a@example.com>", "250 OK")
                .expect("RCPT TO:<b@example.com>", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());
            var envelope = SmtpEnvelope.of("sender@example.com", "a@example.com", "b@example.com");

            var result = new SmtpClient().send(config, envelope, MessageSource.ofString("Subject: x\n\nbody\n"));

            server.awaitCompletion();
            assertNull(server.serverFailure());
            assertEquals(2, result.recipientDispositions().size());
            assertTrue(result.recipientDispositions().stream().allMatch(SendResult.RecipientDisposition::accepted));
        }
    }

    @Test
    void timeoutIsHonouredOnBanner() {
        // Build a server that opens the socket but never writes the banner — client must time out.
        Throwable observed = null;
        try (var server = FakeSmtpServer.builder().banner().expect("DUMMY").start()) {
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withTimeout(Duration.ofMillis(200));
            try {
                new SmtpClient().send(config, SmtpEnvelope.of("a@b.c", "x@y.z"), MessageSource.ofString(""));
            } catch (SmtpException failure) {
                observed = failure;
                assertEquals(SmtpException.Kind.TIMEOUT, failure.kind());
            }
        } catch (Exception teardown) {
            // server close is fine
        }
        assertTrue(observed instanceof SmtpException, "expected timeout but got " + observed);
    }
}
