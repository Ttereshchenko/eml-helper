package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
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
    void attachmentContentTypePreservesStoredCharsetParameter() throws Exception {
        // A text attachment's stored MIME tag carries the charset that is the only decode hint for its
        // base64 bytes; the serializer must keep it while appending its own name= parameter. Before the
        // fix the MSG driver truncated the tag at the first ';', dropping the charset.
        var serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        serializer.addAttachment(
                "notes.txt", "text/plain; charset=koi8-r", "x".getBytes(StandardCharsets.UTF_8), null, false);

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertTrue(
                eml.contains("Content-Type: text/plain; charset=koi8-r; name=\"notes.txt\""),
                "stored charset must survive alongside the appended name: " + eml);
    }

    @Test
    void attachmentContentTypeDropsDuplicateStoredNameParameter() throws Exception {
        // Outlook sometimes stores a name= parameter on PR_ATTACH_MIME_TAG; the serializer appends its
        // own, so the stored one must be dropped to avoid a duplicate parameter (rfc2045 §5.1). Before
        // the fix the PST driver passed the tag through verbatim and emitted two name= parameters.
        var serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        serializer.addAttachment(
                "real.png", "image/png; name=\"stored.png\"", "x".getBytes(StandardCharsets.UTF_8), null, false);

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertTrue(
                eml.contains("Content-Type: image/png; name=\"real.png\""),
                "the serializer's own name= must be the only one: " + eml);
        assertFalse(eml.contains("stored.png"), "the stored duplicate name= must be dropped: " + eml);
    }

    @Test
    void testTransportHeaderLoneCarriageReturnIsNeutralized() throws Exception {
        // A bare CR (no LF) inside a passed-through transport header must not act as a line break a
        // lenient parser reads as an injected header (RFC 5322 §2.2). Before the fix it was emitted
        // raw; now it folds into a single field, matching the synthesized-header path.
        var serializer = new EmlSerializer();
        serializer.setTransportHeaders("X-Trace: ok\r\nX-Evil: a\rBcc: victim@evil.test\r\n");
        serializer.addBody("body", "text/plain; charset=UTF-8");

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertFalse(eml.contains("a\rBcc"), "bare CR must not survive as a line break: " + eml);
        assertTrue(eml.contains("X-Evil: a Bcc: victim@evil.test"), "lone CR folds into one field: " + eml);
    }

    @Test
    void testCustomHeaderDoesNotDuplicateSynthesizedSubject() throws Exception {
        // A synthesized essential (Subject) must not be re-emitted from a custom header — RFC 5322
        // §3.6 allows Subject at most once. Before the fix the dedup guard checked only the transport
        // block, so a custom "Subject" slipped through as a second copy.
        var serializer = new EmlSerializer();
        serializer.setSubject("Real Subject");
        serializer.addCustomHeader("Subject", "Injected Subject");
        serializer.addBody("body", "text/plain; charset=UTF-8");

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertEquals(1, eml.split("Subject: ", -1).length - 1, "exactly one Subject header: " + eml);
        assertTrue(eml.contains("Subject: Real Subject"), "keeps the synthesized Subject: " + eml);
        assertFalse(eml.contains("Injected Subject"), "drops the duplicate custom Subject: " + eml);
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
        // No HTML body references the Content-ID, so the part must NOT be hidden as an inline
        // member of multipart/related — it is demoted to a visible regular attachment that keeps
        // its Content-ID (with the msg-id domain sanitizeContentId supplies).
        assertTrue(
                eml.contains("Content-Disposition: attachment; filename=\"image.png\""),
                "An unreferenced Content-ID part should stay a visible attachment: " + eml);
        assertTrue(eml.contains("Content-ID: <inline-img-123@mailkit.invalid>"), "Should keep Content-ID: " + eml);
        assertFalse(
                eml.contains("multipart/related"),
                "Without a cid: reference there is nothing to relate the part to (RFC 2387): " + eml);
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
    void testInlineCidReferenceRewrittenCaseInsensitively() throws Exception {
        // The HTML body references the image with an upper-case cid: URL (cid: scheme + addr-spec are
        // case-insensitive per RFC 2392 §2). routing is already case-insensitive, but before the fix the
        // body rewrite that retargets the sanitized Content-ID was case-sensitive, so a "CID:LOGO" stayed
        // pointing at the un-suffixed id and the inline image no longer resolved.
        var serializer = new EmlSerializer();
        serializer.addBody("<img src=\"CID:LOGO\">", "text/html; charset=UTF-8");
        serializer.addAttachment("logo.png", "image/png", "img".getBytes(StandardCharsets.UTF_8), "logo", true);

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertTrue(eml.contains("multipart/related"), "Referenced inline image should be wrapped in related: " + eml);
        assertTrue(eml.contains("Content-ID: <logo@mailkit.invalid>"), "Sanitized Content-ID header expected: " + eml);
        assertTrue(
                eml.contains("cid:logo@mailkit.invalid"),
                "Body cid: must be retargeted to the sanitized id regardless of case: " + eml);
        assertFalse(eml.contains("CID:LOGO"), "Stale upper-case cid: reference must be rewritten: " + eml);
    }

    @Test
    void testCidReferenceRequiresWholeTokenNotPrefix() throws Exception {
        // The HTML cites only cid:image10. "cid:image1" is a substring of "cid:image10", but a cid: URL
        // maps to one specific Content-ID (RFC 2392 §2), so the image1 part is NOT referenced and must
        // stay a visible attachment. Before the fix the substring match pulled image1 into
        // multipart/related as an inline member, where common clients neither render nor list it.
        var serializer = new EmlSerializer();
        serializer.addBody("<img src=\"cid:image10\">", "text/html; charset=UTF-8");
        serializer.addAttachment("pic1.png", "image/png", "a".getBytes(StandardCharsets.UTF_8), "image1", true);
        serializer.addAttachment("pic10.png", "image/png", "b".getBytes(StandardCharsets.UTF_8), "image10", true);

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertTrue(eml.contains("multipart/related"), "the genuinely referenced image10 must be related: " + eml);
        assertTrue(
                eml.contains("Content-Disposition: attachment; filename=\"pic1.png\""),
                "image1 is only a prefix of cid:image10, so it must stay a visible attachment: " + eml);
        assertFalse(
                eml.contains("Content-Disposition: attachment; filename=\"pic10.png\""),
                "image10 is referenced, so it stays inline rather than a visible attachment: " + eml);
    }

    @Test
    void testLiteralEncodedWordSubjectIsReEncoded() throws Exception {
        // A pure-ASCII Subject that literally contains RFC 2047 encoded-word syntax must be re-encoded,
        // else a receiver decodes "=?ISO-8859-1?Q?p=F6=F6?=" and shows "pöö" instead of the literal
        // text the user typed. Before the fix isPureAscii short-circuited and the literal went out raw.
        var serializer = new EmlSerializer();
        serializer.setSubject("=?ISO-8859-1?Q?p=F6=F6?=");
        serializer.addBody("body", "text/plain; charset=UTF-8");

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertFalse(
                eml.contains("=?ISO-8859-1?Q?"),
                "the literal encoded-word must be re-encoded, not emitted raw: " + eml);
        assertTrue(eml.contains("Subject: =?UTF-8?B?"), "Subject must be wrapped as a UTF-8 encoded-word: " + eml);
    }

    @Test
    void testLiteralEncodedWordDisplayNameIsReEncoded() {
        // formatAddress's pure-ASCII branch emits the name as a quoted phrase; a name that literally
        // contains encoded-word syntax would then be decoded by the receiver, so it must be re-encoded.
        var formatted = EmlSerializer.formatAddress("=?utf-8?q?x?=", "user@example.com");

        assertFalse(
                formatted.contains("=?utf-8?q?x?="),
                "literal encoded-word name must not pass through quoted: " + formatted);
        assertTrue(formatted.startsWith("=?UTF-8?B?"), "name must be re-encoded as an encoded-word: " + formatted);
        assertTrue(formatted.contains("<user@example.com>"), "address must be preserved: " + formatted);
    }

    @Test
    void testFormatAddressPlainKeepsNonAsciiNameUnencoded() {
        // formatAddress RFC-2047-encodes a non-ASCII name for a header; the plain variant is for a
        // text/plain body, where an encoded-word is undefined and would render literally to the reader.
        var plain = EmlSerializer.formatAddressPlain("Müller", "muller@example.com");
        assertEquals("Müller <muller@example.com>", plain);
        assertFalse(plain.contains("=?"), "A body address must not carry an RFC 2047 encoded-word: " + plain);
        assertTrue(
                EmlSerializer.formatAddress("Müller", "muller@example.com").contains("=?"),
                "Sanity: the header variant still encodes");

        assertEquals("<only@example.com>", EmlSerializer.formatAddressPlain(null, "only@example.com"));
        assertEquals(
                "Jane Doe <jane@example.com>",
                EmlSerializer.formatAddressPlain("Jane\r\nDoe", "jane@example.com"),
                "CR/LF in the name must collapse so each member stays on its own line");
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
    void longAsciiFilenameStaysWithinTheHardLineLimit() throws Exception {
        EmlSerializer serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        // Attachment filenames are attacker-controlled and uncapped (e.g. an uncapped <subject>.eml from
        // uniqueEmbeddedName, or PR_ATTACH_LONG_FILENAME). The pure-ASCII quoted form is emitted unfolded,
        // so before the fix a name this long produced a single Content-Disposition line well past the
        // rfc5322 §2.1.1 998-octet hard limit. The fix routes an over-long ASCII name through the same
        // self-wrapping RFC 2231 continuation path the non-ASCII branch already uses.
        String longFilename = "a".repeat(1000) + ".txt";
        serializer.addAttachment(
                longFilename, "application/octet-stream", "x".getBytes(StandardCharsets.UTF_8), null, false);

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        assertTrue(eml.contains("filename*0*="), "Long ASCII filename must use RFC 2231 continuation: " + eml);
        assertFalse(
                eml.contains("filename=\"" + longFilename + "\""),
                "Long ASCII filename must not be emitted as a single unfolded quoted parameter");
        for (String line : eml.split("\r\n")) {
            assertTrue(
                    line.length() <= 998,
                    "Output line exceeds the rfc5322 §2.1.1 998-octet hard limit: length " + line.length());
        }
    }

    @Test
    void shortAsciiFilenameStaysQuoted() throws Exception {
        // The length gate must not disturb the common case: a normal short ASCII name keeps the compact
        // quoted form byte-for-byte (no RFC 2231 continuation), preserving wide client compatibility.
        EmlSerializer serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        serializer.addAttachment(
                "quarterly-results-2024-final.pdf",
                "application/pdf",
                "x".getBytes(StandardCharsets.UTF_8),
                null,
                false);

        StringWriter writer = new StringWriter();
        serializer.writeTo(writer);
        String eml = writer.toString();

        assertTrue(eml.contains("filename=\"quarterly-results-2024-final.pdf\""), eml);
        assertFalse(eml.contains("filename*0*="), "A short ASCII filename must not be chunked: " + eml);
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

    // Review nit regression: re-encoding a trailing space as =20 on a full 75-char line used to
    // produce a 77-char line, one over the RFC 2045 §6.7 limit; it must soft-wrap first.
    @Test
    void quotedPrintableKeepsTrailingSpaceEncodingWithinLineLimit() {
        var fullLine = "a".repeat(74) + " ";
        var encoded = EmlSerializer.quotedPrintableEncode(fullLine + "\r\nnext");
        for (var line : encoded.split("\r\n", -1)) {
            assertTrue(line.length() <= 76, "QP line exceeds 76 chars (" + line.length() + "): " + line);
        }
        assertEquals("a".repeat(74) + "=\r\n=20\r\nnext\r\n", encoded, "soft break, then the encoded space");

        // At 74 chars the =20 still fits inline (74 - 1 + 3 = 76): no soft break wanted.
        assertEquals(
                "a".repeat(73) + "=20\r\n",
                EmlSerializer.quotedPrintableEncode("a".repeat(73) + " "),
                "the boundary case must stay on one line");
    }

    // RFC 2047 §2: a header line that contains one or more encoded-words is limited to 76 chars
    // (each encoded-word itself to 75). The generic header folder used 78, so a long header name
    // carrying a non-ASCII value could emit a 77-79 char encoded-word line. The tightened threshold
    // applies ONLY to encoded-word lines, so plain ASCII headers stay folded at the §2.1.1 limit.
    @Test
    void encodedWordLinesRespectSeventySixCharacterLimit() throws Exception {
        var serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        // A long header name plus a three-byte-per-char value: the first encoded-word line, name
        // prefix included, reaches 84 chars before the fix (76-char limit exceeded).
        serializer.addCustomHeader("X-Custom-Long-Header-Name-Here", "日本語のテキストをここに置きます以上の文字列");
        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        for (var line : eml.split("\r\n", -1)) {
            if (line.contains("=?") && line.contains("?=")) {
                assertTrue(line.length() <= 76, "encoded-word line exceeds 76 chars (" + line.length() + "): " + line);
                // RFC 2047 §2 also caps each individual encoded-word at 75 chars.
                var start = line.indexOf("=?");
                var end = line.indexOf("?=", start) + 2;
                assertTrue(end - start <= 75, "encoded-word exceeds 75 chars: " + line.substring(start, end));
            }
        }
        // The header must still be present and decode back to the original value.
        assertTrue(eml.contains("X-Custom-Long-Header-Name-Here:"), eml);
    }

    // A plain ASCII header must be folded exactly as before (78-char threshold) — the encoded-word
    // tightening must not alter the common case.
    @Test
    void plainAsciiHeaderFoldingIsUnchangedByEncodedWordLimit() throws Exception {
        var serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        var asciiValue = "token-one token-two token-three token-four token-five token-six token-seven token-eight";
        serializer.addCustomHeader("X-Ascii-Header", asciiValue);
        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        var headerStart = eml.indexOf("X-Ascii-Header:");
        var firstLine = eml.substring(headerStart, eml.indexOf("\r\n", headerStart));
        // The first line is folded against the 78-char soft limit (no encoded-word present).
        assertTrue(firstLine.length() <= 78, "ASCII header first line exceeds 78: " + firstLine);
        assertFalse(firstLine.contains("=?"), "no encoded-word should appear for pure ASCII: " + firstLine);
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
        single.addBody("<p>hi <img src=\"cid:cid-1\"></p>", "text/html; charset=UTF-8");
        single.addAttachment("img.png", "image/png", new byte[] {1}, "cid-1", true);
        var singleWriter = new StringWriter();
        single.writeTo(singleWriter);
        assertTrue(singleWriter.toString().contains("; type=\"text/html\""), singleWriter::toString);

        var alternative = new EmlSerializer();
        alternative.addBody("plain", "text/plain; charset=UTF-8");
        alternative.addBody("<p>hi <img src=\"cid:cid-1\"></p>", "text/html; charset=UTF-8");
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

    /**
     * F8: a synthesized header whose value is one unbreakable token longer than the RFC 5322
     * §2.1.1 hard limit (e.g. the base64 Thread-Index of a very long conversation) must be
     * hard-folded rather than emitted as a >998-character line strict parsers reject.
     */
    @Test
    void unbreakableOverlongHeaderValueIsHardFolded() throws Exception {
        var serializer = new EmlSerializer();
        serializer.setSubject("Long thread");
        var threadIndex = "A".repeat(1500); // no whitespace anywhere
        serializer.addCustomHeader("Thread-Index", threadIndex);
        serializer.addBody("body", "text/plain; charset=UTF-8");

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        for (var line : eml.split("\r\n", -1)) {
            assertTrue(line.length() <= 998, "line exceeds the RFC 5322 hard limit: " + line.length());
        }
        // Folding necessarily introduces WSP into the token (that is what makes it legal); the
        // consumers of such headers strip whitespace, so the value must round-trip modulo WSP.
        var headerMatcher = java.util.regex.Pattern.compile("Thread-Index:((?:[^\\r\\n]|\\r\\n[ \\t])*)")
                .matcher(eml);
        assertTrue(headerMatcher.find(), "the Thread-Index header must be present");
        assertEquals(
                threadIndex,
                headerMatcher.group(1).replaceAll("[ \\t\\r\\n]", ""),
                "the folded value must round-trip once whitespace is stripped");
    }

    // N3: RFC 5322 §3.6.2 makes From mandatory — a message with no sender at all gets the explicit
    // placeholder, flagged in X-MailKit-Synthesized-Headers, instead of an unparseable message.
    @Test
    void missingFromIsSynthesizedAsPlaceholder() throws Exception {
        var serializer = new EmlSerializer();
        serializer.addBody("body", "text/plain; charset=UTF-8");
        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertTrue(eml.contains("From: <undisclosed@invalid>"), eml);
        assertTrue(eml.contains("X-MailKit-Synthesized-Headers: From, Date"), eml);
    }

    // N14: Sender without From is malformed (RFC 5322 §3.6.2 defines Sender relative to From) — a
    // transmitter-only message promotes the transmitter into From instead.
    @Test
    void transmitterWithoutSenderIsPromotedToFrom() throws Exception {
        var serializer = new EmlSerializer();
        serializer.setTransmitter("Agent", "agent@example.com");
        serializer.addBody("body", "text/plain; charset=UTF-8");
        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertTrue(eml.contains("From: \"Agent\" <agent@example.com>"), eml);
        assertFalse(eml.contains("Sender:"), eml);
    }

    // N8: rfc2046 §5.2.1 allows only 7bit/8bit/binary on message/rfc822, and 8bit still carries the
    // rfc5322 §2.1.1 998-octet line limit — a nested message with a longer line must declare binary.
    @Test
    void nestedMessageWithOverlongLineDeclaresBinaryCte() throws Exception {
        var overlong = "Subject: ok\r\n\r\nBody line: " + "Z".repeat(1100) + "\r\n";
        var withinLimit = "Subject: ok\r\n\r\nshort body\r\n";

        var binarySerializer = new EmlSerializer();
        binarySerializer.setSender("A", "a@example.com");
        binarySerializer.addBody("body", "text/plain; charset=UTF-8");
        binarySerializer.addEmbeddedMessage("inner.eml", overlong);
        var binaryWriter = new StringWriter();
        binarySerializer.writeTo(binaryWriter);
        assertTrue(binaryWriter.toString().contains("Content-Transfer-Encoding: binary"), binaryWriter::toString);

        var eightBitSerializer = new EmlSerializer();
        eightBitSerializer.setSender("A", "a@example.com");
        eightBitSerializer.addBody("body", "text/plain; charset=UTF-8");
        eightBitSerializer.addEmbeddedMessage("inner.eml", withinLimit);
        var eightBitWriter = new StringWriter();
        eightBitSerializer.writeTo(eightBitWriter);
        assertTrue(eightBitWriter.toString().contains("Content-Transfer-Encoding: 8bit"), eightBitWriter::toString);
    }

    // N14: Content-Location used to be emitted raw; an overlong stored value must fold within the
    // RFC 5322 §2.1.1 hard limit like every other header.
    @Test
    void overlongContentLocationIsFolded() throws Exception {
        var location = "https://example.com/" + "segment/".repeat(150);
        var serializer = new EmlSerializer();
        serializer.setSender("A", "a@example.com");
        serializer.addBody("body", "text/plain; charset=UTF-8");
        serializer.addAttachment("file.bin", "application/octet-stream", new byte[] {1}, null, location, false);
        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        assertTrue(eml.contains("Content-Location:"), eml);
        for (var line : eml.split("\r\n", -1)) {
            assertTrue(line.length() <= 998, "line exceeds the RFC 5322 hard limit: " + line.length());
        }
    }

    /**
     * Byte-fidelity regression: a hoisted clear-signed S/MIME entity is written verbatim, so an 8-bit
     * octet in its body (e.g. an inner {@code 8bit} part) must survive byte-for-byte. The old
     * String-based raw entity decoded the body as ISO-8859-1 and re-encoded it through the UTF-8
     * writer, doubling every octet &ge; 0x80 (0xE9 → 0xC3 0xA9) and invalidating the signature.
     */
    @Test
    void rawEntityBodyWithEightBitOctetsIsWrittenByteExactViaOutputStream() throws Exception {
        var serializer = new EmlSerializer();
        serializer.setSubject("signed");
        serializer.setSender("A", "a@example.com");
        byte[] body = {'l', 'i', 'n', 'e', '\r', '\n', (byte) 0xE9, (byte) 0x80, (byte) 0xFF, '\r', '\n'};
        serializer.setRawEntity("multipart/signed; boundary=\"sig\"", "8bit", null, body);

        var bytes = new ByteArrayOutputStream();
        serializer.writeTo(bytes);
        var eml = bytes.toByteArray();

        assertTrue(indexOfSubarray(eml, body) >= 0, "the raw 8-bit body must appear verbatim in the output");
        assertEquals(1, countByte(eml, (byte) 0xE9), "0xE9 must appear once, not be re-encoded to 0xC3 0xA9");
        assertEquals(0, countByte(eml, (byte) 0xC3), "no UTF-8 re-encoding of the high bytes may occur");
    }

    /**
     * Byte-fidelity regression for a NESTED entity: an embedded {@code message/rfc822} part is emitted
     * verbatim, so an 8-bit octet in the nested message (e.g. a nested clear-signed S/MIME entity) must
     * survive byte-for-byte through {@link EmlSerializer#writeTo(java.io.OutputStream)}. The old
     * String-based embedded body routed the bytes through the UTF-8 char writer, doubling every octet
     * &ge; 0x80 and invalidating a nested signature.
     */
    @Test
    void embeddedMessageWithEightBitOctetsIsWrittenByteExactViaOutputStream() throws Exception {
        var serializer = new EmlSerializer();
        serializer.setSubject("outer");
        serializer.setSender("A", "a@example.com");
        serializer.addBody("outer body", "text/plain; charset=UTF-8");
        byte[] nested = {
            'C', 'o', 'n', 't', 'e', 'n', 't', '-', 'T', 'y', 'p', 'e', ':', ' ',
            't', 'e', 'x', 't', '/', 'p', 'l', 'a', 'i', 'n', '\r', '\n', '\r', '\n',
            's', 'i', 'g', (byte) 0xE9, (byte) 0x80, (byte) 0xFF, '\r', '\n'
        };
        serializer.addEmbeddedMessage("inner.eml", nested);

        var bytes = new ByteArrayOutputStream();
        serializer.writeTo(bytes);
        var eml = bytes.toByteArray();

        assertTrue(indexOfSubarray(eml, nested) >= 0, "the nested message bytes must appear verbatim");
        assertEquals(1, countByte(eml, (byte) 0xE9), "0xE9 must appear once, not be re-encoded to 0xC3 0xA9");
        assertEquals(0, countByte(eml, (byte) 0xC3), "no UTF-8 re-encoding of the nested high bytes may occur");
    }

    /**
     * F1 regression: an embedded {@code message/rfc822} whose final byte is a bare LF (not CRLF)
     * must still be followed by a valid CRLF-prefixed boundary delimiter. The old code emitted the
     * nested bytes verbatim and then called {@code appendBoundary}, which relies on the
     * {@link NewlineTrackingWriter} to decide whether to prepend a CRLF — but the raw-body
     * emitter writes straight to the underlying stream and bypasses the tracking writer, so
     * {@code atLineStart()} always returned {@code false}, causing the boundary to be preceded by a
     * lone LF. The fix checks the nested bytes directly and appends CRLF when they do not already
     * end with one.
     */
    @Test
    void embeddedMessageWithBareLfTerminatorGetsCrlfBeforeBoundary() throws Exception {
        // The inner body ends with bare LF (\n), not CRLF (\r\n).
        var nested = "From: a@b.com\r\nSubject: inner\r\n\r\ninner body\n".getBytes(StandardCharsets.UTF_8);

        var serializer = new EmlSerializer();
        serializer.setSender("A", "a@example.com");
        serializer.addEmbeddedMessage("inner.eml", nested);

        var bytes = new ByteArrayOutputStream();
        serializer.writeTo(bytes);
        var eml = bytes.toByteArray();
        var emlString = new String(eml, StandardCharsets.UTF_8);

        // Extract the root boundary from the Content-Type header.
        var ctMatcher = Pattern.compile("boundary=\"([^\"]+)\"").matcher(emlString);
        assertTrue(ctMatcher.find(), "Content-Type must declare a boundary:\n" + emlString);
        var boundary = ctMatcher.group(1);
        var delimiterBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);

        // Every occurrence of the boundary delimiter must be preceded by \r\n (0x0D 0x0A).
        var pos = 0;
        var foundAtLeastOne = false;
        while (true) {
            var idx = indexOfSubarray(eml, delimiterBytes, pos);
            if (idx < 0) {
                break;
            }
            foundAtLeastOne = true;
            assertTrue(
                    idx >= 2 && eml[idx - 2] == '\r' && eml[idx - 1] == '\n',
                    "Boundary at offset " + idx + " must be preceded by CRLF (\\r\\n), not a lone"
                            + " LF or other bytes. Raw output:\n" + emlString);
            pos = idx + delimiterBytes.length;
        }
        assertTrue(foundAtLeastOne, "At least one boundary delimiter must appear in the output");

        // The bare LF inside the nested content must be preserved verbatim.
        assertTrue(
                indexOfSubarray(eml, "inner body\n".getBytes(StandardCharsets.UTF_8), 0) >= 0,
                "Bare LF inside the nested body must be preserved verbatim");
    }

    /** Overload that accepts a start position for scanning. */
    private static int indexOfSubarray(byte[] haystack, byte[] needle, int from) {
        outer:
        for (var index = from; index <= haystack.length - needle.length; index++) {
            for (var offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static int indexOfSubarray(byte[] haystack, byte[] needle) {
        outer:
        for (var index = 0; index <= haystack.length - needle.length; index++) {
            for (var offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static int countByte(byte[] data, byte value) {
        var count = 0;
        for (var current : data) {
            if (current == value) {
                count++;
            }
        }
        return count;
    }

    /**
     * Regression for the addAddressHeader double-encode bug: when an already-formatted address
     * (produced by {@link EmlSerializer#formatAddress}) is passed through
     * {@link EmlSerializer#addCustomHeader}, the value is run through
     * {@link EmlSerializer#encodeHeaderIfNeeded} a second time, which treats the entire
     * "=?UTF-8?B?…?= &lt;addr&gt;" string as header text and re-encodes the angle-addr into a new
     * base64 blob, producing an unparseable nest of encoded-words with no literal addr-spec.
     * {@link EmlSerializer#addAddressHeader} emits the value verbatim (as From/To do), so the
     * addr-spec stays bare and the Cyrillic phrase is encoded exactly once.
     */
    @Test
    void addAddressHeaderEmitsAlreadyFormattedValueVerbatimWithoutDoubleEncoding() throws Exception {
        var cyrillicFormatted = EmlSerializer.formatAddress("Иван Петров", "ivan@example.com");

        // Verify the formatAddress output has the right structure before we test the header emission.
        assertTrue(
                cyrillicFormatted.startsWith("=?UTF-8?"),
                "formatAddress must encode the Cyrillic phrase: " + cyrillicFormatted);
        assertTrue(
                cyrillicFormatted.contains("<ivan@example.com>"),
                "formatAddress must keep addr-spec bare: " + cyrillicFormatted);

        var serializer = new EmlSerializer();
        serializer.setSender("Sender", "sender@example.com");
        serializer.addBody("body", "text/plain; charset=UTF-8");
        serializer.addAddressHeader("Reply-To", cyrillicFormatted);

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        // Extract the Reply-To header field body (unfold continuation lines by joining CRLF+WSP).
        var headerMatcher = Pattern.compile("Reply-To:((?:[^\r\n]|\r\n[ \t])*)").matcher(eml);
        assertTrue(headerMatcher.find(), "Reply-To header must be present:\n" + eml);
        var replyToBody = headerMatcher.group(1).replaceAll("\r\n[ \t]", " ").trim();

        // The addr-spec must be present as a structural token — not buried inside a base64 blob.
        assertTrue(
                replyToBody.contains("<ivan@example.com>"),
                "addr-spec must appear literally in Reply-To, not re-encoded: " + replyToBody);

        // The display name must still be an encoded-word (not a quoted string of Cyrillic UTF-8 bytes).
        assertTrue(
                replyToBody.contains("=?UTF-8?"),
                "Cyrillic phrase must remain as an encoded-word in Reply-To: " + replyToBody);

        // Contrast: count encoded-word tokens. If double-encoded, the whole formatted string becomes
        // one giant encoded-word and "<ivan@example.com>" disappears from the raw header.
        var encodedWordCount = 0;
        var encodedWordMatcher =
                Pattern.compile("=\\?UTF-8\\?[BbQq]\\?[^?]*\\?=").matcher(replyToBody);
        while (encodedWordMatcher.find()) {
            encodedWordCount++;
        }
        // There should be at least one encoded-word for the phrase, but the addr-spec must NOT be
        // inside one — so the addr-spec literal must appear outside any encoded-word boundary.
        assertTrue(encodedWordCount >= 1, "Cyrillic phrase must produce at least one encoded-word: " + replyToBody);

        // Contrast assertion: pure ASCII display name is emitted as a quoted phrase, not encoded.
        var serializer2 = new EmlSerializer();
        serializer2.setSender("Sender", "sender@example.com");
        serializer2.addBody("body", "text/plain; charset=UTF-8");
        serializer2.addAddressHeader("Reply-To", EmlSerializer.formatAddress("Support Desk", "s@x.com"));
        var writer2 = new StringWriter();
        serializer2.writeTo(writer2);
        var eml2 = writer2.toString();
        assertTrue(
                eml2.contains("\"Support Desk\" <s@x.com>"),
                "ASCII display name must be emitted as a quoted phrase: " + eml2);
    }

    /**
     * Regression for the appendHeader hard-split bug: when a header value contains no foldable
     * whitespace within the first {@code MAX_HEADER_LINE_LENGTH} (998) characters, the old code
     * scanned forward for a distant whitespace, found it beyond 998, and folded only at that point
     * — emitting a first line longer than the RFC 5322 §2.1.1 hard limit. The fix hard-splits at
     * position 998 and lets normal folding handle the remainder.
     *
     * <p>This test uses a {@code Keywords} value whose first token is 1 100 characters long (well
     * over 998), so the only whitespace lies beyond position 998. Every output line must be
     * &le; 998 characters.
     */
    @Test
    void overlongHeaderTokenWithNoWhitespaceWithin998IsHardSplit() throws Exception {
        var serializer = new EmlSerializer();
        serializer.setSender("A", "a@example.com");
        serializer.addBody("body", "text/plain; charset=UTF-8");
        serializer.addCustomHeader("Keywords", "K".repeat(1100) + ", second");

        var writer = new StringWriter();
        serializer.writeTo(writer);
        var eml = writer.toString();

        for (var line : eml.split("\r\n", -1)) {
            assertTrue(
                    line.length() <= 998,
                    "line exceeds the RFC 5322 hard limit (" + line.length() + " chars): " + line);
        }

        // Verify the value round-trips modulo folding whitespace (consumers strip it).
        var headerMatcher = Pattern.compile("Keywords:((?:[^\r\n]|\r\n[ \t])*)").matcher(eml);
        assertTrue(headerMatcher.find(), "Keywords header must be present:\n" + eml);
        var roundTripped = headerMatcher.group(1).replaceAll("[ \t\r\n]", "");
        assertEquals(
                "K".repeat(1100) + ",second", roundTripped, "Keywords value must round-trip modulo folding whitespace");
    }
}
