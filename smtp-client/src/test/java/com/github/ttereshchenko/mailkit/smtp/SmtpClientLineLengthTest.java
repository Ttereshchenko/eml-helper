package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import org.junit.jupiter.api.Test;

/**
 * rfc5321 §4.5.3.1.6: a text line including its CRLF must not exceed 1000 octets. The client
 * pre-flights the wire-normalized body and refuses (before MAIL FROM) a message carrying an
 * over-long line, rather than streaming bytes a strict server would reject or truncate.
 */
class SmtpClientLineLengthTest {

    @Test
    void rejectsMessageWithLineExceedingThousandOctets() {
        var overLong = "X".repeat(1001); // 1001 content octets — 1003 with CRLF, over the 1000 limit
        var eml = "Subject: x\n\n" + overLong + "\n";

        SmtpException observed = null;
        try (var server =
                FakeSmtpServer.builder().expect("EHLO ", "250 fake.local Hello").start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());
            try {
                new SmtpClient().send(config, SmtpEnvelope.of("a@b.c", "x@y.z"), MessageSource.ofString(eml));
            } catch (SmtpException failure) {
                observed = failure;
            }
        } catch (Exception teardown) {
            // The client aborts after EHLO without finishing the script, so server teardown is fine.
        }

        assertNotNull(observed, "an over-long line must be rejected");
        assertEquals(SmtpException.Kind.DATA_REJECTED, observed.kind());
        assertEquals(Phase.DATA, observed.phase());
    }

    @Test
    void acceptsMessageWithLinesAtTheLimit() throws Exception {
        var atLimit = "X".repeat(998); // 998 content octets — exactly 1000 with CRLF
        var eml = "Subject: x\n\n" + atLimit + "\n";

        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local Hello")
                .expect("MAIL FROM:<a@b.c>", "250 OK")
                .expect("RCPT TO:<x@y.z>", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());

            var result = new SmtpClient().send(config, SmtpEnvelope.of("a@b.c", "x@y.z"), MessageSource.ofString(eml));

            server.awaitCompletion();
            assertNull(server.serverFailure());
            assertTrue(result.cleanlyClosed());
        }
    }
}
