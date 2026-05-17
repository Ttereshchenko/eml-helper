package com.github.ttereshchenko.mailkit.conversion.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MsgToEmlConverterTest {

    @Test
    void plainMessageEmitsRequiredHeaders() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Hello World")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .messageDate(new Date(1715817600000L))
                .messageId("<msg-001@example.com>")
                .textBody("Hello there!")
                .toBytes();

        var eml = MsgToEmlConverter.convert(new ByteArrayInputStream(bytes));

        assertTrue(eml.contains("From: \"Alice\" <alice@example.com>"), eml);
        assertTrue(eml.contains("To: \"Bob\" <bob@example.com>"), eml);
        assertTrue(eml.contains("Subject: Hello World"), eml);
        assertTrue(eml.contains("Date: "), eml);
        assertTrue(eml.contains("Message-ID: <msg-001@example.com>"), eml);
        assertTrue(eml.contains("MIME-Version: 1.0"), eml);
        assertTrue(eml.contains("Content-Type: text/plain; charset=UTF-8"), eml);
        assertTrue(eml.contains("Content-Transfer-Encoding: base64"), eml);
    }

    @Test
    void htmlBodyDrivesContentType() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("HTML")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .htmlBody("<p>Hello</p>")
                .textBody("Hello fallback that should NOT be used")
                .toBytes();

        var eml = MsgToEmlConverter.convert(new ByteArrayInputStream(bytes));

        assertTrue(eml.contains("Content-Type: text/html; charset=UTF-8"), eml);
        var bodyStart = eml.indexOf("\r\n\r\n");
        assertNotEquals(-1, bodyStart, eml);
        var encodedBody = eml.substring(bodyStart + 4).replaceAll("\\s", "");
        var decoded = new String(Base64.getMimeDecoder().decode(encodedBody), StandardCharsets.UTF_8);
        assertEquals("<p>Hello</p>", decoded);
    }

    @Test
    void unicodeSubjectIsRfc2047Encoded() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Café résumé")
                .sender("Sender", "s@example.com")
                .recipientTo("Receiver", "r@example.com")
                .textBody("body")
                .toBytes();

        var eml = MsgToEmlConverter.convert(new ByteArrayInputStream(bytes));

        var subjectPattern = Pattern.compile("(?m)^Subject: =\\?UTF-8\\?B\\?[A-Za-z0-9+/=]+\\?=$");
        assertTrue(subjectPattern.matcher(eml).find(), "Subject not RFC 2047 encoded: " + eml);
        assertTrue(MsgToEmlConverter.isPureAscii(eml), "EML output must remain ASCII");
    }

    @Test
    void attachmentRoundTripsFilenameAndBytes() throws Exception {
        var payload = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("With attachment")
                .sender("A", "a@x")
                .recipientTo("B", "b@x")
                .textBody("see attachment")
                .attachment("report.pdf", "application/pdf", payload)
                .toBytes();

        var eml = MsgToEmlConverter.convert(new ByteArrayInputStream(bytes));

        assertTrue(eml.contains("Content-Type: multipart/mixed; boundary=\"MAILKIT_"), eml);
        assertTrue(eml.contains("Content-Disposition: attachment; filename=\"report.pdf\""), eml);
        assertTrue(eml.contains("Content-Type: application/pdf"), eml);

        var attachmentMarker = "Content-Disposition: attachment; filename=\"report.pdf\"\r\n";
        var attachmentIndex = eml.indexOf(attachmentMarker);
        assertNotEquals(-1, attachmentIndex);
        var blankLine = eml.indexOf("\r\n\r\n", attachmentIndex);
        var nextBoundary = eml.indexOf("--MAILKIT_", blankLine + 4);
        var encoded = eml.substring(blankLine + 4, nextBoundary).replaceAll("\\s", "");
        var decoded = Base64.getMimeDecoder().decode(encoded);
        assertEquals(payload.length, decoded.length);
        for (var index = 0; index < payload.length; index++) {
            assertEquals(payload[index], decoded[index], "byte " + index);
        }
    }

    @Test
    void embeddedMsgIsRecursedIntoMessageRfc822() throws Exception {
        var inner = MsgFixtureBuilder.topLevel()
                .subject("Inner subject XYZ-marker")
                .sender("InnerSender", "inner@example.com")
                .recipientTo("InnerReceiver", "inner-r@example.com")
                .textBody("inner body");

        var outerBytes = MsgFixtureBuilder.topLevel()
                .subject("Outer")
                .sender("Outer", "outer@example.com")
                .recipientTo("OuterTo", "outer-r@example.com")
                .textBody("outer body")
                .embeddedAttachment("nested", inner)
                .toBytes();

        var eml = MsgToEmlConverter.convert(new ByteArrayInputStream(outerBytes));

        assertTrue(eml.contains("Content-Type: message/rfc822"), eml);
        var nestedStart = eml.indexOf("Content-Type: message/rfc822");
        assertTrue(nestedStart > 0);
        var nestedBlank = eml.indexOf("\r\n\r\n", nestedStart);
        assertTrue(nestedBlank > 0);
        var afterNested = eml.indexOf("--MAILKIT_", nestedBlank + 4);
        var nestedSection =
                afterNested < 0 ? eml.substring(nestedBlank + 4) : eml.substring(nestedBlank + 4, afterNested);
        assertTrue(
                nestedSection.contains("Subject: Inner subject XYZ-marker"),
                "Nested message should re-contain inner subject: " + nestedSection);
    }

    @Test
    void ccRecipientsAreFilteredFromTo() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .sender("S", "s@x")
                .recipientTo("ToOne", "to1@x")
                .recipientCc("CcOne", "cc1@x")
                .textBody("body")
                .toBytes();

        var eml = MsgToEmlConverter.convert(new ByteArrayInputStream(bytes));

        assertTrue(eml.contains("To: \"ToOne\" <to1@x>"), eml);
        assertTrue(eml.contains("Cc: \"CcOne\" <cc1@x>"), eml);
        var toLine =
                eml.lines().filter(line -> line.startsWith("To: ")).findFirst().orElseThrow();
        assertEquals("To: \"ToOne\" <to1@x>", toLine);
    }

    @Test
    void emptyStreamFailsLoudly() {
        var bytes = new byte[] {0x00, 0x01, 0x02};
        try {
            MsgToEmlConverter.convert(new ByteArrayInputStream(bytes));
        } catch (IOException expected) {
            assertNotNull(expected);
            return;
        } catch (Exception other) {
            assertNotNull(other);
            return;
        }
        throw new AssertionError("expected an exception for non-OLE input");
    }
}
