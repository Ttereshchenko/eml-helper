package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SmtpClientEsmtpExtensionsTest {

    @Test
    void sizePreflightRefusesWhenMessageExceedsAdvertisedLimit() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 SIZE 10")
                .start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("this message is way longer than ten bytes"));
                fail("expected MAIL_REJECTED for SIZE overflow");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.MAIL_REJECTED, failure.kind());
                assertTrue(failure.getMessage().contains("SIZE"), failure.getMessage());
            }
        }
    }

    @Test
    void sizePreflightUsesWireLengthSoAnLfOnlyMessageUnderTheRawLimitIsStillRejected() throws Exception {
        // rfc1870 §6: the SIZE preflight must reflect the bytes actually transmitted. The body is
        // 10 bytes on disk (LF-only) but 12 bytes on the wire once each LF is normalized to CRLF.
        // Against an advertised "SIZE 11" the raw length (10) would pass — the old, buggy check —
        // but the true wire length (12) exceeds the limit and must be refused before MAIL.
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 SIZE 11")
                .start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofBytes("aaaa\nbbbb\n".getBytes(StandardCharsets.UTF_8)));
                fail("expected MAIL_REJECTED: wire length 12 exceeds advertised SIZE 11");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.MAIL_REJECTED, failure.kind());
                assertTrue(failure.getMessage().contains("12"), failure.getMessage());
            }
        }
    }

    @Test
    void declaredSizeOnMailIsTheCrlfWireLengthNotTheRawLength() throws Exception {
        // rfc1870 §4: SIZE=<n> is the estimate of the transmitted message. An LF-only "hi\n" is 3
        // raw bytes but 4 on the wire ("hi\r\n"), so the MAIL FROM parameter must declare SIZE=4.
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 SIZE 1048576")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());

            new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofBytes("hi\n".getBytes(StandardCharsets.UTF_8)));

            var mailLine = server.receivedLines().stream()
                    .filter(line -> line.startsWith("MAIL FROM:"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(mailLine.contains("SIZE=4"), "declared size must be the CRLF wire length, not raw: " + mailLine);
        }
    }

    @Test
    void smtputf8RequiredButUnadvertisedFailsBeforeMail() throws Exception {
        try (var server =
                FakeSmtpServer.builder().expect("EHLO ", "250 fake.local").start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("rené@example.com", "to@example.com"),
                                MessageSource.ofString("body"));
                fail("expected MAIL_REJECTED for non-ASCII envelope without SMTPUTF8");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.MAIL_REJECTED, failure.kind());
                assertTrue(failure.getMessage().contains("SMTPUTF8"), failure.getMessage());
            }
        }
    }

    @Test
    void eightBitMimeRequiredButUnadvertisedFailsBeforeMail() throws Exception {
        try (var server =
                FakeSmtpServer.builder().expect("EHLO ", "250 fake.local").start()) {
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("body with é (high bit byte)"));
                fail("expected MAIL_REJECTED for 8-bit body without 8BITMIME");
            } catch (SmtpException failure) {
                assertEquals(SmtpException.Kind.MAIL_REJECTED, failure.kind());
                assertTrue(failure.getMessage().contains("8BITMIME"), failure.getMessage());
            }
        }
    }

    @Test
    void eightBitMimeDowngradePolicyContinuesWithoutBodyParameter() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp =
                    EsmtpConfig.defaults().withEightBitMime(EsmtpConfig.EightBitMimePolicy.DOWNGRADE_IF_UNADVERTISED);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("body with é"));

            assertTrue(result.cleanlyClosed());
            for (var line : server.receivedLines()) {
                assertTrue(
                        !line.contains("BODY=8BITMIME"),
                        "downgrade policy must not declare BODY=8BITMIME when server lacks support: " + line);
            }
        }
    }

    @Test
    void mailLineCarriesBodyAndSizeAndSmtpUtf8AndPrdrWhenAllAdvertised() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250-8BITMIME", "250-SMTPUTF8", "250-SIZE 1048576", "250 PRDR")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 OK queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withPrdr(true);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);

            new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("rené@example.com", "to@example.com"),
                            MessageSource.ofString("body with é\r\n"));

            var mailLine = server.receivedLines().stream()
                    .filter(line -> line.startsWith("MAIL FROM:"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(mailLine.contains("BODY=8BITMIME"), mailLine);
            assertTrue(mailLine.contains("SMTPUTF8"), mailLine);
            assertTrue(mailLine.contains("SIZE="), mailLine);
            assertTrue(mailLine.contains("PRDR"), mailLine);
        }
    }

    @Test
    void eightBitMimeNeverPolicyOmitsBodyParameterEvenWhenAdvertised() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 8BITMIME")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withEightBitMime(EsmtpConfig.EightBitMimePolicy.NEVER);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("body with é"));

            assertTrue(result.cleanlyClosed());
            for (var line : server.receivedLines()) {
                assertTrue(!line.contains("BODY=8BITMIME"), "NEVER policy must not declare BODY=8BITMIME: " + line);
            }
        }
    }

    @Test
    void declareSizeOnMailDisabledOmitsSizeParameter() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 SIZE 1048576")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withDeclareSizeOnMail(false);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);

            new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("body\r\n"));

            var mailLine = server.receivedLines().stream()
                    .filter(line -> line.startsWith("MAIL FROM:"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(!mailLine.contains("SIZE="), mailLine);
        }
    }

    @Test
    void honorSizeDisabledSendsDespiteAdvertisedLimit() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250-fake.local", "250 SIZE 10")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var esmtp = EsmtpConfig.defaults().withHonorSize(false);
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withEsmtp(esmtp);

            var result = new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("this message is way longer than ten bytes"));

            assertTrue(result.cleanlyClosed());
        }
    }

    @Test
    void rcptLineCarriesDsnNotifyAndOrcptWhenSet() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .expect("EHLO ", "250 fake.local")
                .expect("MAIL FROM:", "250 OK")
                .expect("RCPT TO:", "250 OK")
                .expect("DATA", "354 go")
                .expectData("250 queued")
                .expect("QUIT", "221 bye")
                .start()) {
            var recipient = new SmtpEnvelope.Recipient(
                    "to@example.com",
                    java.util.List.of(SmtpEnvelope.DsnNotify.FAILURE, SmtpEnvelope.DsnNotify.DELAY),
                    "rfc822;orig@example.com");
            var envelope = new SmtpEnvelope(
                    "from@example.com", java.util.List.of(recipient), "ENV-1", SmtpEnvelope.RetMode.HDRS);
            var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());

            new SmtpClient().send(config, envelope, MessageSource.ofString("body\r\n"));

            var mailLine = server.receivedLines().stream()
                    .filter(line -> line.startsWith("MAIL FROM:"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(mailLine.contains("ENVID=ENV-1"), mailLine);
            assertTrue(mailLine.contains("RET=HDRS"), mailLine);
            var rcptLine = server.receivedLines().stream()
                    .filter(line -> line.startsWith("RCPT TO:"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(rcptLine.contains("NOTIFY=FAILURE,DELAY"), rcptLine);
            assertTrue(rcptLine.contains("ORCPT=rfc822;orig@example.com"), rcptLine);
        }
    }
}
