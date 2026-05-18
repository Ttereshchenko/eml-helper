package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import org.junit.jupiter.api.Test;

class SmtpClientRejectionTest {

    @Test
    void mailRejectionSurfacesTypedException() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "550 unauthorized sender")
                .start()) {
            try {
                new SmtpClient()
                        .send(
                                SmtpConfig.defaults("127.0.0.1").withPort(server.port()),
                                SmtpEnvelope.of("nobody@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected MAIL_REJECTED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.MAIL_REJECTED, failure.kind());
                assertEquals(Phase.MAIL, failure.phase());
                assertNotNull(failure.transcript());
            }
        }
    }

    @Test
    void allRecipientsRejectedSurfacesRcptException() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "550 user unknown")
                .start()) {
            try {
                new SmtpClient()
                        .send(
                                SmtpConfig.defaults("127.0.0.1").withPort(server.port()),
                                SmtpEnvelope.of("from@example.com", "missing@example.com"),
                                MessageSource.ofString("body"));
                fail("expected RCPT_REJECTED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.RCPT_REJECTED, failure.kind());
                assertEquals(Phase.RCPT, failure.phase());
            }
        }
    }

    @Test
    void dataRejectionSurfacesDataException() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "552 too big")
                .start()) {
            try {
                new SmtpClient()
                        .send(
                                SmtpConfig.defaults("127.0.0.1").withPort(server.port()),
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected DATA_REJECTED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.DATA_REJECTED, failure.kind());
                assertEquals(Phase.DATA, failure.phase());
            }
        }
    }

    @Test
    void cancellationMidTransactionClosesWithoutQuit() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .start()) {
            var cancelAfterMail = new CancellationToken() {
                private int calls;

                @Override
                public boolean isCancelled() {
                    return ++calls > 2;
                }
            };
            try {
                new SmtpClient()
                        .send(
                                SmtpConfig.defaults("127.0.0.1").withPort(server.port()),
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body"),
                                cancelAfterMail,
                                SmtpTranscript.NULL_LISTENER);
                fail("expected CANCELLED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.CANCELLED, failure.kind());
            }
        }
    }
}
