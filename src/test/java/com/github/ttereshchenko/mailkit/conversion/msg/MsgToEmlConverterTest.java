package com.github.ttereshchenko.mailkit.conversion.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.conversion.ConversionException;
import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
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
    void fromNameBackfilledFromSentRepresentingWhenSenderNameAbsent() throws Exception {
        // PR_SENDER_NAME absent but PR_SENT_REPRESENTING_NAME present with the same address: From must
        // carry the represented author's display name, not an address-only From (rfc5322 §3.6.2).
        // Before the fix the MSG path emitted "From: <bob@example.com>", diverging from the PST path.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Backfilled From name")
                .sender(null, "bob@example.com") // PR_SENDER_EMAIL_ADDRESS only; PR_SENDER_NAME absent
                .sentRepresenting("Bob Author", "bob@example.com")
                .recipientTo("Carol", "carol@example.com")
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("From: \"Bob Author\" <bob@example.com>"), eml);
        // Same address for sender and author, so no redundant Sender header.
        assertFalse(eml.contains("Sender:"), eml);
    }

    // M1: the Date header is the origination time (rfc5322 §3.6.1). When both PR_CLIENT_SUBMIT_TIME and
    // PR_MESSAGE_DELIVERY_TIME are present, Date must be the submit time, not the delivery time (the old
    // code preferred delivery, diverging from the PST pipeline).
    @Test
    void dateHeaderPrefersClientSubmitTimeOverDeliveryTime() throws Exception {
        var submitTime = new Date(1_577_900_000_000L); // 2020
        var deliveryTime = new Date(1_630_000_000_000L); // 2021
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Date precedence")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .clientSubmitTime(submitTime)
                .messageDate(deliveryTime)
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        var dateLine =
                eml.lines().filter(line -> line.startsWith("Date:")).findFirst().orElse("");
        assertTrue(dateLine.contains("2020"), "Date should be the submit (origination) time: " + dateLine);
        assertFalse(dateLine.contains("2021"), "Date must not be the delivery time: " + dateLine);
    }

    // M4: a REPORT.* class that is neither a delivery report (.NDR/.DR) nor a read/non-read receipt
    // (.IPNRN/.IPNNRN) must not be emitted as a disposition-notification claiming the message was
    // "displayed" (rfc8098 §3.2.6); it falls back to the generic body instead.
    @Test
    void unrecognizedReportClassIsNotEmittedAsDisplayedMdn() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .messageClass("REPORT.IPM.Note.Delayed")
                .subject("Delivery delayed")
                .sender("Mailer Daemon", "postmaster@example.com")
                .recipientTo("Bob", "bob@example.com")
                .textBody("Your message has been delayed.")
                .toBytes();

        var eml = convertString(bytes);

        assertFalse(eml.contains("disposition-notification"), "a non-receipt report must not become an MDN: " + eml);
        assertFalse(eml.contains("Disposition:"), "a non-receipt report must not fabricate a disposition: " + eml);
        assertTrue(eml.contains("Your message has been delayed."), "falls back to the generic body: " + eml);
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
    void recipientTypeHighBitFlagsStillClassifyToCcBcc() throws Exception {
        // [MS-OXOMSG] §2.2.3.1: Exchange sets high-bit flags on PR_RECIPIENT_TYPE for resent / saved-sent
        // items (e.g. 0x10000000 "already processed"). The low bits still hold MAPI_TO/CC/BCC, so a
        // flagged "To" (0x10000001) must classify as To rather than fall through to the display-name
        // fallback and lose the SMTP address.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .sender("S", "s@x")
                .recipientOrdered("ToOne", "to1@x", 0x10000001)
                .recipientOrdered("CcOne", "cc1@x", 0x10000002)
                .recipientOrdered("BccOne", "bcc1@x", 0x10000003)
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("To: \"ToOne\" <to1@x>"), eml);
        assertTrue(eml.contains("Cc: \"CcOne\" <cc1@x>"), eml);
        assertTrue(eml.contains("Bcc: \"BccOne\" <bcc1@x>"), eml);
    }

    @Test
    void emptyStreamFailsLoudly() {
        // Must be the domain ConversionException specifically — a regression back to leaking POI
        // internals (NotOLE2FileException etc.) would still have passed the old catch-anything test.
        var bytes = new byte[] {0x00, 0x01, 0x02};
        var out = new java.io.ByteArrayOutputStream();
        assertThrows(
                ConversionException.class,
                () -> MsgToEmlConverter.convert(new ByteArrayInputStream(bytes), out, ConversionLog.NOOP));
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
    void bracketedAttachContentIdStillResolvesInlineImage() throws Exception {
        // PR_ATTACH_CONTENT_ID is defined without angle brackets ([MS-OXCMSG] §2.2.2.5), but a sender may
        // store "<logo@x>". The cid: reference in the HTML body carries none, so the bracketed form must
        // be normalised or the part is demoted from an inline multipart/related member to a plain
        // attachment and its image stops rendering.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Inline image, bracketed cid")
                .sender("A", "a@x")
                .recipientTo("B", "b@x")
                .htmlBody("<img src=\"cid:logo@x\">")
                .attachment("logo.png", "image/png", new byte[] {1, 2, 3}, "<logo@x>")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Content-ID: <logo@x>"), eml);
        assertTrue(
                eml.contains("multipart/related"),
                "a bracketed Content-ID must still match the cid: reference and stay inline: " + eml);
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
        MsgToEmlConverter.convert(new ByteArrayInputStream(bytes), out, ConversionLog.NOOP);
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
                ConversionException.class,
                () -> MsgToEmlConverter.convert(new ByteArrayInputStream(garbage), out, ConversionLog.NOOP));
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

    @Test
    void mimeVersionSurvivesWhenTransportHeadersDeclareIt() throws Exception {
        // The original MIME-Version is filtered out together with the original Content-Type (the
        // serializer re-encodes the body), but before the fix it was also marked "present", so the
        // output ended up with a multipart Content-Type and no MIME-Version header at all.
        var headers = "MIME-Version: 1.0\r\n"
                + "From: sender@example.com\r\n"
                + "To: receiver@example.com\r\n"
                + "Content-Type: text/plain; charset=us-ascii\r\n";
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .sender("Sender", "sender@example.com")
                .recipientTo("Receiver", "receiver@example.com")
                .transportHeaders(headers)
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("MIME-Version: 1.0"), eml);
    }

    @Test
    void threadingHeadersAreRecoveredFromMapiProperties() throws Exception {
        // Without stored transport headers, In-Reply-To/References used to be dropped entirely,
        // breaking reply threading in the exported EML.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Re: thread")
                .sender("A", "a@x")
                .recipientTo("B", "b@x")
                .inReplyTo("<parent-id@example.com>")
                .references("<root-id@example.com> <parent-id@example.com>")
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("In-Reply-To: <parent-id@example.com>"), eml);
        assertTrue(eml.contains("References: <root-id@example.com> <parent-id@example.com>"), eml);
    }

    @Test
    void crLfInAttachmentFilenameCannotInjectHeaders() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .sender("A", "a@x")
                .recipientTo("B", "b@x")
                .textBody("body")
                .attachment("evil.txt\r\nX-Injected: yes", "application/octet-stream", new byte[] {1})
                .toBytes();

        var eml = convertString(bytes);

        // Before the fix the quoted filename parameter emitted the CR/LF raw, splitting the
        // Content-Disposition header and injecting an attacker-controlled X-Injected header line.
        assertFalse(eml.lines().anyMatch(line -> line.startsWith("X-Injected:")), eml);
        assertTrue(eml.contains("filename=\"evil.txt__X-Injected: yes\""), eml);
    }

    @Test
    void attachmentWithoutPayloadIsEmittedEmptyAndLogged() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .sender("A", "a@x")
                .recipientTo("B", "b@x")
                .textBody("body")
                .attachment("ghost.bin", "application/octet-stream", null)
                .toBytes();

        var errors = new ArrayList<String>();
        var log = new ConversionLog() {
            @Override
            public void info(String message) {}

            @Override
            public void error(String message) {
                errors.add(message);
            }
        };
        var out = new java.io.ByteArrayOutputStream();
        MsgToEmlConverter.convert(new ByteArrayInputStream(bytes), out, log);
        var eml = out.toString(java.nio.charset.StandardCharsets.US_ASCII);

        // Before the fix the extraction failure was swallowed (catch Exception ignored) — the
        // attachment silently became a zero-byte file with no console trace.
        assertTrue(eml.contains("filename=\"ghost.bin\""), eml);
        assertTrue(errors.stream().anyMatch(message -> message.contains("no data")), errors::toString);
    }

    @Test
    void sclExportedEvenWhenTransportHeadersCarryMessageId() throws Exception {
        // R1: the SCL property used to be read with a MAPIProperty.createCustom lookup key, which can
        // never match a POI property map entry (MAPIProperty has no equals/hashCode) — the header was
        // never emitted. R2: even with a value, emission was nested inside Message-ID synthesis and
        // was dropped whenever the transport headers already declared a Message-ID.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .textBody("body")
                .spamConfidenceLevel(5)
                .transportHeaders("Message-ID: <keep@example.com>\r\nFrom: orig@example.com\r\n")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("X-MS-Exchange-Organization-SCL: 5"), eml);
        assertTrue(eml.contains("Message-ID: <keep@example.com>"), eml);
    }

    @Test
    void senderSmtpAddressPreferredOverExchangeDn() throws Exception {
        // R1: PidTagSenderSmtpAddress (0x5D01) was read with a createCustom key that never matches,
        // so the SMTP form Exchange stores beside the X.500 DN was ignored.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .textBody("body")
                .sender("Kevin", "/O=ORG/OU=ADMIN GROUP/CN=RECIPIENTS/CN=KEVIN")
                .senderAddrType("EX")
                .senderSmtpAddress("kevin@example.com")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("From: \"Kevin\" <kevin@example.com>"), eml);
        assertFalse(eml.contains("/O=ORG"), eml);
    }

    @Test
    void exchangeDnSenderWithoutSmtpFormIsEncapsulated() throws Exception {
        // R3: an X.500 DN containing "@" inside a CN segment used to bypass IMCEA encapsulation
        // (the heuristic was a bare contains("@")) and leak raw into the From angle brackets.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .textBody("body")
                .sender("Kevin Roast", "/O=HOSTED/OU=FIRST ADMIN GROUP/CN=RECIPIENTS/CN=KEVIN.ROAST@BEN")
                .senderAddrType("EX")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("IMCEAEX-"), eml);
        assertTrue(eml.contains("@invalid>"), eml);
        assertFalse(eml.contains("</O="), eml);
    }

    @Test
    void exchangeRecipientWithoutSmtpChunkIsEncapsulated() throws Exception {
        // R3, recipient side: same DN-with-@ leak through the recipient table's address-type fallback.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .textBody("body")
                .recipientToWithoutSmtp("Jane", "/O=ORG/OU=AD GROUP/CN=RECIPIENTS/CN=JANE.DOE@HQ", "EX")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("IMCEAEX-"), eml);
        assertFalse(eml.contains("</O="), eml);
    }

    @Test
    void exchangeRecipientKeepsFullLegacyDnNotPoiTruncatedFragment() throws Exception {
        // POI's getRecipientEmailAddress() returns only the substring after the first "/CN=", dropping
        // the mandatory /O= and /OU= X.500 prefix. Reading the raw PR_EMAIL_ADDRESS keeps the full DN so
        // the IMCEAEX address matches the PST path and stays roundtrippable. In the encapsulation "="
        // escapes to _x003D_ and "/" to _, so the /O=ORG and /OU=EXG segments POI drops must survive.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .textBody("body")
                .recipientToWithoutSmtp("Jane Doe", "/O=ORG/OU=EXG/CN=RECIPIENTS/CN=JDOE", "EX")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("IMCEAEX-_O_x003D_ORG_OU_x003D_EXG_CN_x003D_RECIPIENTS_CN_x003D_JDOE@invalid"), eml);
    }

    @Test
    void corruptRtfBodyDegradesInsteadOfFailingConversion() throws Exception {
        // R4: POI surfaces a truncated/garbled compressed-RTF stream as an unchecked exception;
        // before the fix it escaped populateBodies and failed the whole conversion even though the
        // plain-text body was perfectly readable.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .textBody("still here")
                .corruptRtfBody()
                .toBytes();

        var errors = new ArrayList<String>();
        var log = new ConversionLog() {
            @Override
            public void info(String message) {}

            @Override
            public void error(String message) {
                errors.add(message);
            }
        };
        var out = new java.io.ByteArrayOutputStream();
        MsgToEmlConverter.convert(new ByteArrayInputStream(bytes), out, log);
        var eml = out.toString(java.nio.charset.StandardCharsets.US_ASCII);

        assertTrue(eml.contains("still here"), eml);
        assertTrue(errors.stream().anyMatch(message -> message.contains("RTF")), errors::toString);
    }

    @Test
    void genuineRtfBodyShipsAsBodyRtfAttachmentWithPlainFallback() throws Exception {
        // R9: a genuine RTF body used to be emitted as an unrenderable text/rtf alternative that
        // dominated the message size; it now mirrors the PST converter (body.rtf attachment plus a
        // stripped plain-text body when no other body exists).
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .rtfBody("{\\rtf1 Hello \\b world\\b0 .}")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("filename=\"body.rtf\""), eml);
        assertTrue(eml.contains("Content-Type: application/rtf"), eml);
        assertTrue(eml.contains("Hello world."), eml);
        assertFalse(eml.contains("text/rtf"), eml);
    }

    @Test
    void htmlEncapsulatedRtfIsDroppedWhenHtmlBodyPresent() throws Exception {
        // R9: HTML-encapsulated RTF is just a transport encoding of the HTML body that sits beside
        // it; re-emitting it doubled the message size for no renderable gain.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .htmlBody("<p>real</p>")
                .rtfBody("{\\rtf1\\ansi\\fromhtml1 {\\*\\htmltag84 <b>}x{\\*\\htmltag92 </b>}}")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("<p>real</p>"), eml);
        assertFalse(eml.contains("text/rtf"), eml);
        assertFalse(eml.contains("application/rtf"), eml);
    }

    @Test
    void htmlEncapsulatedRtfRecoversHtmlWhenNoHtmlBody() throws Exception {
        // R8/R9: hex escapes inside htmltag runs are decoded (the "=" below) and the uc-1 ANSI
        // fallback "?" after the decoded Cyrillic code point is skipped instead of duplicated.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .rtfBody(
                        "{\\rtf1\\ansi\\fromhtml1\\uc1 {\\*\\htmltag84 <a href=\"a\\'3db\">}\\u1055?{\\*\\htmltag92 </a>}}")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Content-Type: text/html; charset=UTF-8"), eml);
        // quoted-printable form of <a href="a=b">П</a>
        assertTrue(eml.contains("<a href=3D\"a=3Db\">=D0=9F</a>"), eml);
        assertFalse(eml.contains("text/rtf"), eml);
    }

    @Test
    void displayToFallbackUsesPlaceholderForBareNames() throws Exception {
        // R7: with no structured recipient table, bare display names were emitted as both the name
        // and the angle-addr ("John Doe" <John Doe>) — an unparseable To header.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .textBody("body")
                .displayTo("John Doe; jane@example.com")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("\"John Doe\" <undisclosed@invalid>"), eml);
        assertTrue(eml.contains("<jane@example.com>"), eml);
        assertFalse(eml.contains("<John Doe>"), eml);
    }

    @Test
    void embeddedMessagesWithEqualSubjectsGetDistinctFilenames() throws Exception {
        // R12: two embedded messages with the same subject used to produce identically named
        // message/rfc822 attachments.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("outer")
                .textBody("body")
                .embeddedAttachment(
                        "a", MsgFixtureBuilder.topLevel().subject("Dup").textBody("one"))
                .embeddedAttachment(
                        "b", MsgFixtureBuilder.topLevel().subject("Dup").textBody("two"))
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("filename=\"Dup.eml\""), eml);
        assertTrue(eml.contains("filename=\"Dup (2).eml\""), eml);
    }

    @Test
    void recipientOverflowIsTruncatedNotFatal() throws Exception {
        // R12: more than 2048 recipients used to abort the whole conversion; the cap now truncates
        // with a logged warning instead.
        var builder = MsgFixtureBuilder.topLevel().subject("big").textBody("body");
        for (var index = 0; index < 2050; index++) {
            builder.recipientTo("R" + index, "r" + index + "@example.com");
        }

        var errors = new ArrayList<String>();
        var log = new ConversionLog() {
            @Override
            public void info(String message) {}

            @Override
            public void error(String message) {
                errors.add(message);
            }
        };
        var out = new java.io.ByteArrayOutputStream();
        MsgToEmlConverter.convert(new ByteArrayInputStream(builder.toBytes()), out, log);
        var eml = out.toString(java.nio.charset.StandardCharsets.US_ASCII);

        var exported = eml.split("@example\\.com", -1).length - 1;
        assertEquals(2048, exported, eml.substring(0, Math.min(eml.length(), 500)));
        assertTrue(errors.stream().anyMatch(message -> message.contains("2050")), errors::toString);
    }

    // N1: POI's isEmbeddedMessage() is just "has a sub-storage", which also matches an embedded OLE
    // object (PR_ATTACH_METHOD 6, e.g. a pasted Excel sheet). Routing it through the
    // embedded-message branch replaced the payload with an error stub; it must instead be
    // re-wrapped as an OLE2 compound-file attachment.
    @Test
    void oleObjectAttachmentIsPreservedNotDestroyed() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("OLE object")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .textBody("see the embedded sheet")
                .oleAttachment("Worksheet", "spreadsheet-cells".getBytes(StandardCharsets.US_ASCII))
                .toBytes();

        var eml = convertString(bytes);

        assertFalse(eml.contains("Error converting nested message"), eml);
        assertFalse(eml.contains("message/rfc822"), eml);
        assertTrue(eml.contains("filename=\"Worksheet.ole\""), eml);
        // The rewrapped storage is an OLE2 compound file: its magic D0 CF 11 E0 A1 B1 1A E1
        // base64-encodes to this prefix.
        assertTrue(eml.contains("0M8R4KGxGuE"), eml);
    }

    // N4: RFC 5322 §3.6.2 — on a delegate send, From carries the author (PR_SENT_REPRESENTING_*)
    // and Sender the actual transmitter (PR_SENDER_*); previously the author was dropped entirely.
    @Test
    void delegateSendSplitsFromAndSender() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Delegate send")
                .sender("Assistant", null)
                .senderSmtpAddress("assistant@corp.example")
                .sentRepresenting("Boss", "boss@corp.example")
                .recipientTo("Bob", "bob@example.com")
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("From: \"Boss\" <boss@corp.example>"), eml);
        assertTrue(eml.contains("Sender: \"Assistant\" <assistant@corp.example>"), eml);
    }

    // N4: a message carrying only the author's SMTP form (0x5D02) used to pair the transmitter's
    // display name with the author's address — provably false data.
    @Test
    void authorOnlySmtpAddressKeepsAuthorName() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("No cross pairing")
                .sender("Assistant", null)
                .sentRepresenting("Boss", "boss@corp.example")
                .recipientTo("Bob", "bob@example.com")
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("From: \"Boss\" <boss@corp.example>"), eml);
        assertFalse(eml.contains("\"Assistant\" <boss@corp.example>"), eml);
    }

    // N3: RFC 5322 §3.6.2 makes From mandatory; a message with no sender properties at all gets
    // the explicit placeholder instead of an unparseable From-less message.
    @Test
    void messageWithoutAnySenderGetsPlaceholderFrom() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("No sender")
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("From: <undisclosed@invalid>"), eml);
        assertTrue(eml.contains("X-MailKit-Synthesized-Headers: From,"), eml);
    }

    @Test
    void bccRecipientsAreExported() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Bcc")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .recipientBcc("Carol", "carol@example.com")
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Bcc: \"Carol\" <carol@example.com>"), eml);
        assertTrue(eml.contains("To: \"Bob\" <bob@example.com>"), eml);
        assertFalse(eml.contains("To: \"Bob\" <bob@example.com>, \"Carol\""), eml);
    }

    // N5 (parity with the PST pipeline): importance and sensitivity map onto headers.
    @Test
    void importanceAndSensitivityAreExported() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Urgent")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .importance(2)
                .sensitivity(2)
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Importance: High"), eml);
        assertTrue(eml.contains("X-Priority: 1"), eml);
        assertTrue(eml.contains("Sensitivity: Private"), eml);
    }

    @Test
    void normalImportanceAndSensitivityStayImplicit() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Normal")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .importance(1)
                .sensitivity(0)
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertFalse(eml.contains("Importance:"), eml);
        assertFalse(eml.contains("X-Priority:"), eml);
        assertFalse(eml.contains("Sensitivity:"), eml);
    }

    // N5: PR_CONVERSATION_TOPIC / PR_CONVERSATION_INDEX export as the Thread-* headers Outlook
    // itself uses, so conversation threading survives the conversion.
    @Test
    void threadingHeadersExportedFromConversationProperties() throws Exception {
        var conversationIndex = new byte[] {1, 2, 3, 4, 5, 6};
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Re: Budget")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .conversationTopic("Budget 2026")
                .conversationIndex(conversationIndex)
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Thread-Topic: Budget 2026"), eml);
        assertTrue(eml.contains("Thread-Index: " + Base64.getEncoder().encodeToString(conversationIndex)), eml);
    }

    // N5: PR_REPLY_RECIPIENT_ENTRIES (a one-off FLATENTRYLIST, the same [MS-OXCDATA] structure a
    // PST stores) is parsed into a Reply-To header.
    @Test
    void replyToRecoveredFromFlatEntryList() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Reply elsewhere")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .replyTo("Support Desk", "support@corp.example")
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Reply-To: \"Support Desk\" <support@corp.example>"), eml);
    }

    // N13: PR_READ_RECEIPT_REQUESTED maps to Disposition-Notification-To (RFC 8098).
    @Test
    void readReceiptRequestMapsToDispositionNotificationTo() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Receipt please")
                .sender("Alice", null)
                .senderSmtpAddress("alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .readReceiptRequested()
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Disposition-Notification-To: \"Alice\" <alice@example.com>"), eml);
    }

    // N11: the HTML body is re-encoded as UTF-8; a surviving <meta charset> declaring the original
    // codepage would make meta-honoring clients mojibake the part.
    @Test
    void htmlMetaCharsetIsRewrittenToUtf8() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Meta")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .htmlBody("<html><head><meta http-equiv=\"Content-Type\""
                        + " content=\"text/html; charset=windows-1251\"></head><body>ok</body></html>")
                .toBytes();

        var eml = convertString(bytes);

        assertFalse(eml.contains("windows-1251"), eml);
        // quoted-printable escapes '=' as =3D, so charset=UTF-8 appears as charset=3DUTF-8 (soft
        // line wraps stripped first).
        assertTrue(eml.replace("=\r\n", "").contains("charset=3DUTF-8"), eml);
    }

    // N9: Outlook assigns Content-IDs to attachments no body references; such a part must stay a
    // visible regular attachment instead of an invisible inline member of multipart/related.
    @Test
    void unreferencedContentIdAttachmentStaysVisible() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Unreferenced cid")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .htmlBody("<p>no inline images here</p>")
                .attachment("logo.png", "image/png", new byte[] {1, 2, 3}, "img1@local")
                .toBytes();

        var eml = convertString(bytes);

        assertFalse(eml.contains("multipart/related"), eml);
        assertTrue(eml.contains("Content-Disposition: attachment; filename=\"logo.png\""), eml);
        assertTrue(eml.contains("Content-ID: <img1@local>"), eml);
    }

    // N12: a Content-ID without a domain half is not a valid msg-id (RFC 5322 §3.6.4); a synthetic
    // domain is appended and the HTML cid: reference rewritten in step so it keeps resolving.
    @Test
    void contentIdWithoutDomainGetsSyntheticDomain() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Bare cid")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .htmlBody("<img src=\"cid:plainid\">")
                .attachment("a.png", "image/png", new byte[] {1}, "plainid")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("multipart/related"), eml);
        assertTrue(eml.contains("Content-ID: <plainid@mailkit.invalid>"), eml);
        assertTrue(eml.replace("=\r\n", "").contains("cid:plainid@mailkit.invalid"), eml);
    }

    @Test
    void zeroByteAttachmentRoundTrips() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Empty attachment")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .textBody("body")
                .attachment("empty.bin", "application/octet-stream", new byte[0])
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Content-Disposition: attachment; filename=\"empty.bin\""), eml);
    }

    @Test
    void duplicateAttachmentFilenamesBothSurvive() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Duplicates")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .textBody("body")
                .attachment("data.bin", "application/octet-stream", new byte[] {1})
                .attachment("data.bin", "application/octet-stream", new byte[] {2})
                .toBytes();

        var eml = convertString(bytes);

        var parts = eml.split("filename=\"data\\.bin\"", -1).length - 1;
        assertEquals(2, parts, eml);
    }

    // Pre-1980 FILETIME dates must format correctly (the corpus has none: message_1979.msg's name
    // refers to a POI property-stream layout, not a date).
    @Test
    void pre1980DateIsExported() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Vintage")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .messageDate(new Date(170_000_000_000L)) // 1975-05-22T14:13:20Z
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Date: Thu, 22 May 1975 14:13:20 +0000"), eml);
    }

    // Astral-plane (surrogate-pair) characters survive both header encoding and the QP body.
    @Test
    void astralPlaneSubjectAndBodySurvive() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Launch 🚀 plan")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .textBody("Done 😀")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("=?UTF-8?B?"), eml);
        assertTrue(eml.replace("=\r\n", "").contains("=F0=9F=98=80"), eml); // 😀 in UTF-8 QP
        assertTrue(eml.chars().allMatch(chr -> chr <= 0x7F), "EML output must remain ASCII");
    }

    // RTL Hebrew text survives the UTF-8 QP re-encoding.
    @Test
    void rtlHebrewBodySurvives() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("שלום")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .textBody("שלום עולם")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.replace("=\r\n", "").contains("=D7=A9=D7=9C=D7=95=D7=9D"), eml); // שלום in UTF-8 QP
    }

    // REPORT.* messages are now handled by emitReport and produce a multipart/report body (RFC 6522).
    // IPM.Note.IPNRN (read receipt stored as a plain note) goes through the normal body path.
    @Test
    void reportNdrClassProducesMultipartReport() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Undeliverable: Hello")
                .sender("Postmaster", "postmaster@example.com")
                .recipientTo("Bob", "bob@example.com")
                .messageClass("REPORT.IPM.Note.NDR")
                .reportText("Delivery has failed.")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("multipart/report"), "NDR must produce multipart/report: " + eml);
        assertTrue(
                eml.contains("report-type=delivery-status"),
                "NDR multipart/report must carry report-type=delivery-status: " + eml);
        assertTrue(
                eml.contains("Content-Type: message/delivery-status"),
                "NDR must include a message/delivery-status part: " + eml);
        assertTrue(
                eml.contains("Action: failed"), "NDR must include Action: failed (derived from .NDR suffix): " + eml);
        assertTrue(eml.contains("From: \"Postmaster\" <postmaster@example.com>"), eml);
    }

    @Test
    void reportDrClassProducesMultipartReportWithDeliveredAction() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Delivered: Hello")
                .sender("Postmaster", "postmaster@example.com")
                .recipientTo("Bob", "bob@example.com")
                .messageClass("REPORT.IPM.Note.DR")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("multipart/report"), eml);
        assertTrue(eml.contains("Action: delivered"), "DR class must produce Action: delivered: " + eml);
    }

    @Test
    void reportReadReceiptClassProducesDispositionNotification() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Read: Hello")
                .sender("Postmaster", "postmaster@example.com")
                .recipientTo("Bob", "bob@example.com")
                .messageClass("REPORT.IPM.Note.IPNRN")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("multipart/report"), eml);
        assertTrue(
                eml.contains("report-type=disposition-notification"),
                "read receipt must carry report-type=disposition-notification: " + eml);
        assertTrue(
                eml.contains("Content-Type: message/disposition-notification"),
                "read receipt must include a message/disposition-notification part: " + eml);
    }

    @Test
    void readReceiptFinalRecipientIsTheReaderNotTheOriginalSender() throws Exception {
        // rfc8098 §3.2.4: an MDN's Final-Recipient is the party who read the message and issues the
        // receipt — the receipt's own sender — not its To recipient (the original sender who requested
        // the receipt). The pre-fix code used PidTagDisplayTo and emitted "rfc822; unknown" here.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Read: Hello")
                .sender("Reader", "reader@example.com")
                .recipientTo("Original Sender", "origin@example.com")
                .messageClass("REPORT.IPM.Note.IPNRN")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(
                eml.contains("Final-Recipient: rfc822; reader@example.com"),
                "MDN Final-Recipient must be the reader (receipt sender), not the original sender: " + eml);
    }

    @Test
    void readReceiptNoteClassConvertAsPlainEmail() throws Exception {
        // IPM.Note.IPNRN is NOT a REPORT.* class, so it goes through the normal body path.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Read receipt note")
                .sender("Postmaster", "postmaster@example.com")
                .recipientTo("Bob", "bob@example.com")
                .messageClass("IPM.Note.IPNRN")
                .textBody("Your message has been read.")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("Your message has been read."), eml);
        assertTrue(eml.contains("From: \"Postmaster\" <postmaster@example.com>"), eml);
        assertFalse(
                eml.contains("multipart/report"),
                "IPM.Note.IPNRN is not a REPORT.* class and must not produce multipart/report: " + eml);
    }

    // --- Task method fix: IPM.TaskRequest must NOT be mislabeled as a plain PUBLISH task ---
    // Regression: the old startsWith("IPM.Task") check swallowed IPM.TaskRequest as a plain task.
    @Test
    void taskRequestClassProducesMethodRequest() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Please complete this task")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .messageClass("IPM.TaskRequest")
                .textBody("Task details")
                .toBytes();

        var eml = convertString(bytes);

        // The task.ics attachment Content-Type header must carry method=REQUEST, not method=PUBLISH.
        // The ical body is base64-encoded in the EML, so assertions target the visible Content-Type line.
        assertTrue(
                eml.contains("method=REQUEST"),
                "IPM.TaskRequest must produce method=REQUEST in task.ics Content-Type (regression: was PUBLISH): "
                        + eml);
        assertFalse(
                eml.contains("method=PUBLISH"),
                "IPM.TaskRequest must NOT produce method=PUBLISH (old startsWith bug): " + eml);
        assertTrue(eml.contains("task.ics"), "task.ics attachment must be present: " + eml);
    }

    @Test
    void plainTaskClassProducesMethodPublish() throws Exception {
        // Plain IPM.Task (no "Request" suffix) must still produce PUBLISH.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("My task")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .messageClass("IPM.Task")
                .textBody("Task details")
                .toBytes();

        var eml = convertString(bytes);

        // The ical body is base64-encoded; assertions target the visible Content-Type header line.
        assertTrue(
                eml.contains("method=PUBLISH"),
                "plain IPM.Task must produce method=PUBLISH in task.ics Content-Type: " + eml);
        assertFalse(eml.contains("method=REQUEST"), eml);
        assertTrue(eml.contains("task.ics"), eml);
    }

    @Test
    void taskRequestAcceptProducesMethodReply() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Accepted: task")
                .sender("Bob", "bob@example.com")
                .recipientTo("Alice", "alice@example.com")
                .messageClass("IPM.TaskRequest.Accept")
                .textBody("Accepted")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("method=REPLY"), "IPM.TaskRequest.Accept must produce method=REPLY: " + eml);
    }

    @Test
    void taskRequestDeclineProducesMethodReply() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Declined: task")
                .sender("Bob", "bob@example.com")
                .recipientTo("Alice", "alice@example.com")
                .messageClass("IPM.TaskRequest.Decline")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(eml.contains("method=REPLY"), "IPM.TaskRequest.Decline must produce method=REPLY: " + eml);
    }

    // --- downgrade log: unrecognized message classes emit an info log entry ---
    @Test
    void unknownMessageClassEmitsDowngradeLog() throws Exception {
        var loggedMessages = new ArrayList<String>();
        var log = new ConversionLog() {
            @Override
            public void info(String message) {
                loggedMessages.add(message);
            }

            @Override
            public void error(String message) {}
        };
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("S")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .messageClass("IPM.Activity")
                .textBody("body")
                .toBytes();
        var out = new java.io.ByteArrayOutputStream();
        MsgToEmlConverter.convert(new ByteArrayInputStream(bytes), out, log);

        assertTrue(
                loggedMessages.stream().anyMatch(message -> message.contains("IPM.Activity")),
                "an unrecognized message class must trigger an info downgrade log: " + loggedMessages);
    }

    // --- finding 1: DSN Status must be a d.d.d code (rfc3464 §2.3.4), not the free-form
    //     PR_SUPPLEMENTARY_INFO text, which belongs in Diagnostic-Code (rfc3464 §2.3.6). ---
    @Test
    void ndrStatusIsEnhancedCodeAndSupplementaryTextGoesToDiagnosticCode() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Undeliverable: Hello")
                .sender("Postmaster", "postmaster@example.com")
                .recipientTo("Failed Person", "failed@remote.example.com")
                .messageClass("REPORT.IPM.Note.NDR")
                .reportText("Delivery to the following recipient failed permanently.")
                .supplementaryInfo("550 5.1.1 <failed@remote.example.com>: Recipient address rejected: User unknown")
                .toBytes();

        var eml = convertString(bytes);

        // rfc3464 §2.3.4: Status is a class.subject.detail code recovered from the supplementary text,
        // never the free text itself (the old code fed PR_SUPPLEMENTARY_INFO straight into Status).
        assertTrue(eml.contains("Status: 5.1.1"), "Status must be the d.d.d code parsed from the report: " + eml);
        assertFalse(
                eml.contains("Status: 550 5.1.1") || eml.contains("Status: smtp"),
                "Status must not carry the free-form supplementary/diagnostic text: " + eml);
        // rfc3464 §2.3.6: the free-form transport text is the Diagnostic-Code, not the Status.
        assertTrue(
                unfold(eml)
                        .contains("Diagnostic-Code: smtp; 550 5.1.1 <failed@remote.example.com>: Recipient"
                                + " address rejected: User unknown"),
                "the supplementary text must be routed to Diagnostic-Code: " + eml);
    }

    // --- finding 1 (default): a report with no parseable status code defaults to 5.0.0, not free text. ---
    @Test
    void ndrWithoutEnhancedCodeDefaultsStatusToPermanentFailure() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Undeliverable")
                .sender("Postmaster", "postmaster@example.com")
                .recipientTo("Failed", "failed@remote.example.com")
                .messageClass("REPORT.IPM.Note.NDR")
                .reportText("Your message could not be delivered.")
                .supplementaryInfo("Remote host said: mailbox full")
                .toBytes();

        var eml = convertString(bytes);

        // rfc3463 §3 "other or undefined permanent failure" when no finer code is available.
        assertTrue(eml.contains("Status: 5.0.0"), "missing status code must default to 5.0.0: " + eml);
        assertTrue(
                eml.contains("Diagnostic-Code: smtp; Remote host said: mailbox full"),
                "the supplementary text is still surfaced as Diagnostic-Code: " + eml);
    }

    // --- finding 2: Final-Recipient must be the address that failed (rfc3464 §2.3.2), taken from the
    //     report's recipient table — not the bounce's own PR_DISPLAY_TO. ---
    @Test
    void ndrFinalRecipientIsTheFailedAddressNotTheBounceRecipient() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Undeliverable: Hello")
                .sender("Postmaster", "postmaster@example.com")
                // The recipient table of the NDR holds the failed recipient.
                .recipientTo("Failed Person", "failed@remote.example.com")
                // PR_DISPLAY_TO is the bounce's own recipient (the original sender) — must NOT be used.
                .displayTo("original-sender@example.com")
                .messageClass("REPORT.IPM.Note.NDR")
                .reportText("Delivery failed.")
                .toBytes();

        var eml = convertString(bytes);

        assertTrue(
                eml.contains("Final-Recipient: rfc822; failed@remote.example.com"),
                "Final-Recipient must be the failed recipient address: " + eml);
        assertFalse(
                eml.contains("Final-Recipient: rfc822; original-sender@example.com"),
                "Final-Recipient must not be the bounce's own PR_DISPLAY_TO: " + eml);
    }

    // --- finding 3: BCC-class recipients must be excluded from iCal ATTENDEE lines (RFC 5546 —
    //     attendees are the visible participants). Drives the calendar path with a synthesized
    //     __nameid appointment-start so the meeting REQUEST emits ATTENDEE lines. ---
    @Test
    void bccRecipientIsExcludedFromMeetingAttendees() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .messageClass("IPM.Schedule.Meeting.Request")
                .subject("Project kickoff")
                .sender("Chair Person", "chair@example.com")
                .recipientTo("Visible Attendee", "visible@example.com")
                .recipientBcc("Hidden Person", "hidden@example.com")
                .appointmentStartEnd(new Date(1490725800000L), new Date(1490727600000L))
                .textBody("Agenda inside")
                .toBytes();

        var eml = convertString(bytes);
        var invite = decodedInvite(eml);

        assertTrue(unfold(eml).contains("method=REQUEST"), "meeting request must produce a REQUEST invite: " + eml);
        assertTrue(invite.contains("METHOD:REQUEST"), invite);
        assertTrue(
                invite.contains("ATTENDEE;CN=\"Visible Attendee\":mailto:visible@example.com"),
                "the visible To recipient must appear as an ATTENDEE: " + invite);
        assertFalse(
                invite.contains("hidden@example.com"),
                "a BCC recipient must never leak into an iCal ATTENDEE line: " + invite);
    }

    @Test
    void meetingResponseReplyOrganizerIsTheToRecipientNotACcDelegate() throws Exception {
        // RFC 5546 §3.2.3: a meeting-response REPLY flows from the responding attendee (the sender) to
        // the meeting ORGANIZER, which Outlook stores as the response's To recipient. A Cc'd delegate
        // must not be promoted to ORGANIZER, and the single ATTENDEE is the responder with its PARTSTAT.
        // (This in-memory case lists the To recipient first; the Cc-before-To ordering edge — where the
        // organizer-selection fix actually changes the outcome — is covered end-to-end by the vendored
        // meeting_response_accepted.msg in MsgSampleCorpusTest.)
        var bytes = MsgFixtureBuilder.topLevel()
                .messageClass("IPM.Schedule.Meeting.Resp.Pos")
                .subject("Accepted: Project kickoff")
                .sender("Responding Attendee", "responder@example.com")
                .recipientTo("Meeting Organizer", "organizer@example.com")
                .recipientCc("Delegate", "delegate@example.com")
                .appointmentStartEnd(new Date(1490725800000L), new Date(1490727600000L))
                .textBody("Accepted")
                .toBytes();

        var eml = convertString(bytes);
        // iCal folds lines longer than 75 octets as CRLF + a single WSP (rfc5545 §3.1); unfolding
        // removes both so a long ATTENDEE address is not split mid-token before the substring checks.
        var invite = decodedInvite(eml).replace("\r\n ", "").replace("\r\n\t", "");

        assertTrue(unfold(eml).contains("method=REPLY"), "a meeting response must produce a REPLY invite: " + eml);
        assertTrue(invite.contains("METHOD:REPLY"), invite);
        assertTrue(
                invite.contains("ORGANIZER;CN=\"Meeting Organizer\":mailto:organizer@example.com"),
                "the meeting organizer is the To recipient: " + invite);
        assertFalse(
                invite.contains("delegate@example.com"),
                "a Cc delegate must not appear as the organizer of a REPLY: " + invite);
        assertTrue(
                invite.contains("ATTENDEE;CN=\"Responding Attendee\";PARTSTAT=ACCEPTED:mailto:responder@example.com"),
                "the REPLY's single ATTENDEE is the responder carrying PARTSTAT: " + invite);
    }

    // round-4 audit: a meeting's iCal UID MUST be its stable identity (PidLidCleanGlobalObjectId),
    // not a random UUID, or a client cannot correlate a REQUEST/REPLY/CANCEL of the same meeting
    // (rfc5545 §3.8.4.7, rfc5546 §3.2; [MS-OXCICAL] §2.1.3.1.1.20.26 maps the bytes to UID as
    // uppercase hex).
    @Test
    void meetingInviteUidIsUppercaseHexOfCleanGlobalObjectId() throws Exception {
        // A representative CleanGlobalObjectId: the [MS-OXOCAL] byte-array id header followed by a
        // mixed-case-significant tail, so the assertion proves both the source property and the
        // uppercase-hex formatting.
        var cleanGoid = new byte[] {
            0x04, 0x00, 0x00, 0x00, (byte) 0x82, 0x00, (byte) 0xE0, 0x00,
            0x74, (byte) 0xC5, (byte) 0xB7, 0x10, 0x1A, (byte) 0x82, (byte) 0xE0, 0x08,
            0x00, 0x00, 0x00, 0x00, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x01
        };
        var bytes = MsgFixtureBuilder.topLevel()
                .messageClass("IPM.Schedule.Meeting.Request")
                .subject("Project kickoff")
                .sender("Chair Person", "chair@example.com")
                .recipientTo("Visible Attendee", "visible@example.com")
                .appointmentStartEnd(new Date(1490725800000L), new Date(1490727600000L))
                .meetingCleanGlobalObjectId(cleanGoid)
                .textBody("Agenda inside")
                .toBytes();

        var eml = convertString(bytes);
        // Unfold first: a full GlobalObjectId is long enough that UID may wrap (rfc5545 §3.1).
        var invite = decodedInvite(eml).replace("\r\n ", "").replace("\r\n\t", "");

        var expectedUid = HexFormat.of().withUpperCase().formatHex(cleanGoid);
        assertTrue(
                invite.contains("UID:" + expectedUid + "\r\n"),
                "the VEVENT UID must be the uppercase hex of PidLidCleanGlobalObjectId: " + invite);
    }

    // The fallback half of the same fix: a personal appointment stores no GlobalObjectId, so the UID
    // stays a generated value — but a UID line must still be present and well-formed (rfc5545 §3.8.4.7).
    @Test
    void appointmentWithoutCleanGlobalObjectIdStillCarriesAUid() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .messageClass("IPM.Appointment")
                .subject("Solo focus block")
                .sender("Organizer", "organizer@example.com")
                .appointmentStartEnd(new Date(1490725800000L), new Date(1490727600000L))
                .textBody("Heads down")
                .toBytes();

        var eml = convertString(bytes);
        var invite = decodedInvite(eml).replace("\r\n ", "").replace("\r\n\t", "");

        // No value assertion (it is random); only that exactly one non-empty UID line was emitted.
        assertTrue(
                Pattern.compile("\r\nUID:\\S+\r\n").matcher(invite).find(),
                "every VEVENT must carry a UID even without a stored meeting identity: " + invite);
    }

    // --- finding 4: In-Reply-To/References msg-id references must be angle-bracketed (rfc5322
    //     §3.6.4), the same normalization Message-ID gets — they are stored unbracketed in MAPI. ---
    @Test
    void threadingHeaderReferencesAreAngleBracketNormalized() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Re: thread")
                .sender("A", "a@example.com")
                .recipientTo("B", "b@example.com")
                .inReplyTo("parent-id@example.com")
                .references("root-id@example.com parent-id@example.com")
                .textBody("body")
                .toBytes();

        var eml = convertString(bytes);

        // The stored values lack angle brackets; the converter must add them (rfc5322 §3.6.4).
        assertTrue(eml.contains("In-Reply-To: <parent-id@example.com>"), "In-Reply-To must be angle-bracketed: " + eml);
        assertTrue(
                eml.contains("References: <root-id@example.com> <parent-id@example.com>"),
                "every References msg-id must be angle-bracketed: " + eml);
    }

    /**
     * Codepage regression (MSG-2): a genuinely-UTF-8 PT_STRING8 plain-text body with
     * PR_INTERNET_CPID = 65001 must be decoded as UTF-8. POI's guess7BitEncoding deliberately drops a
     * UTF-8 body codepage, leaving the body decoded as the CP1252 default and mojibaked; the converter
     * now re-applies the CPID, so the body's UTF-8 octets survive the re-encode to the output.
     */
    @Test
    void utf8PlainTextBodyWithCpid65001IsDecodedAsUtf8() throws Exception {
        // Cyrillic "тест" -> UTF-8 D1 82 D0 B5 D1 81 D1 82, quoted-printable =D1=82=D0=B5=D1=81=D1=82.
        var body = "тест";
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Codepage")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .internetCpid(65001)
                .textBodyAnsi(body.getBytes(StandardCharsets.UTF_8))
                .toBytes();

        var eml = convertString(bytes).replace("=\r\n", "");

        assertTrue(
                eml.contains("=D1=82=D0=B5=D1=81=D1=82"),
                "UTF-8 body bytes must survive (not be mis-decoded as CP1252): " + eml);
    }

    /**
     * Codepage regression (MSG-1): an ANSI-MSG attachment filename stored as PT_STRING8 must be
     * decoded with the message codepage. POI's set7BitEncoding never visits attachment chunks, so a
     * non-Latin PR_ATTACH_LONG_FILENAME stayed at the CP1252 default and was mojibaked; the converter
     * now re-decodes attachment strings with PR_MESSAGE_CODEPAGE.
     */
    @Test
    void ansiAttachmentFilenameIsDecodedWithMessageCodepage() throws Exception {
        var windows1251 = Charset.forName("windows-1251");
        // Cyrillic "файл" -> UTF-8 D1 84 D0 B0 D0 B9 D0 BB, RFC 2231 %D1%84%D0%B0%D0%B9%D0%BB.
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Attachment")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .messageCodepage(1251)
                // An ANSI main chunk so POI's has7BitEncodingStrings() triggers the codepage path.
                .textBodyAnsi("ok".getBytes(windows1251))
                .ansiFilenameAttachment(
                        "файл.txt".getBytes(windows1251), "text/plain", "data".getBytes(StandardCharsets.US_ASCII))
                .toBytes();

        var eml = convertString(bytes).replace("=\r\n", "");

        assertTrue(
                eml.contains("filename*0*=UTF-8''%D1%84%D0%B0%D0%B9%D0%BB"),
                "the windows-1251 filename must be decoded with the message codepage: " + eml);
    }

    /**
     * Codepage regression (MSG-3): POI 5.5.1 CodePageUtil.codepageToEncoding(1256, true) returns
     * "Cp1255" (Hebrew) instead of the Arabic charset — an isolated transcription typo in its
     * javaLangFormat=true branch. A legacy ANSI MSG with PR_INTERNET_CPID / PR_MESSAGE_CODEPAGE =
     * 1256 therefore had its PT_STRING8 body decoded as windows-1255, turning Arabic into Hebrew
     * gibberish. The fix maps codepage 1256 explicitly to "windows-1256" before delegating to POI.
     *
     * <p>Byte sequence C7 E1 D3 E1 C7 E3 = "السلام" under windows-1256 (U+0627 U+0644 U+0633
     * U+0644 U+0627 U+0645), but decodes to "ַב׃בַד" under the buggy Cp1255.
     */
    @Test
    void arabicAnsiBodyIsDecodedWithWindows1256NotHebrew() throws Exception {
        // windows-1256 bytes for "السلام" (al-salaam)
        var arabicBytes = new byte[] {(byte) 0xC7, (byte) 0xE1, (byte) 0xD3, (byte) 0xE1, (byte) 0xC7, (byte) 0xE3};
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Arabic body codepage test")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .internetCpid(1256)
                .messageCodepage(1256)
                // ANSI body so POI's has7BitEncodingStrings() triggers the codepage path.
                .textBodyAnsi(arabicBytes)
                .toBytes();

        var eml = convertString(bytes).replace("=\r\n", "");

        // Correct: "السلام" in UTF-8 QP is =D8=A7=D9=84=D8=B3=D9=84=D8=A7=D9=85
        assertTrue(
                eml.contains("=D8=A7=D9=84=D8=B3=D9=84=D8=A7=D9=85"),
                "Arabic body bytes must be decoded with windows-1256 (not Hebrew Cp1255): " + eml);
        // Buggy: under Cp1255 the same bytes produce Hebrew "ַב׃בַד" → =D6=B7=D7=91=D7=83=D7=91=D6=B7=D7=93
        assertFalse(
                eml.contains("=D6=B7=D7=91=D7=83=D7=91=D6=B7=D7=93"),
                "Body must NOT contain Cp1255 Hebrew mojibake: " + eml);
    }

    /**
     * Codepage regression (round 13): the HTML body shares the same POI 1256/932/874/950 typo as the
     * plain-text body, but along two paths the text-body fix never reached. The modern binary
     * {@code PR_HTML} chunk (PidTagHtml, PT_BINARY) is decoded by POI's {@code getHtmlBody()} with
     * {@code codepageToEncoding(PR_INTERNET_CPID, true)} directly, and because it is a {@code ByteChunk}
     * {@code applySourceCodepage} cannot re-decode it; that path also runs with no ANSI string chunks at
     * all (no {@code has7BitEncodingStrings()} gate). An Arabic (cp1256) binary HTML body therefore
     * surfaced as Hebrew, drifting from the PST driver (which decodes PR_HTML via windows-1256). The fix
     * decodes the binary chunk with the corrected charset in {@code readHtmlBody}.
     */
    @Test
    void arabicBinaryHtmlBodyIsDecodedWithWindows1256NotHebrew() throws Exception {
        // windows-1256 bytes for "السلام" (al-salaam), wrapped in an otherwise all-ASCII HTML document.
        var arabicBytes = new byte[] {(byte) 0xC7, (byte) 0xE1, (byte) 0xD3, (byte) 0xE1, (byte) 0xC7, (byte) 0xE3};
        var win1256 = Charset.forName("windows-1256");
        var htmlBytes = ("<html><body>" + new String(arabicBytes, win1256) + "</body></html>").getBytes(win1256);
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Arabic binary HTML codepage test")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                // No ANSI string chunks: the binary PR_HTML decode must be corrected even when
                // has7BitEncodingStrings() is false (so applySourceCodepage never runs).
                .internetCpid(1256)
                .htmlBodyBinary(htmlBytes)
                .toBytes();

        var eml = convertString(bytes).replace("=\r\n", "");

        assertTrue(eml.contains("Content-Type: text/html; charset=UTF-8"), eml);
        // Correct: "السلام" in UTF-8 QP is =D8=A7=D9=84=D8=B3=D9=84=D8=A7=D9=85
        assertTrue(
                eml.contains("=D8=A7=D9=84=D8=B3=D9=84=D8=A7=D9=85"),
                "Binary HTML body bytes must be decoded with windows-1256 (not Hebrew Cp1255): " + eml);
        assertFalse(
                eml.contains("=D6=B7=D7=91=D7=83=D7=91=D6=B7=D7=93"),
                "Binary HTML body must NOT contain Cp1255 Hebrew mojibake: " + eml);
    }

    /**
     * Codepage regression (round 13), legacy-string variant: a string {@code PR_BODY_HTML} (PT_STRING8)
     * chunk is decoded by POI's {@code guess7BitEncoding} with the same buggy {@code codepageToEncoding}
     * htmlbody charset, and {@code applySourceCodepage} used to exclude BODY_HTML entirely, leaving the
     * Hebrew mis-decode in place. The fix re-decodes the BODY_HTML StringChunk with the corrected
     * PR_INTERNET_CPID charset alongside PR_BODY.
     */
    @Test
    void arabicAnsiStringHtmlBodyIsDecodedWithWindows1256NotHebrew() throws Exception {
        var arabicBytes = new byte[] {(byte) 0xC7, (byte) 0xE1, (byte) 0xD3, (byte) 0xE1, (byte) 0xC7, (byte) 0xE3};
        var win1256 = Charset.forName("windows-1256");
        var htmlBytes = ("<html><body>" + new String(arabicBytes, win1256) + "</body></html>").getBytes(win1256);
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Arabic string HTML codepage test")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .internetCpid(1256)
                // ANSI string PR_BODY_HTML so POI's has7BitEncodingStrings() triggers the codepage path.
                .htmlBodyAnsi(htmlBytes)
                .toBytes();

        var eml = convertString(bytes).replace("=\r\n", "");

        assertTrue(eml.contains("Content-Type: text/html; charset=UTF-8"), eml);
        assertTrue(
                eml.contains("=D8=A7=D9=84=D8=B3=D9=84=D8=A7=D9=85"),
                "ANSI string HTML body must be decoded with windows-1256 (not Hebrew Cp1255): " + eml);
        assertFalse(
                eml.contains("=D6=B7=D7=91=D7=83=D7=91=D6=B7=D7=93"),
                "ANSI string HTML body must NOT contain Cp1255 Hebrew mojibake: " + eml);
    }

    /**
     * Codepage regression (round 10): POI's CodePageUtil.codepageToEncoding returns IBM-derived Java
     * charsets for the Microsoft DBCS code pages (932 -> "SJIS" = Shift_JIS, not windows-31j), which
     * differ in thousands of double-byte cells. A Japanese ANSI MSG therefore rendered the wrong glyph
     * versus the same message exported from a PST (which uses CodePages -> windows-31j). The fix pins
     * 932 to windows-31j.
     *
     * <p>CP932/windows-31j byte pair {@code 0x81 0x60} = U+FF5E FULLWIDTH TILDE ("～"); plain Shift_JIS
     * decodes the identical bytes to U+301C WAVE DASH.
     */
    @Test
    void japaneseAnsiBodyDecodedWithWindows31jNotShiftJis() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Japanese body codepage test")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .internetCpid(932)
                .messageCodepage(932)
                .textBodyAnsi(new byte[] {(byte) 0x81, (byte) 0x60})
                .toBytes();

        var eml = convertString(bytes).replace("=\r\n", "");

        // Correct (windows-31j): U+FF5E in UTF-8 QP
        assertTrue(
                eml.contains("=EF=BD=9E"),
                "CP932 0x81 0x60 must decode to U+FF5E via windows-31j, not Shift_JIS U+301C: " + eml);
        // Buggy (Shift_JIS): U+301C in UTF-8 QP
        assertFalse(eml.contains("=E3=80=9C"), "Body must NOT contain Shift_JIS wave-dash mojibake: " + eml);
    }

    /**
     * Codepage regression (round 10): codepage 874 went through POI as "cp874" = x-IBM874, which leaves
     * the Microsoft CP874 punctuation bytes (ellipsis, smart quotes, NBSP) undefined and decodes them
     * to U+FFFD — silent data loss, and a divergence from the PST side (x-windows-874). The fix pins
     * 874 to x-windows-874.
     *
     * <p>CP874/x-windows-874 byte {@code 0x85} = U+2026 HORIZONTAL ELLIPSIS; x-IBM874 has no mapping.
     */
    @Test
    void thaiAnsiBodyDecodedWithWindows874NotIbm874() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Thai body codepage test")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .internetCpid(874)
                .messageCodepage(874)
                .textBodyAnsi(new byte[] {(byte) 0x85})
                .toBytes();

        var eml = convertString(bytes).replace("=\r\n", "");

        // Correct (x-windows-874): U+2026 in UTF-8 QP
        assertTrue(
                eml.contains("=E2=80=A6"),
                "CP874 0x85 must decode to U+2026 via x-windows-874, not IBM874 U+FFFD: " + eml);
        // Buggy (x-IBM874): the undefined byte becomes a U+FFFD replacement char
        assertFalse(eml.contains("=EF=BF=BD"), "Body must NOT contain a U+FFFD replacement char: " + eml);
    }

    /**
     * Codepage regression (round 11): the round-8/10 charsetForCodepage fix corrected the body and
     * attachment-name decode, but POI's guess7BitEncoding decodes EVERY non-body PT_STRING8 chunk —
     * the Subject, display names and named-property values (PidLidLocation/PidLidEmail* feeding the
     * iCal/vCard output) — with the same buggy general charset, and applySourceCodepage did not
     * re-decode them. So a cp932 ANSI Subject kept Shift_JIS (U+301C) instead of windows-31j
     * (U+FF5E). The fix widens applySourceCodepage to all non-body main-store strings.
     */
    @Test
    void japaneseAnsiSubjectDecodedWithWindows31jNotShiftJis() throws Exception {
        var bytes = MsgFixtureBuilder.topLevel()
                .subjectAnsi(new byte[] {(byte) 0x81, (byte) 0x60}) // CP932 0x81 0x60 = U+FF5E FULLWIDTH TILDE
                .textBody("Japanese subject codepage test")
                .sender("Alice", "alice@example.com")
                .recipientTo("Bob", "bob@example.com")
                .internetCpid(932)
                .messageCodepage(932)
                .toBytes();

        var eml = convertString(bytes);

        // Subject is RFC 2047 base64; U+FF5E ("～") UTF-8 EF BD 9E -> base64 "772e"
        assertTrue(
                eml.contains("=?UTF-8?B?772e?="),
                "cp932 ANSI Subject must decode to U+FF5E via windows-31j, not Shift_JIS: " + eml);
        // Buggy Shift_JIS U+301C ("〜") UTF-8 E3 80 9C -> base64 "44Cc"
        assertFalse(eml.contains("=?UTF-8?B?44Cc?="), "Subject must not be Shift_JIS-mojibaked: " + eml);
    }

    /**
     * Codepage regression (round 12): the round-8/10/11 charsetForCodepage fixes corrected the body,
     * attachment name and the main-store strings, but POI's guess7BitEncoding ALSO decodes the
     * recipient-table PT_STRING8 chunks (To/Cc/Bcc PR_DISPLAY_NAME / PR_EMAIL_ADDRESS) with the same
     * buggy general charset, and applySourceCodepage never re-decoded those. So a cp1256 ANSI MSG's
     * recipient name kept the Hebrew Cp1255 mojibake even though its Subject and body rendered Arabic.
     * The fix widens applySourceCodepage to the recipient chunks too.
     *
     * <p>Byte sequence C7 E1 D3 E1 C7 E3 = "السلام" under windows-1256; the display name is RFC 2047
     * base64, so the correct decode yields the UTF-8 base64 "2KfZhNiz2YTYp9mF".
     */
    @Test
    void arabicAnsiRecipientNameDecodedWithWindows1256NotHebrew() throws Exception {
        var arabicBytes = new byte[] {(byte) 0xC7, (byte) 0xE1, (byte) 0xD3, (byte) 0xE1, (byte) 0xC7, (byte) 0xE3};
        var bytes = MsgFixtureBuilder.topLevel()
                .subject("Arabic recipient codepage test")
                .sender("Alice", "alice@example.com")
                .recipientToAnsi(arabicBytes, "bob@example.com")
                .internetCpid(1256)
                .messageCodepage(1256)
                // ANSI body so POI's has7BitEncodingStrings() triggers the codepage path.
                .textBodyAnsi(arabicBytes)
                .toBytes();

        var eml = convertString(bytes).replace("=\r\n", "");

        // Correct (windows-1256): "السلام" UTF-8 -> RFC 2047 base64 "2KfZhNiz2YTYp9mF".
        assertTrue(
                eml.contains("2KfZhNiz2YTYp9mF"),
                "Recipient display name must be decoded with windows-1256 (not Hebrew Cp1255): " + eml);
        // Buggy (Cp1255): the same bytes decode to Hebrew -> RFC 2047 base64 "1rfXkdeD15HWt9eT".
        assertFalse(eml.contains("1rfXkdeD15HWt9eT"), "Recipient name must NOT contain Cp1255 Hebrew mojibake: " + eml);
    }

    @Test
    void genuineRtfBodyPreservesWindows1252UndefinedBytes() throws Exception {
        // Audit M2: a genuine RTF body is preserved as a byte-faithful body.rtf attachment. The previous
        // code re-encoded POI's decoded RTF String through windows-1252, which maps that code page's five
        // undefined octets (0x81/0x8D/0x8F/0x90/0x9D) to '?' (0x3F) and corrupted the attachment. The fix
        // decompresses the raw PR_RTF_COMPRESSED chunk straight to bytes, so the original octet survives.
        var prefix = "{\\rtf1\\ansi\\ansicpg1252 Hello".getBytes(StandardCharsets.US_ASCII);
        var suffix = "World}".getBytes(StandardCharsets.US_ASCII);
        var rtf = new byte[prefix.length + 1 + suffix.length];
        System.arraycopy(prefix, 0, rtf, 0, prefix.length);
        rtf[prefix.length] = (byte) 0x81; // undefined in windows-1252
        System.arraycopy(suffix, 0, rtf, prefix.length + 1, suffix.length);

        var bytes = MsgFixtureBuilder.topLevel().subject("S").rtfBodyRaw(rtf).toBytes();
        var bodyRtf = decodeAttachmentBytes(convertString(bytes), "body.rtf");

        assertTrue(containsByte(bodyRtf, (byte) 0x81), "body.rtf lost the windows-1252-undefined byte 0x81");
        assertFalse(containsByte(bodyRtf, (byte) 0x3F), "0x81 was corrupted to '?' (0x3F)");
    }

    // C1 regression (MSG side): nonSentinelDate must suppress the FILETIME-0 sentinel
    // (-11_644_473_600_000 ms, i.e. 1601-01-01T00:00:00Z), pass through real dates, and return
    // null for a null input.

    @Test
    void nonSentinelDateReturnsSentinelDateAsNull() {
        // -11_644_473_600_000 ms is the Java epoch representation of 1601-01-01T00:00:00Z.
        var sentinel = new Date(-11_644_473_600_000L);
        assertNull(MsgToEmlConverter.nonSentinelDate(sentinel), "FILETIME-0 sentinel Date must map to null");
    }

    @Test
    void nonSentinelDatePassesThroughRealDate() {
        var real = new Date(1_592_215_200_000L); // 2020-06-15T10:00:00Z
        assertEquals(
                real, MsgToEmlConverter.nonSentinelDate(real), "A real origination Date must be returned unchanged");
    }

    @Test
    void nonSentinelDateReturnsNullForNullDate() {
        assertNull(MsgToEmlConverter.nonSentinelDate(null), "null Date input must yield null");
    }

    private String convertString(byte[] input) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        MsgToEmlConverter.convert(new ByteArrayInputStream(input), out, ConversionLog.NOOP);
        return out.toString(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** Reverses RFC 5322 header folding so long header values can be matched as one logical line. */
    private static String unfold(String eml) {
        return eml.replace("\r\n ", " ").replace("\r\n\t", " ");
    }

    /** Decodes the base64-encoded {@code invite.ics} attachment part out of the EML. */
    private static String decodedInvite(String eml) {
        var marker = "filename=\"invite.ics\"";
        var markerIndex = eml.indexOf(marker);
        assertTrue(markerIndex >= 0, "no invite.ics attachment:\r\n" + eml);
        var payloadStart = eml.indexOf("\r\n\r\n", markerIndex) + 4;
        var payloadEnd = eml.indexOf("\r\n--", payloadStart);
        var base64 = eml.substring(payloadStart, payloadEnd < 0 ? eml.length() : payloadEnd)
                .replace("\r\n", "");
        return new String(java.util.Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Decodes the base64-encoded attachment part with the given filename out of the EML, as raw bytes. */
    private static byte[] decodeAttachmentBytes(String eml, String filename) {
        var marker = "filename=\"" + filename + "\"";
        var markerIndex = eml.indexOf(marker);
        assertTrue(markerIndex >= 0, "no " + filename + " attachment:\r\n" + eml);
        var payloadStart = eml.indexOf("\r\n\r\n", markerIndex) + 4;
        var payloadEnd = eml.indexOf("\r\n--", payloadStart);
        var base64 = eml.substring(payloadStart, payloadEnd < 0 ? eml.length() : payloadEnd)
                .replace("\r\n", "");
        return java.util.Base64.getMimeDecoder().decode(base64);
    }

    private static boolean containsByte(byte[] data, byte value) {
        for (var element : data) {
            if (element == value) {
                return true;
            }
        }
        return false;
    }
}
