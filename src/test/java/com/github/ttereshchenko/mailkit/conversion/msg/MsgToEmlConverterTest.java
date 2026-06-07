package com.github.ttereshchenko.mailkit.conversion.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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

        var eml = convertString(bytes);

        assertTrue(eml.contains("From: \"Alice\" <alice@example.com>"), eml);
        assertTrue(eml.contains("To: \"Bob\" <bob@example.com>"), eml);
        assertTrue(eml.contains("Subject: Hello World"), eml);
        assertTrue(eml.contains("Date: "), eml);
        assertTrue(eml.contains("Message-ID: <msg-001@example.com>"), eml);
        assertTrue(eml.contains("MIME-Version: 1.0"), eml);
        assertTrue(eml.contains("Content-Type: text/plain; charset=UTF-8"), eml);
        assertTrue(eml.contains("Content-Transfer-Encoding: quoted-printable"), eml);
    }

    @Test
    void htmlBodyDrivesContentType() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("HTML")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .htmlBody("<p>Hello</p>")
                .textBody("Hello fallback that should NOW be used")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Content-Type: multipart/alternative;"), eml);
        assertTrue(eml.contains("Content-Type: text/html; charset=UTF-8"), eml);
        assertTrue(eml.contains("Content-Type: text/plain; charset=UTF-8"), eml);

        assertTrue(eml.contains("<p>Hello</p>"), eml);
    }

    @Test
    void unicodeSubjectIsRfc2047Encoded() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Café résumé")
                .sender("Sender", "s@example.com")
                .recipientTo("Receiver", "r@example.com")
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        var subjectPattern = Pattern.compile(
                "(?m)^Subject: (=\\?UTF-8\\?B\\?[A-Za-z0-9+/=]+\\?=(?:\\r\\n =\\?UTF-8\\?B\\?[A-Za-z0-9+/=]+\\?=)*)$");
        assertTrue(subjectPattern.matcher(eml).find(), "Subject not RFC 2047 encoded: " + eml);
        assertTrue(eml.chars().allMatch(chr -> chr <= 0x7F), "EML output must remain ASCII");
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

        var eml = convertString(bytes);

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

        var eml = convertString(outerBytes);

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

        var eml = convertString(bytes);

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
            convertString(bytes);
        } catch (IOException expected) {
            assertNotNull(expected);
            return;
        } catch (Exception other) {
            assertNotNull(other);
            return;
        }
        throw new AssertionError("expected an exception for non-OLE input");
    }

    @Test
    void inlineAttachmentContentIdIsPreserved() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Inline image")
                .sender("A", "a@x")
                .recipientTo("B", "b@x")
                .htmlBody("<img src=\"cid:logo@x\">")
                .attachment("logo.png", "image/png", new byte[] {1, 2, 3}, "logo@x")
                .toBytes();

        var eml = convertString(bytes);

        // Before the fix Content-ID/inline were hardcoded null/false, so cid: references never resolved.
        assertTrue(eml.contains("Content-ID: <logo@x>"), eml);
        assertTrue(eml.contains("multipart/related"), eml);
    }

    @Test
    void nonAsciiTransportHeadersDoNotCrashConversion() throws Exception {
        var headers = "Subject: Café résumé\r\n" + "From: sender@example.com\r\n" + "To: receiver@example.com\r\n";
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("ignored when transport headers present")
                .sender("Sender", "sender@example.com")
                .recipientTo("Receiver", "receiver@example.com")
                .transportHeaders(headers)
                .textBody("body")
                .toBytes();

        var out = new java.io.ByteArrayOutputStream();
        // Before the fix the US-ASCII writer threw UnmappableCharacterException on the first non-ASCII byte.
        MsgToEmlConverter.convert(new ByteArrayInputStream(bytes), out, null);
        var eml = out.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(eml.contains("Subject: Café résumé"), eml);
    }

    @Test
    void malformedMsgThrowsConversionExceptionNotPoiInternals() {
        // Not an OLE2 container: POI throws NotOLE2FileException (an IOException) from MAPIMessage's
        // constructor. Before the fix that leaked out raw; now it is wrapped in ConversionException.
        var garbage = "this is not an OLE2 .msg file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var out = new java.io.ByteArrayOutputStream();
        assertThrows(
                com.github.ttereshchenko.mailkit.conversion.ConversionException.class,
                () -> MsgToEmlConverter.convert(new ByteArrayInputStream(garbage), out, null));
    }

    @Test
    void deeplyNestedEmbeddedMessageTruncatesInsteadOfFailing() throws Exception {
        var current = MsgFixtureBuilder.topLevel()
                .subject("deepest")
                .sender("S", "s@x")
                .recipientTo("R", "r@x")
                .textBody("deepest body");
        // Wrap past MAX_EMBEDDED_DEPTH (10) so the inner conversion exceeds the limit.
        for (var level = 0; level < 12; level++) {
            current = MsgFixtureBuilder.topLevel()
                    .subject("Level " + level)
                    .sender("S", "s@x")
                    .recipientTo("R", "r@x")
                    .textBody("body " + level)
                    .embeddedAttachment("nested", current);
        }

        var eml = convertString(current.toBytes());

        // Before the fix this threw IOException("embedded message depth exceeded") and failed the whole
        // conversion; now the over-deep branch is truncated with a stub and the parent still converts.
        assertTrue(eml.contains("Nested Message Limit Exceeded"), eml);
    }

    @Test
    void appointmentMessageDoesNotEmitEmptyCalendarInvite() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .messageClass("IPM.Appointment")
                .subject("Team sync")
                .sender("Organizer", "organizer@example.com")
                .recipientTo("Attendee", "attendee@example.com")
                .textBody("Let's meet")
                .toBytes();

        var eml = convertString(bytes);

        // POI exposes no reliable way to read the appointment's start/end/location, so the converter no
        // longer synthesizes an invite.ics with placeholder DTSTART/DTEND/no LOCATION. The appointment is
        // still exported as a normal email — just without the misleading calendar attachment.
        assertTrue(eml.contains("Subject: Team sync"), eml);
        assertTrue(eml.contains("Let's meet"), eml);
        assertFalse(eml.contains("text/calendar"), eml);
        assertFalse(eml.contains("BEGIN:VCALENDAR"), eml);
        assertFalse(eml.contains("invite.ics"), eml);
    }

    private String convertString(byte[] input) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        MsgToEmlConverter.convert(new ByteArrayInputStream(input), out, null);
        return out.toString(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
