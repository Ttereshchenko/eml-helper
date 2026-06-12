package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EmlSerializerTest {

    @Test
    void testFilenameParameterNeutralizesControlCharacters() {
        // CR/LF in an attacker-controlled attachment filename must not split the header line —
        // before the fix the quoted ASCII form emitted them raw, allowing EML header injection.
        var parameter = EmlSerializer.filenameParameter("filename", "evil.txt\r\nX-Injected: yes");
        assertEquals("filename=\"evil.txt__X-Injected: yes\"", parameter);
    }

    @Test
    void testMessageIdAndInlineAttachments() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.setMessageId("<test-message-id@mailkit.org>");
        serializer.addBody("Plain text", "text/plain; charset=UTF-8");

        byte[] attachData = "test data".getBytes(StandardCharsets.UTF_8);
        serializer.addAttachment("image.png", "image/png", attachData, "inline-img-123", true);

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        assertTrue(eml.contains("Message-ID: <test-message-id@mailkit.org>"), "Should contain Message-ID");
        assertTrue(
                eml.contains("Content-Disposition: inline; filename=\"image.png\""), "Should format inline attachment");
        assertTrue(eml.contains("Content-ID: <inline-img-123>"), "Should contain Content-ID");
        assertTrue(
                eml.contains("multipart/related"),
                "Inline (Content-ID) parts should be grouped with the body in multipart/related (RFC 2387)");
    }

    @Test
    void testInlineAndRegularAttachmentNesting() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addBody("<img src=\"cid:logo\">", "text/html; charset=UTF-8");
        serializer.addAttachment("logo.png", "image/png", "img".getBytes(StandardCharsets.UTF_8), "logo", true);
        serializer.addAttachment("report.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8), null, false);

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        // Regular attachment -> mixed at the top; inline image + body -> related underneath.
        assertTrue(eml.contains("multipart/mixed"), "Regular attachment should produce a mixed root");
        assertTrue(eml.contains("multipart/related"), "Inline image should be wrapped in related");
        int relatedPos = eml.indexOf("multipart/related");
        int pdfPos = eml.indexOf("report.pdf");
        assertTrue(relatedPos > 0 && pdfPos > relatedPos, "Inline-related block should precede the mixed attachment");
    }

    @Test
    void testBodySortingAndAlternativeBoundary() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addBody("RTF content", "text/rtf; charset=UTF-8");
        serializer.addBody("HTML content", "text/html; charset=UTF-8");
        serializer.addBody("Plain content", "text/plain; charset=UTF-8");

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        assertFalse(eml.contains("multipart/mixed"), "Should not use mixed boundary if no attachments");
        assertTrue(eml.contains("multipart/alternative"), "Should use alternative boundary for multiple bodies");

        int plainPos = eml.indexOf("Content-Type: text/plain");
        int htmlPos = eml.indexOf("Content-Type: text/html");
        int rtfPos = eml.indexOf("Content-Type: text/rtf");

        assertTrue(plainPos < rtfPos, "Plain should come before RTF");
        assertTrue(rtfPos < htmlPos, "RTF should come before HTML");
    }

    @Test
    void testQuotedPrintableEncoding() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 2100; i++) {
            longBody.append("A");
        }
        serializer.addBody(longBody.toString(), "text/html; charset=UTF-8");

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        String[] lines = eml.split("\r\n");
        for (String line : lines) {
            assertTrue(line.length() <= 998, "Output line exceeds 998 octets: length " + line.length());
        }
    }

    @Test
    void testG1SanitizeControlCharacters() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.setSubject("Bad \r\nInjected: Header");

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        long count = java.util.Arrays.stream(eml.split("\r\n"))
                .filter(line -> line.startsWith("Subject:"))
                .count();
        org.junit.jupiter.api.Assertions.assertEquals(1, count, "Should have exactly one Subject line");
        assertFalse(eml.contains("\r\nInjected:"), "Control chars should be encoded and not injected");
    }

    @Test
    void testJ1EmptyRecipient() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addRecipient(EmlSerializer.RECIPIENT_TYPE_TO, "Alice", "alice@test.com");
        serializer.addRecipient(EmlSerializer.RECIPIENT_TYPE_TO, "", ""); // Empty row
        serializer.addRecipient(EmlSerializer.RECIPIENT_TYPE_TO, "Bob", "bob@test.com");

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        org.junit.jupiter.api.Assertions.assertTrue(
                eml.contains("To: \"Alice\" <alice@test.com>, \"Bob\" <bob@test.com>"));
        org.junit.jupiter.api.Assertions.assertFalse(eml.contains(", ,"));
    }

    @Test
    void testG2LongHeaderFolding() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        for (int i = 0; i < 50; i++) {
            serializer.addRecipient(0, "User " + i, "user" + i + "@example.com");
        }
        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        String[] lines = eml.split("\r\n");
        for (String line : lines) {
            assertTrue(line.length() <= 998, "Output line exceeds 998 octets: length " + line.length());
        }
    }

    @Test
    void testG3NameOnlyAddress() {
        String result = EmlSerializer.formatAddress("Bob Smith", null);
        assertTrue(result.contains("undisclosed@invalid"), "Name-only address should use placeholder email");
        assertTrue(result.contains("Bob Smith"), "Name should still be present");
    }

    @Test
    void nonAsciiFilenameUsesRfc2231NotQuotedEncodedWord() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        serializer.addAttachment("rapport_é.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8), null, false);

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        // RFC 2231 extended notation, not an RFC 2047 encoded-word wrapped in quotes (forbidden by §5).
        assertTrue(eml.contains("filename*0*=UTF-8''rapport_%C3%A9.pdf"), eml);
        assertTrue(eml.contains("name*0*=UTF-8''rapport_%C3%A9.pdf"), eml);
        assertFalse(eml.contains("filename=\"=?UTF-8?"), "Encoded-words must not appear inside a quoted filename");
    }

    @Test
    void asciiFilenameStaysQuoted() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        serializer.addAttachment("report.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8), null, false);

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        assertTrue(eml.contains("filename=\"report.pdf\""), eml);
    }

    @Test
    void longNonAsciiFilenameIsChunkedViaRfc2231() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        // A very long filename that must be chunked
        String longFilename = "오랫동안_기다려온_매우_긴_파일_이름_테스트_문서_매우_긴_파일_이름_테스트_문서.pdf";
        serializer.addAttachment(longFilename, "application/pdf", "x".getBytes(StandardCharsets.UTF_8), null, false);

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        // Check that it's broken into at least *0* and *1*
        assertTrue(eml.contains("filename*0*=UTF-8''"), "Should have chunk 0: " + eml);
        assertTrue(eml.contains("filename*1*="), "Should have chunk 1: " + eml);
        assertTrue(eml.contains(";\r\n "), "Chunks should be separated by folded CRLF: " + eml);

        // Ensure no line exceeds 78 chars (soft limit) or 998 chars (hard limit).
        // Actually, we check the hard limit to be safe.
        String[] lines = eml.split("\r\n");
        for (String line : lines) {
            assertTrue(line.length() <= 998, "Output line exceeds 998 octets: length " + line.length());
            // Since we chunk by 8 chars, the max length should actually be well under 100.
            assertTrue(line.length() <= 150, "Output line should be chunked nicely: length " + line.length());
        }
    }

    @Test
    void transportBlockBackfillsMissingEssentialHeaders() throws Exception {
        // A stored transport block that lacks From/To/Date. Before the fix the serializer took the
        // transport branch and dropped every resolved header, so From/To/Date never appeared (#11).
        EmlSerializer serializer = new EmlSerializer();
        serializer.setTransportHeaders("Received: from mx.example.com\r\nSubject: Stored subject\r\n");
        serializer.setSender("Alice", "alice@example.com");
        serializer.addRecipient(EmlSerializer.RECIPIENT_TYPE_TO, "Bob", "bob@example.com");
        serializer.addBody("hi", "text/plain; charset=UTF-8");

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        // Headers the block already carried are kept (and not duplicated)...
        assertTrue(eml.contains("Subject: Stored subject"), eml);
        assertTrue(eml.contains("Received: from mx.example.com"), eml);
        // ...and the missing ones are backfilled from the resolved values.
        assertTrue(eml.contains("From: \"Alice\" <alice@example.com>"), eml);
        assertTrue(eml.contains("To: \"Bob\" <bob@example.com>"), eml);
        assertTrue(eml.contains("\r\nDate: "), eml);

        String synthLine = java.util.Arrays.stream(eml.split("\r\n"))
                .filter(line -> line.startsWith("X-MailKit-Synthesized-Headers:"))
                .findFirst()
                .orElse("");
        assertTrue(synthLine.contains("From"), synthLine);
        assertTrue(synthLine.contains("To"), synthLine);
        assertTrue(synthLine.contains("Date"), synthLine);
        assertFalse(synthLine.contains("Subject"), "Subject came from the transport block, not synthesized");
    }

    @Test
    void emitsDateHeaderEvenWhenMessageHasNoDate() throws Exception {
        // No setDate(...) call: the source carries neither a delivery time nor a submit time.
        // Before the fix writeTo only emitted Date when date != null, violating RFC 5322 §3.6 (#12).
        EmlSerializer serializer = new EmlSerializer();
        serializer.setSubject("No date message");
        serializer.addBody("hi", "text/plain; charset=UTF-8");

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        String dateLine = java.util.Arrays.stream(eml.split("\r\n"))
                .filter(line -> line.startsWith("Date: "))
                .findFirst()
                .orElse(null);
        assertNotNull(dateLine, "A Date header must be present even when the source has none: " + eml);
        assertTrue(
                dateLine.matches("Date: \\w{3}, \\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4}"),
                "Date must be RFC 2822 formatted: " + dateLine);
    }

    @Test
    void boundaryDelimiterUsesExactlyOneCrlfNoSpuriousBlankLine() throws Exception {
        // The boundary delimiter must reuse a part's trailing CRLF, not double it into a blank line.
        EmlSerializer serializer = new EmlSerializer();
        serializer.addBody("Hello", "text/plain; charset=UTF-8");
        serializer.addAttachment(
                "a.bin", "application/octet-stream", "x".getBytes(StandardCharsets.UTF_8), null, false);

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        assertTrue(eml.contains("multipart/mixed"), eml);
        // Exactly one CRLF separates the body text from the following boundary...
        assertTrue(eml.contains("\r\nHello\r\n--MAILKIT_"), eml);
        // ...and never two, which a naive "always prepend a CRLF" boundary fix would produce.
        assertFalse(eml.contains("Hello\r\n\r\n--MAILKIT_"), "Boundary must not be preceded by a spurious blank line");
    }

    // --- H1: header-injection safety (CRLF in attacker-controlled PST string properties) ---

    private static long linesStartingWith(String eml, String prefix) {
        return java.util.Arrays.stream(eml.split("\r\n"))
                .filter(line -> line.startsWith(prefix))
                .count();
    }

    @Test
    void crlfInRecipientAddressDoesNotInjectHeader() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addRecipient(EmlSerializer.RECIPIENT_TYPE_TO, "Mallory", "a@b.com\r\nBcc: victim@evil.test");
        serializer.addBody("hi", "text/plain; charset=UTF-8");

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        assertEquals(0, linesStartingWith(eml, "Bcc:"), "A CRLF-laden address must not inject a Bcc header: " + eml);
        assertFalse(eml.contains("\r\nBcc: victim@evil.test"), eml);
        assertEquals(1, linesStartingWith(eml, "To:"), "Exactly one To header: " + eml);
    }

    @Test
    void angleBracketInRecipientAddressCannotBreakOutOfWrapper() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addRecipient(EmlSerializer.RECIPIENT_TYPE_TO, "Mallory", "a@b.com> <injected@evil.test");
        serializer.addBody("hi", "text/plain; charset=UTF-8");

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        // The stray angle brackets are stripped, so the address stays inside a single <...> wrapper.
        assertFalse(eml.contains("a@b.com> <injected@evil.test"), eml);
    }

    @Test
    void crlfInMessageIdDoesNotInjectHeader() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.setMessageId("<id@x>\r\nBcc: victim@evil.test");
        serializer.addBody("hi", "text/plain; charset=UTF-8");

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        assertEquals(0, linesStartingWith(eml, "Bcc:"), "Message-ID CRLF must not inject a Bcc header: " + eml);
        assertEquals(1, linesStartingWith(eml, "Message-ID:"), "Exactly one Message-ID header: " + eml);
    }

    @Test
    void crlfInAttachmentMimeTagDoesNotInjectHeader() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addBody("hi", "text/plain; charset=UTF-8");
        serializer.addAttachment(
                "a.bin",
                "application/octet-stream\r\nX-Injected: yes",
                "x".getBytes(StandardCharsets.UTF_8),
                null,
                false);

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        assertEquals(0, linesStartingWith(eml, "X-Injected:"), "MIME tag CRLF must not inject a header: " + eml);
        assertFalse(eml.contains("\r\nX-Injected: yes"), eml);
    }

    @Test
    void crlfInContentIdDoesNotInjectHeader() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addBody("hi", "text/plain; charset=UTF-8");
        serializer.addAttachment(
                "a.png", "image/png", "x".getBytes(StandardCharsets.UTF_8), "cid\r\nX-Injected: yes", true);

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        assertEquals(0, linesStartingWith(eml, "X-Injected:"), "Content-ID CRLF must not inject a header: " + eml);
        assertEquals(1, linesStartingWith(eml, "Content-ID:"), "Exactly one Content-ID header: " + eml);
    }

    // F5 regression: the SCL header was nested inside the Message-ID synthesis branch, so it
    // silently vanished when the message had no Message-ID or the transport block supplied one.
    @Test
    void sclHeaderDoesNotDependOnMessageIdSynthesis() throws Exception {
        var serializer = new EmlSerializer();
        serializer.setScl(5);
        serializer.addBody("body", "text/plain; charset=UTF-8");
        var writer = new StringWriter();
        serializer.writeTo(writer);
        assertTrue(
                writer.toString().contains("X-MS-Exchange-Organization-SCL: 5"),
                "SCL must be emitted without a Message-ID: " + writer);

        serializer = new EmlSerializer();
        serializer.setScl(3);
        serializer.setTransportHeaders("Message-ID: <orig@example.com>\r\nFrom: <a@example.com>\r\n");
        serializer.addBody("body", "text/plain; charset=UTF-8");
        writer = new StringWriter();
        serializer.writeTo(writer);
        assertTrue(
                writer.toString().contains("X-MS-Exchange-Organization-SCL: 3"),
                "a transport-supplied Message-ID must not suppress the SCL: " + writer);
    }

    // F11 regression: a lone CR (classic Mac line ending) was silently dropped, joining its two
    // lines into one; all three line-break flavours must encode as a hard break.
    @Test
    void quotedPrintableTreatsLoneCarriageReturnAsLineBreak() {
        assertEquals("line1\r\nline2\r\n", EmlSerializer.quotedPrintableEncode("line1\rline2"));
        assertEquals("line1\r\nline2\r\n", EmlSerializer.quotedPrintableEncode("line1\r\nline2"));
        assertEquals("line1\r\nline2\r\n", EmlSerializer.quotedPrintableEncode("line1\nline2"));
    }

    // F19: a stored Message-ID without angle brackets gains them (RFC 5322 §3.6.4).
    @Test
    void messageIdGainsAngleBracketsWhenMissing() throws Exception {
        var serializer = new EmlSerializer();
        serializer.setMessageId("bare-id@mailkit.org");
        serializer.addBody("body", "text/plain; charset=UTF-8");
        var writer = new StringWriter();
        serializer.writeTo(writer);
        assertTrue(writer.toString().contains("Message-ID: <bare-id@mailkit.org>"), writer.toString());
    }

    // F9: an on-behalf-of message carries both From: (author) and Sender: (actual transmitter).
    @Test
    void transmitterEmitsSenderHeader() throws Exception {
        var serializer = new EmlSerializer();
        serializer.setSender("Author", "author@example.com");
        serializer.setTransmitter("Assistant", "assistant@example.com");
        serializer.addBody("body", "text/plain; charset=UTF-8");
        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();
        assertTrue(eml.contains("From: \"Author\" <author@example.com>"), eml);
        assertTrue(eml.contains("Sender: \"Assistant\" <assistant@example.com>"), eml);
        assertTrue(eml.contains("X-MailKit-Synthesized-Headers: From, Sender"), eml);
    }

    // F19: custom headers keep their insertion order, so repeated conversions emit identical files.
    @Test
    void customHeadersKeepInsertionOrder() throws Exception {
        var serializer = new EmlSerializer();
        serializer.addCustomHeader("In-Reply-To", "<a@example.com>");
        serializer.addCustomHeader("References", "<a@example.com> <b@example.com>");
        serializer.addCustomHeader("Importance", "High");
        serializer.addBody("body", "text/plain; charset=UTF-8");
        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();
        var inReplyTo = eml.indexOf("In-Reply-To:");
        var references = eml.indexOf("References:");
        var importance = eml.indexOf("Importance:");
        assertTrue(inReplyTo >= 0 && inReplyTo < references && references < importance, eml);
    }

    // F10: encapsulated non-SMTP addresses end in the reserved ".invalid" TLD so synthesized
    // addresses are recognizable as such and can never route.
    @Test
    void imceaEncapsulationUsesInvalidTld() {
        assertEquals(
                "IMCEAEX-_O_x003D_ORG_CN_x003D_user@invalid", EmlSerializer.imceaEncapsulate("EX", "/O=ORG/CN=user"));
        assertEquals("real@example.com", EmlSerializer.imceaEncapsulate("SMTP", "real@example.com"));
    }

    // R3: an X.500 DN containing "@" inside a CN segment used to bypass encapsulation (the check was
    // a bare contains("@")) and leak raw — spaces, slashes and all — into the From/To angle brackets.
    @Test
    void imceaEncapsulatesExchangeDnContainingAtSign() {
        var encapsulated = EmlSerializer.imceaEncapsulate("EX", "/O=ORG/OU=AD GROUP/CN=RECIPIENTS/CN=USER@HOST");
        assertTrue(encapsulated.startsWith("IMCEAEX-"), encapsulated);
        assertTrue(encapsulated.endsWith("@invalid"), encapsulated);
        assertFalse(encapsulated.contains("/"), encapsulated);
        assertFalse(encapsulated.contains(" "), encapsulated);

        // A DN with no recorded address type is still recognizably an Exchange address.
        var typeless = EmlSerializer.imceaEncapsulate(null, "/O=ORG/CN=USER@HOST");
        assertTrue(typeless.startsWith("IMCEAEX-"), typeless);

        // Values that genuinely parse as addr-specs keep passing through untouched.
        assertEquals("user@host.example", EmlSerializer.imceaEncapsulate("EX", "user@host.example"));
        assertEquals("opaque", EmlSerializer.imceaEncapsulate(null, "opaque"));
    }

    @Test
    void looksLikeSmtpAddressRejectsUnparseableValues() {
        assertTrue(EmlSerializer.looksLikeSmtpAddress("user@host.example"));
        assertFalse(EmlSerializer.looksLikeSmtpAddress("/O=ORG/CN=USER@HOST"));
        assertFalse(EmlSerializer.looksLikeSmtpAddress("two words@host"));
        assertFalse(EmlSerializer.looksLikeSmtpAddress("user@@host"));
        assertFalse(EmlSerializer.looksLikeSmtpAddress("@host"));
        assertFalse(EmlSerializer.looksLikeSmtpAddress("user@"));
        assertFalse(EmlSerializer.looksLikeSmtpAddress("no-at-sign"));
    }

    // R5: a stored multipart/* MIME tag on an opaque base64 attachment used to emit a structurally
    // invalid part — base64 is forbidden on composite types and the multipart had no boundary.
    @Test
    void compositeMimeTagOnOpaqueAttachmentDowngradesToOctetStream() throws Exception {
        var serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        serializer.addAttachment(
                "smime.p7m",
                "multipart/signed; protocol=\"application/x-pkcs7-signature\"",
                new byte[] {1, 2, 3},
                null,
                false);

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertTrue(eml.contains("Content-Type: application/octet-stream; name=\"smime.p7m\""), eml);
        assertFalse(eml.contains("multipart/signed"), eml);
    }

    // R5 guard: real embedded messages take the nestedEml path and must keep their composite type.
    @Test
    void embeddedMessageKeepsMessageRfc822Type() throws Exception {
        var serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        serializer.addEmbeddedMessage("inner.eml", "Subject: inner\r\n\r\ninner body\r\n");

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertTrue(eml.contains("Content-Type: message/rfc822; name=\"inner.eml\""), eml);
    }

    // R10: RFC 2387 §3.1 makes the root part's media type a required multipart/related parameter.
    @Test
    void relatedPartCarriesRequiredTypeParameter() throws Exception {
        var single = new EmlSerializer();
        single.addBody("<p>hi</p>", "text/html; charset=UTF-8");
        single.addAttachment("img.png", "image/png", new byte[] {1}, "cid-1", true);
        var singleWriter = new StringWriter();
        single.writeTo(singleWriter);
        assertTrue(singleWriter.toString().contains("; type=\"text/html\""), singleWriter::toString);

        var alternative = new EmlSerializer();
        alternative.addBody("plain", "text/plain; charset=UTF-8");
        alternative.addBody("<p>hi</p>", "text/html; charset=UTF-8");
        alternative.addAttachment("img.png", "image/png", new byte[] {1}, "cid-1", true);
        var alternativeWriter = new StringWriter();
        alternative.writeTo(alternativeWriter);
        assertTrue(
                alternativeWriter.toString().contains("; type=\"multipart/alternative\""), alternativeWriter::toString);
    }

    /**
     * J1 regression: an empty-valued custom header (the X-MS-Journal-Report marker on Exchange
     * journal reports has no value) used to be silently dropped by the blank-value guard in
     * appendHeader; it must be emitted as a bare field instead.
     */
    @Test
    void emptyValuedCustomHeaderIsEmittedBare() throws Exception {
        var serializer = new EmlSerializer();
        serializer.addCustomHeader("X-MS-Journal-Report", "");
        serializer.addBody("journal record", "text/plain; charset=UTF-8");
        var writer = new StringWriter();
        serializer.writeTo(writer);
        assertTrue(
                writer.toString().contains("X-MS-Journal-Report:\r\n"),
                () -> "Expected the bare journal marker header in:\n" + writer);
    }

    /**
     * G4 regression: an original transport-header line longer than the RFC 5322 §2.1.1 hard limit
     * of 998 characters used to be passed through verbatim; it must be re-folded at whitespace so
     * the generated message stays parseable.
     */
    @Test
    void overlongTransportHeaderLineIsRefolded() throws Exception {
        var receivedTokens = new StringBuilder("Received: from relay.example.com");
        while (receivedTokens.length() < 1400) {
            receivedTokens.append(" by hop").append(receivedTokens.length()).append(".example.com");
        }
        var serializer = new EmlSerializer();
        serializer.setTransportHeaders(receivedTokens + "\r\nSubject: kept\r\n");
        serializer.addBody("body", "text/plain; charset=UTF-8");
        var writer = new StringWriter();
        serializer.writeTo(writer);

        var output = writer.toString();
        assertTrue(output.contains("Received: from relay.example.com"), "The header itself must survive");
        for (var line : output.split("\r\n")) {
            assertTrue(line.length() <= 998, () -> "Line exceeds the RFC 5322 hard limit: " + line.length());
        }
        // The folded continuations must start with whitespace, i.e. remain one logical header.
        var folded = output.split("\r\n");
        for (int lineIndex = 1; lineIndex < folded.length; lineIndex++) {
            if (folded[lineIndex - 1].startsWith("Received:") && !folded[lineIndex].isEmpty()) {
                assertTrue(
                        Character.isWhitespace(folded[lineIndex].charAt(0)),
                        "The continuation of the folded Received line must start with WSP");
                break;
            }
        }
    }
}
