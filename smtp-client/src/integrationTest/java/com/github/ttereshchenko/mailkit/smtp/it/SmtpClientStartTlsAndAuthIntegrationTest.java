package com.github.ttereshchenko.mailkit.smtp.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.smtp.MessageSource;
import com.github.ttereshchenko.mailkit.smtp.SmtpClient;
import com.github.ttereshchenko.mailkit.smtp.SmtpConfig;
import com.github.ttereshchenko.mailkit.smtp.SmtpEnvelope;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthConfig;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthCredentials;
import com.github.ttereshchenko.mailkit.smtp.auth.AuthMechanism;
import com.github.ttereshchenko.mailkit.smtp.fake.TestTlsResources;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * STARTTLS and AUTH against a real Mailpit: the server presents the bundled localhost test
 * certificate and accepts any credentials, so the test exercises the genuine JSSE handshake and a
 * full PLAIN exchange instead of the in-process fake. Skipped automatically without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class SmtpClientStartTlsAndAuthIntegrationTest {

    @Container
    private static final MailpitContainer MAILPIT = createTlsAuthContainer();

    private static MailpitContainer createTlsAuthContainer() {
        try {
            return new MailpitContainer()
                    .withEnv("MP_SMTP_TLS_CERT", "/certs/cert.pem")
                    .withEnv("MP_SMTP_TLS_KEY", "/certs/key.pem")
                    .withEnv("MP_SMTP_AUTH_ACCEPT_ANY", "1")
                    .withCopyToContainer(
                            Transferable.of(TestTlsResources.serverCertPem().getBytes(StandardCharsets.UTF_8)),
                            "/certs/cert.pem")
                    .withCopyToContainer(
                            Transferable.of(TestTlsResources.serverKeyPem().getBytes(StandardCharsets.UTF_8)),
                            "/certs/key.pem");
        } catch (Exception failure) {
            throw new IllegalStateException("could not prepare Mailpit TLS material", failure);
        }
    }

    @Test
    void authPlainOverStartTlsDeliversToInbox() throws Exception {
        var eml = "From: sender@example.com\n"
                + "To: recipient@example.com\n"
                + "Subject: integration-starttls-auth\n"
                + "Message-ID: <mailkit-it-tls-001@mailkit.local>\n"
                + "\n"
                + "Sent over STARTTLS with AUTH PLAIN.\n";
        var auth =
                AuthConfig.forMechanism(AuthMechanism.PLAIN, AuthCredentials.of("it-user", "it-secret"::toCharArray));
        var config = SmtpConfig.defaults(MAILPIT.getHost())
                .withPort(MAILPIT.smtpPort())
                .withEhloHost("mailkit-it.local")
                .withTls(TestTlsResources.clientStartTlsConfig())
                .withAuth(auth);
        var envelope = SmtpEnvelope.of("sender@example.com", "recipient@example.com");

        var result = new SmtpClient().send(config, envelope, MessageSource.ofString(eml));

        assertTrue(result.tls().active(), "STARTTLS should have been negotiated");
        assertTrue(result.cleanlyClosed());
        assertTrue(result.recipientDispositions().get(0).accepted());

        var inbox = MAILPIT.fetchMessagesJson();
        assertTrue(inbox.contains("integration-starttls-auth"), "Mailpit inbox missing message: " + inbox);
    }
}
