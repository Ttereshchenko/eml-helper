package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import org.junit.jupiter.api.Test;

/** Failure paths inside the PIPELINING batch (MAIL + RCPTs + DATA written before any read). */
class SmtpClientPipeliningFailureTest {

    @Test
    void mailRejectedInsideBatchSurfacesMailRejected() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 PIPELINING")
                .expect("MAIL FROM:", "550 sender blocked")
                .expect("RCPT TO:", "550 no sender")
                .expect("DATA", "503 bad sequence")
                .start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected MAIL_REJECTED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.MAIL_REJECTED, failure.kind());
                assertEquals(Phase.MAIL, failure.phase());
            }
        }
    }

    @Test
    void allRecipientsRejectedInsideBatchDrainsDataReplyAndSurfacesRcptRejected() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 PIPELINING")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:<one@example.com>", "550 unknown user")
                .expect("RCPT TO:<two@example.com>", "550 unknown user")
                .expect("DATA", "503 no valid recipients")
                .start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "one@example.com", "two@example.com"),
                                MessageSource.ofString("body"));
                fail("expected RCPT_REJECTED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.RCPT_REJECTED, failure.kind());
                assertEquals(Phase.RCPT, failure.phase());
            }
        }
    }

    @Test
    void dataRejectedInsideBatchSurfacesDataRejected() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 PIPELINING")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "554 no DATA today")
                .start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected DATA_REJECTED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.DATA_REJECTED, failure.kind());
                assertEquals(Phase.DATA, failure.phase());
            }
        }
    }
}
