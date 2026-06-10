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
