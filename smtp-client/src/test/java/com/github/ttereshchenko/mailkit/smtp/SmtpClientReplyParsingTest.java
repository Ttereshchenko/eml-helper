package com.github.ttereshchenko.mailkit.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.ttereshchenko.mailkit.smtp.fake.FakeSmtpServer;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * Malformed-reply handling in the response reader: every branch must surface a typed
 * {@link SmtpException} instead of an array index error, a hang, or unbounded buffering.
 */
class SmtpClientReplyParsingTest {

    private SmtpException sendExpectingFailure(FakeSmtpServer server) throws Exception {
        var config = SmtpConfig.defaults("127.0.0.1").withPort(server.port());
        try {
            new SmtpClient()
                    .send(
                            config,
                            SmtpEnvelope.of("from@example.com", "to@example.com"),
                            MessageSource.ofString("body"));
            fail("expected SmtpException");
            throw new AssertionError("unreachable");
        } catch (SmtpException failure) {
            return failure;
        }
    }

    @Test
    void transientBannerIsClassifiedAsTransientNotProtocolViolation() throws Exception {
        // rfc5321 §4.2.1/§3.1: a 4yz greeting (e.g. 421) is a transient condition, not a protocol
        // violation — callers can retry rather than treat the server as broken.
        try (var server =
                FakeSmtpServer.builder().banner("421 service not available").start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.TRANSIENT, failure.kind());
            assertEquals(Phase.BANNER, failure.phase());
        }
    }

    @Test
    void transientEhloReplyIsClassifiedAsTransient() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .banner("220 fake.local ready")
                .expect("EHLO ", "421 try again later")
                .start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.TRANSIENT, failure.kind());
        }
    }

    @Test
    void multiLineReplyWithMismatchedCodesIsAProtocolViolation() throws Exception {
        try (var server = FakeSmtpServer.builder().banner("250-one", "550 two").start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
            assertTrue(failure.getMessage().contains("mismatch"), failure.getMessage());
        }
    }

    @Test
    void replyLineShorterThanThreeCharsIsAProtocolViolation() throws Exception {
        try (var server = FakeSmtpServer.builder().banner("22").start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
            assertTrue(failure.getMessage().contains("short reply line"), failure.getMessage());
        }
    }

    @Test
    void nonNumericReplyCodeIsAProtocolViolation() throws Exception {
        try (var server = FakeSmtpServer.builder().banner("ABC hello").start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
            assertTrue(failure.getMessage().contains("non-numeric"), failure.getMessage());
        }
    }

    @Test
    void replyCodeAboveFiveHundredNinetyNineIsAProtocolViolation() throws Exception {
        // rfc5321 §4.2: reply codes are three digits in the 2xx–5xx range. A 6xx code parses as a
        // number but is impossible; the old code accepted it silently via Integer.parseInt.
        try (var server = FakeSmtpServer.builder().banner("600 impossible").start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
            assertTrue(failure.getMessage().contains("out of range"), failure.getMessage());
        }
    }

    @Test
    void negativeReplyCodeIsAProtocolViolation() throws Exception {
        // "-50 ..." parses to -50 via Integer.parseInt(substring(0,3)); rfc5321 §4.2 forbids it.
        try (var server = FakeSmtpServer.builder().banner("-50 negative").start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
            assertTrue(failure.getMessage().contains("out of range"), failure.getMessage());
        }
    }

    @Test
    void replyCodeBelowTwoHundredIsAProtocolViolation() throws Exception {
        // 1yz codes are reserved and never sent by a server in this client's transaction model
        // (rfc5321 §4.2); a 100 banner must be rejected rather than accepted as a positive reply.
        try (var server = FakeSmtpServer.builder().banner("100 too low").start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
            assertTrue(failure.getMessage().contains("out of range"), failure.getMessage());
        }
    }

    @Test
    void invalidSeparatorAfterCodeIsAProtocolViolation() throws Exception {
        try (var server = FakeSmtpServer.builder().banner("220Xhello").start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
            assertTrue(failure.getMessage().contains("separator"), failure.getMessage());
        }
    }

    @Test
    void serverClosingMidMultiLineReplyIsAnIoError() throws Exception {
        try (var server = FakeSmtpServer.builder()
                .banner("220-greeting continues")
                .dropConnection()
                .start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.IO_ERROR, failure.kind());
        }
    }

    @Test
    void replyLineLongerThanCapIsAProtocolViolationNotUnboundedBuffering() throws Exception {
        try (var server =
                FakeSmtpServer.builder().banner("220 " + "x".repeat(9000)).start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
            assertTrue(failure.getMessage().contains("exceeds"), failure.getMessage());
        }
    }

    @Test
    void replyWithMoreLinesThanCapIsAProtocolViolation() throws Exception {
        var lines = new ArrayList<String>();
        for (var index = 0; index < 501; index++) {
            lines.add("220-line " + index);
        }
        try (var server =
                FakeSmtpServer.builder().banner(lines.toArray(new String[0])).start()) {
            var failure = sendExpectingFailure(server);
            assertEquals(SmtpException.Kind.PROTOCOL_VIOLATION, failure.kind());
            assertTrue(failure.getMessage().contains("lines"), failure.getMessage());
        }
    }
}
