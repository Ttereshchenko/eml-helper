package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import org.junit.jupiter.api.Test;

/**
 * Phase-1 coverage of {@code stopAfter} + {@code dropAfter} for the phases reachable without
 * TLS/AUTH: CONNECT, BANNER, FIRST_HELO, MAIL, RCPT, DATA, DOT, QUIT. STARTTLS / TLS / HELO /
 * AUTH stop points land with Phase 2.
 */
class SmtpClientStopAfterTest {

    private SmtpException expectStop(FakeSmtpServer.Builder serverBuilder, Phase stopAfter, boolean drop)
            throws Exception {
        try (var server = serverBuilder.start()) {
            var config =
                    SmtpConfig.defaults("127.0.0.1").withPort(server.port()).withStopAfter(stopAfter, drop);
            try {
                new SmtpClient()
                        .send(
                                config,
                                SmtpEnvelope.of("from@example.com", "to@example.com"),
                                MessageSource.ofString("Subject: x\n\nbody\n"));
                fail("expected SmtpException for stopAfter=" + stopAfter + ", drop=" + drop);
                return null;
            } catch (SmtpException failure) {
                server.awaitCompletion();
                return failure;
            }
        }
    }

    @Test
    void stopAfterConnectClosesBeforeAnyExchange() throws Exception {
        var failure = expectStop(FakeSmtpServer.builder().banner(), Phase.CONNECT, false);
        assertEquals(SmtpException.Kind.STOPPED_AT_PHASE, failure.kind());
        assertEquals(Phase.CONNECT, failure.phase());
    }

    @Test
    void stopAfterBannerReadsBannerThenQuits() throws Exception {
        var failure = expectStop(FakeSmtpServer.builder().expect("QUIT", "221 bye"), Phase.BANNER, false);
        assertEquals(SmtpException.Kind.STOPPED_AT_PHASE, failure.kind());
        assertEquals(Phase.BANNER, failure.phase());
        assertNotNull(failure.transcript());
    }

    @Test
    void dropAfterBannerClosesSocketWithoutQuit() throws Exception {
        var failure = expectStop(FakeSmtpServer.builder(), Phase.BANNER, true);
        assertEquals(SmtpException.Kind.DROPPED_AT_PHASE, failure.kind());
        assertEquals(Phase.BANNER, failure.phase());
    }

    @Test
    void stopAfterFirstHeloIssuesEhloThenQuits() throws Exception {
        var failure = expectStop(
                FakeSmtpServer.builder().expect("EHLO ", "250 fake.local Hello").expect("QUIT", "221 bye"),
                Phase.FIRST_HELO,
                false);
        assertEquals(SmtpException.Kind.STOPPED_AT_PHASE, failure.kind());
        assertEquals(Phase.FIRST_HELO, failure.phase());
    }

    @Test
    void stopAfterMailIssuesMailFromThenQuits() throws Exception {
        var failure = expectStop(
                FakeSmtpServer.builder()
                        .expect("EHLO ", "250 fake.local")
                        .expect("MAIL FROM:", "250 OK")
                        .expect("QUIT", "221 bye"),
                Phase.MAIL,
                false);
        assertEquals(SmtpException.Kind.STOPPED_AT_PHASE, failure.kind());
        assertEquals(Phase.MAIL, failure.phase());
    }

    @Test
    void stopAfterRcptIssuesAllRcptsThenQuits() throws Exception {
        var failure = expectStop(
                FakeSmtpServer.builder()
                        .expect("EHLO ", "250 fake.local")
                        .expect("MAIL FROM:", "250 OK")
                        .expect("RCPT TO:", "250 OK")
                        .expect("QUIT", "221 bye"),
                Phase.RCPT,
                false);
        assertEquals(SmtpException.Kind.STOPPED_AT_PHASE, failure.kind());
        assertEquals(Phase.RCPT, failure.phase());
    }

    @Test
    void stopAfterDataIssuesDataThenQuitsBeforePayload() throws Exception {
        var failure = expectStop(
                FakeSmtpServer.builder()
                        .expect("EHLO ", "250 fake.local")
                        .expect("MAIL FROM:", "250 OK")
                        .expect("RCPT TO:", "250 OK")
                        .expect("DATA", "354 go")
                        .expect("QUIT", "221 bye"),
                Phase.DATA,
                false);
        assertEquals(SmtpException.Kind.STOPPED_AT_PHASE, failure.kind());
        assertEquals(Phase.DATA, failure.phase());
    }

    @Test
    void stopAfterDotSendsPayloadThenQuits() throws Exception {
        var failure = expectStop(
                FakeSmtpServer.builder()
                        .expect("EHLO ", "250 fake.local")
                        .expect("MAIL FROM:", "250 OK")
                        .expect("RCPT TO:", "250 OK")
                        .expect("DATA", "354 go")
                        .expectData("250 queued")
                        .expect("QUIT", "221 bye"),
                Phase.DOT,
                false);
        assertEquals(SmtpException.Kind.STOPPED_AT_PHASE, failure.kind());
        assertEquals(Phase.DOT, failure.phase());
    }

    @Test
    void dropAfterRcptClosesWithoutQuit() throws Exception {
        var failure = expectStop(
                FakeSmtpServer.builder()
                        .expect("EHLO ", "250 fake.local")
                        .expect("MAIL FROM:", "250 OK")
                        .expect("RCPT TO:", "250 OK"),
                Phase.RCPT,
                true);
        assertEquals(SmtpException.Kind.DROPPED_AT_PHASE, failure.kind());
        assertEquals(Phase.RCPT, failure.phase());
    }
}
