package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import com.github.ttereshchenko.mailkit.smtp.tls.TlsConfig;
import org.junit.jupiter.api.Test;

/** The two optional STARTTLS modes when the server rejects (vs merely omits) STARTTLS. */
class SmtpClientStartTlsModesTest {

    private static FakeSmtpServer.Builder plainTail(FakeSmtpServer.Builder builder) {
        return builder.expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye");
    }

    private static SendResult send(FakeSmtpServer server, TlsConfig tls) throws SmtpException {
        var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withTls(tls);
        return new SmtpClient()
                .send(config, SmtpEnvelope.of("from@example.com", "to@example.com"), MessageSource.ofString("body"));
    }

    @Test
    void optionalStrictContinuesPlainWhenStartTlsIsNotAdvertised() throws Exception {
        try (var server = plainTail(FakeSmtpServer.builder().expect("EHLO ", "250 fake.local"))
                .start()) {
            var result = send(server, TlsConfig.starttlsOptional().withMode(TlsConfig.Mode.STARTTLS_OPTIONAL_STRICT));

            assertFalse(result.tls().active());
            assertTrue(result.cleanlyClosed());
        }
    }

    @Test
    void optionalStrictFailsWhenAdvertisedStartTlsIsRejected() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 STARTTLS")
                .expect("STARTTLS", "454 TLS not available right now")
                .start()) {
            try {
                send(server, TlsConfig.starttlsOptional().withMode(TlsConfig.Mode.STARTTLS_OPTIONAL_STRICT));
                fail("expected TLS_FAILED");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.TLS_FAILED, failure.kind());
                assertEquals(Phase.STARTTLS, failure.phase());
            }
        }
    }

    @Test
    void optionalModeDowngradesToCleartextWhenStartTlsIsRejectedAndNotesItInTranscript() throws Exception {
        try (var server = plainTail(FakeSmtpServer.builder()
                        .expect("EHLO ", "250-fake.local", "250 STARTTLS")
                        .expect("STARTTLS", "454 TLS not available right now"))
                .start()) {
            var result = send(server, TlsConfig.starttlsOptional());

            assertFalse(result.tls().active());
            assertTrue(result.cleanlyClosed());
            var transcriptText = result.transcript().render(false);
            assertTrue(
                    transcriptText.contains("continuing in cleartext"),
                    "downgrade must be visible in the transcript:\n" + transcriptText);
        }
    }
}
