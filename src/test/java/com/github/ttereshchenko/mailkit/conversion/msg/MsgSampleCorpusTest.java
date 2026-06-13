package com.github.ttereshchenko.mailkit.conversion.msg;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.conversion.ConversionException;
import com.github.ttereshchenko.mailkit.conversion.ConversionLog;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Conversion tests over the real-world MSG corpus in {@code src/test/resources/samples/msg}:
 * messages from different Outlook generations, modern Unicode and legacy ANSI formats, several
 * codepages (CP1251/CP1252/Big5/UTF-8), assorted attachment shapes, special message classes, and
 * fuzzer-generated corrupt files. Uses the file-backed {@code convert(Path, ...)} overload so the
 * on-demand block-store path is exercised too.
 */
class MsgSampleCorpusTest {

    private static final Path SAMPLES = Paths.get("src/test/resources/samples/msg");

    @Test
    void everySampleConvertsOrFailsWithConversionException() throws Exception {
        try (var files = Files.list(SAMPLES)) {
            var samples = files.filter(path -> path.getFileName().toString().endsWith(".msg"))
                    .sorted()
                    .toList();
            assertTrue(samples.size() >= 40, "sample corpus went missing: " + samples.size());
            for (var sample : samples) {
                var out = new ByteArrayOutputStream();
                try {
                    MsgToEmlConverter.convert(sample, out, ConversionLog.NOOP);
                    var eml = out.toString(StandardCharsets.UTF_8);
                    assertTrue(eml.contains("MIME-Version: 1.0"), sample + " produced structurally invalid EML");
                } catch (ConversionException expected) {
                    // Corrupt samples must fail with the domain exception — anything else (a raw POI
                    // RuntimeException, an NPE) escaping this call fails the test.
                }
            }
        }
    }

    @Test
    void ansiCyrillicCp1251MessageDecodesSubjectAndBody() throws Exception {
        // Before the fix the converter never detected the ANSI string codepage (PR_MESSAGE_CODEPAGE /
        // PR_INTERNET_CPID), so POI decoded CP1251 text as windows-1252 mojibake
        // ("Àâòîìàòè÷åñêèé îòâåò..." instead of "Автоматический ответ...").
        // The subject comes verbatim from the preserved transport headers (original =?Cp1251?B?...?=
        // words); the body is re-encoded by the serializer from the codepage-detected MAPI property.
        var eml = convert("cyrillic_message.msg");
        assertTrue(decodeRfc2047(eml).contains("Автоматический ответ"), headersOf(eml));
        assertTrue(decodeQuotedPrintable(bodyOf(eml)).contains("Ваше сообщение"), headersOf(eml));
    }

    @Test
    void ansiCp1251Lcid1049MessageDecodesBody() throws Exception {
        var eml = convert("ASCII_CP1251_LCID1049.msg");
        assertTrue(decodeQuotedPrintable(bodyOf(eml)).contains("Body автоматически Body"), headersOf(eml));
    }

    @Test
    void ansiBig5ChineseMessageDecodesSubject() throws Exception {
        var eml = convert("chinese-traditional.msg");
        assertTrue(decodeRfc2047(eml).contains("MSG 格式測試"), headersOf(eml));
    }

    @Test
    void ansiHtmlStringBodyDecodesWithDetectedCodepage() throws Exception {
        // The HTML string chunk used to surface as "HTML Ã¶Ã¤Ã¼" (UTF-8 bytes read as windows-1252).
        var eml = convert("ASCII_UTF-8_CP1252_LCID1031_HTML.msg");
        assertTrue(decodeQuotedPrintable(bodyOf(eml)).contains("HTML öäü"), headersOf(eml));
    }

    @Test
    void unicodeAndAnsiVariantsProduceTheSameSubject() throws Exception {
        var expected = "This is a test message please ignore";
        assertTrue(convert("example_sent_regular.msg").contains(expected));
        assertTrue(convert("example_sent_unicode.msg").contains(expected));
        assertTrue(convert("example_received_regular.msg").contains(expected));
        assertTrue(convert("example_received_unicode.msg").contains(expected));
    }

    @Test
    void nestedMsgAndPdfAttachmentsAreExtracted() throws Exception {
        var eml = convert("attachment_msg_pdf.msg");
        assertTrue(eml.contains("message/rfc822"), headersOf(eml));
        assertTrue(eml.contains("Content-Disposition: attachment"), headersOf(eml));
        assertTrue(eml.toLowerCase().contains(".pdf"), headersOf(eml));
    }

    @Test
    void largeRecipientListIsPreserved() throws Exception {
        var eml = convert("lots-of-recipients.msg");
        var headers = headersOf(eml);
        var addressCount = headers.chars().filter(character -> character == '@').count();
        assertTrue(addressCount >= 10, "expected many recipient addresses, got " + addressCount);
    }

    @Test
    void fuzzerCorruptedSamplesFailFastWithConversionException() {
        var fuzzSamples = new String[] {
            "clusterfuzz-testcase-minimized-POIHSMFFuzzer-4735011465854976.msg",
            "clusterfuzz-testcase-minimized-POIHSMFFuzzer-4848576776503296.msg",
            "clusterfuzz-testcase-minimized-POIHSMFFuzzer-5336473854148608.msg"
        };
        for (var name : fuzzSamples) {
            var out = new ByteArrayOutputStream();
            assertThrows(
                    ConversionException.class,
                    () -> MsgToEmlConverter.convert(SAMPLES.resolve(name), out, ConversionLog.NOOP),
                    name);
        }
    }

    // R3: quick.msg carries an Exchange X.500 sender DN whose CN segment contains "@"; it used to
    // leak raw — spaces and slashes included — into the From angle brackets.
    @Test
    void exchangeDnSenderIsEncapsulatedInRealSample() throws Exception {
        var headers = unfold(headersOf(convert("quick.msg")));
        assertTrue(headers.contains("IMCEAEX-"), headers);
        assertFalse(headers.contains("</O="), headers);
    }

    // R3/R7 corpus-wide guard: every address header the converter emits must parse — each angle-addr
    // carries exactly one "@" and no whitespace.
    @Test
    void allEmittedAddressHeadersParseAsAddrSpecs() throws Exception {
        try (var files = Files.list(SAMPLES)) {
            var samples = files.filter(path -> path.getFileName().toString().endsWith(".msg"))
                    .sorted()
                    .toList();
            for (var sample : samples) {
                var out = new ByteArrayOutputStream();
                try {
                    MsgToEmlConverter.convert(sample, out, ConversionLog.NOOP);
                } catch (ConversionException expected) {
                    continue; // corrupt fuzzer samples are covered elsewhere
                }
                var headers = unfold(headersOf(out.toString(StandardCharsets.UTF_8)));
                for (var line : headers.split("\r\n")) {
                    if (!line.matches("(From|Sender|To|Cc|Bcc):.*")) {
                        continue;
                    }
                    var angleAddr = Pattern.compile("<([^>]*)>").matcher(line);
                    while (angleAddr.find()) {
                        var address = angleAddr.group(1);
                        assertTrue(
                                address.indexOf('@') > 0 && !address.contains(" "),
                                sample + " emitted unparseable angle-addr: " + line);
                    }
                }
            }
        }
    }

    // N2: an IPM.Note.SMIME.MultipartSigned message stores the complete original MIME entity in its
    // single attachment ([MS-OXOSMIME] §2.2.1). It is hoisted to the top level — original
    // Content-Type (protocol/micalg/boundary) and signature part intact — instead of being buried
    // as an unverifiable application/octet-stream attachment.
    @Test
    void clearSignedSmimeEntityIsHoistedToTopLevel() throws Exception {
        var eml = convert("logsat.com_signatures_valid.msg");
        var headers = unfold(headersOf(eml));
        assertTrue(
                headers.contains("Content-Type: multipart/signed;"
                        + " protocol=\"application/x-pkcs7-signature\"; micalg=SHA1;"
                        + " boundary=\"----=_NextPart_000_00B1_01C5E184.F3AFBB00\""),
                headers);
        assertTrue(bodyOf(eml).contains("------=_NextPart_000_00B1_01C5E184.F3AFBB00"), "original boundary kept");
        assertTrue(bodyOf(eml).contains("Content-Type: application/x-pkcs7-signature;"), "signature part kept");
        assertFalse(eml.contains("application/octet-stream"), headersOf(eml));
    }

    // R1/R2: PidTagContentFilterSpamConfidenceLevel is stored on this real-world sample; the dead
    // createCustom lookup (and the emission nested under Message-ID synthesis) meant the header was
    // never written.
    @Test
    void spamConfidenceLevelExportedFromRealSample() throws Exception {
        var eml = convert("bug66335.msg");
        assertTrue(eml.contains("X-MS-Exchange-Organization-SCL: 1"), headersOf(eml));
    }

    // N2: an opaque IPM.Note.SMIME envelope (raw PKCS#7 smime.p7m) becomes the message's own
    // application/pkcs7-mime entity instead of an unverifiable octet-stream attachment. Fixture
    // source: bbottema/outlook-message-parser (Apache-2.0).
    @Test
    void opaqueSmimeEnvelopeBecomesTopLevelEntity() throws Exception {
        var eml = convert("bbottema_smime_encrypted.msg");
        var headers = unfold(headersOf(eml));
        assertTrue(headers.contains("Content-Type: application/pkcs7-mime; name=\"smime.p7m\""), headers);
        assertTrue(headers.contains("Content-Transfer-Encoding: base64"), headers);
        assertTrue(headers.contains("Content-Disposition: attachment; filename=\"smime.p7m\""), headers);
        assertFalse(eml.contains("application/octet-stream"), headers);
        // The stored envelope's leading BER bytes 30 80 06 09 2A ... base64 to the classic prefix.
        assertTrue(bodyOf(eml).startsWith("MIAGCSqG"), bodyOf(eml).substring(0, 40));
    }

    // N2: a second, modern clear-signed sample (Thunderbird, sha-512) keeps its envelope verbatim.
    @Test
    void modernClearSignedSmimeEntityIsHoisted() throws Exception {
        var eml = convert("bbottema_smime_signed.msg");
        var headers = unfold(headersOf(eml));
        assertTrue(
                headers.contains("Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\";"
                        + " micalg=sha-512; boundary=\"------------ms040609000407030007040103\""),
                headers);
        assertFalse(eml.contains("application/octet-stream"), headers);
    }

    // N6: appointment start/end/location are readable through POI's NameIdChunks named-property
    // mapping, so the invite carries the real values instead of being dropped.
    @Test
    void appointmentExportsCalendarInvite() throws Exception {
        var eml = convert("msgClassAppointment.msg");
        var invite = decodedBase64Attachment(eml, "invite.ics");
        assertTrue(invite.contains("DTSTART:20170228T183000Z"), invite);
        assertTrue(invite.contains("DTEND:20170228T190000Z"), invite);
        assertTrue(invite.contains("LOCATION:under lazy dog"), invite);
        assertTrue(invite.contains("SUMMARY:Quick brown fox"), invite);
    }

    // N6: a recurring appointment exports its series (PidLidAppointmentRecur -> RRULE). Outlook
    // stores "daily every weekday" as a weekly BYDAY pattern ([MS-OXOCAL] §2.2.1.44.1), and the
    // all-day flag turns DTSTART into a VALUE=DATE. Fixture: HiraokaHyperTools/msgreader (Apache-2.0).
    @Test
    void recurringAppointmentInviteCarriesRecurrenceRule() throws Exception {
        var eml = convert("msgreader_A_daily_1.msg");
        var invite = decodedBase64Attachment(eml, "invite.ics");
        assertTrue(invite.contains("RRULE:FREQ=WEEKLY;INTERVAL=1;WKST=SU;BYDAY=MO,TU,WE,TH,FR"), invite);
        assertTrue(invite.contains("DTSTART;VALUE=DATE:20221211"), invite);
    }

    // N7: contacts gain a contact.vcf and tasks a task.ics VTODO — parity with the PST pipeline.
    @Test
    void contactExportsVcard() throws Exception {
        var eml = convert("msgClassContact.msg");
        var card = decodedBase64Attachment(eml, "contact.vcf");
        assertTrue(card.contains("FN:Dr Quick Brown Fox Jr"), card);
        assertTrue(card.contains("EMAIL;TYPE=internet:quickbrown@gmail.com"), card);
        assertTrue(card.contains("TEL;TYPE=work:(123) 456-7890"), card);
        assertTrue(card.contains("ORG:Fence Co"), card);
    }

    @Test
    void taskExportsVtodo() throws Exception {
        var eml = convert("msgClassTask.msg");
        var todo = decodedBase64Attachment(eml, "task.ics");
        assertTrue(todo.contains("BEGIN:VTODO"), todo);
        assertTrue(todo.contains("DTSTART:20170219T000000Z"), todo);
        assertTrue(todo.contains("DUE:20170310T000000Z"), todo);
        assertTrue(todo.contains("SUMMARY:Must jump over the lazy dog"), todo);
    }

    // N3/N5/N10 on a real store: keywords.msg carries no transport headers, so every one of these
    // headers exists only because the MAPI properties are exported — and the mandatory From falls
    // back to the explicit placeholder instead of being omitted.
    @Test
    void mapiOnlyHeadersExportedFromRealSample() throws Exception {
        var eml = convert("keywords.msg");
        var headers = unfold(headersOf(eml));
        assertTrue(headers.contains("From: <undisclosed@invalid>"), headers);
        assertTrue(headers.contains("Thread-Topic: Test Keywords"), headers);
        assertTrue(headers.contains("Thread-Index: "), headers);
        assertTrue(headers.contains("Keywords: TODO, Currently Important, Currently To Do, Test"), headers);
    }

    // N3: every parseable corpus sample must emit exactly one From header (RFC 5322 §3.6.2).
    @Test
    void everyConvertedSampleHasExactlyOneFromHeader() throws Exception {
        try (var files = Files.list(SAMPLES)) {
            var samples = files.filter(path -> path.getFileName().toString().endsWith(".msg"))
                    .sorted()
                    .toList();
            for (var sample : samples) {
                var out = new ByteArrayOutputStream();
                try {
                    MsgToEmlConverter.convert(sample, out, ConversionLog.NOOP);
                } catch (ConversionException expected) {
                    continue; // corrupt fuzzer samples are covered elsewhere
                }
                var headers = unfold(headersOf(out.toString(StandardCharsets.UTF_8)));
                var fromCount =
                        Pattern.compile("(?m)^From:").matcher(headers).results().count();
                assertTrue(fromCount == 1, sample + " emitted " + fromCount + " From headers:\r\n" + headers);
            }
        }
    }

    // Unicode-format CJK bodies survive the UTF-8 quoted-printable re-encoding. Fixture:
    // HiraokaHyperTools/msgreader (Apache-2.0).
    @Test
    void unicodeCjkBodySurvives() throws Exception {
        var eml = convert("msgreader_Hello_CJK.msg");
        var body = bodyOf(eml).replace("=\r\n", ""); // undo quoted-printable soft wraps
        assertTrue(body.contains("=E4=BD=A0=E5=A5=BD"), "Chinese 你好 lost: " + body);
        assertTrue(body.contains("=E3=81=93=E3=82=93"), "Japanese こん lost: " + body);
    }

    // A legacy ANSI CP932 (Shift-JIS) message decodes through the codepage detection and re-encodes
    // as RFC 2047 UTF-8 ("日本語 Non Un..." is the subject's first encoded-word chunk).
    @Test
    void ansiCp932SubjectDecodes() throws Exception {
        var eml = convert("msgreader_nonUnicodeCP932.msg");
        assertTrue(unfold(headersOf(eml)).contains("Subject: =?UTF-8?B?5pel5pys6KqeIE5vbiBVbg==?="), headersOf(eml));
    }

    // A real two-deep nested .msg recurses into message/rfc822 at both levels (the builder-based
    // depth tests synthesize this; this locks it on a real Outlook artifact).
    @Test
    void realTwoLevelNestedMessagesRecurse() throws Exception {
        var eml = convert("msgreader_msgInMsgInMsg.msg");
        var occurrences = eml.split("Content-Type: message/rfc822", -1).length - 1;
        assertTrue(occurrences >= 2, "expected two nested message/rfc822 levels, found " + occurrences);
    }

    // A never-sent draft (no submit/delivery times, no sender address) still gets the mandatory
    // RFC 5322 headers, visibly flagged as synthesized.
    @Test
    void unsentDraftGetsMandatoryHeaders() throws Exception {
        var eml = convert("bbottema_unsent_draft.msg");
        var headers = unfold(headersOf(eml));
        assertTrue(headers.contains("From: <undisclosed@invalid>"), headers);
        assertTrue(headers.contains("Date: "), headers);
        assertTrue(headers.contains("X-MailKit-Synthesized-Headers: From,"), headers);
    }

    /** Finds the named base64 attachment part and decodes its payload to UTF-8 text. */
    private static String decodedBase64Attachment(String eml, String filename) {
        var marker = "filename=\"" + filename + "\"";
        var markerIndex = eml.indexOf(marker);
        assertTrue(markerIndex >= 0, "attachment " + filename + " not found:\r\n" + headersOf(eml));
        var payloadStart = eml.indexOf("\r\n\r\n", markerIndex) + 4;
        var payloadEnd = eml.indexOf("\r\n--", payloadStart);
        var base64 = eml.substring(payloadStart, payloadEnd < 0 ? eml.length() : payloadEnd)
                .replace("\r\n", "");
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private static String unfold(String headers) {
        return headers.replace("\r\n ", " ").replace("\r\n\t", " ");
    }

    private static String convert(String sampleName) throws ConversionException {
        var out = new ByteArrayOutputStream();
        MsgToEmlConverter.convert(SAMPLES.resolve(sampleName), out, ConversionLog.NOOP);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String headersOf(String eml) {
        var blank = eml.indexOf("\r\n\r\n");
        return blank < 0 ? eml : eml.substring(0, blank);
    }

    private static String bodyOf(String eml) {
        var blank = eml.indexOf("\r\n\r\n");
        return blank < 0 ? "" : eml.substring(blank + 4);
    }

    /** Decodes all RFC 2047 B-encoded words in the text (any charset) into one string. */
    private static String decodeRfc2047(String text) {
        var matcher = Pattern.compile("=\\?([A-Za-z0-9_-]+)\\?B\\?([A-Za-z0-9+/=]+)\\?=")
                .matcher(text);
        var decoded = new StringBuilder();
        while (matcher.find()) {
            var charset = Charset.forName(matcher.group(1));
            decoded.append(new String(Base64.getDecoder().decode(matcher.group(2)), charset));
        }
        return decoded.toString();
    }

    /** Minimal quoted-printable decoder (soft line breaks + {@code =XX} escapes) for assertions. */
    private static String decodeQuotedPrintable(String text) {
        var joined = text.replace("=\r\n", "");
        var bytes = new ByteArrayOutputStream();
        for (var index = 0; index < joined.length(); index++) {
            var character = joined.charAt(index);
            if (character == '=' && index + 2 < joined.length()) {
                try {
                    bytes.write(Integer.parseInt(joined.substring(index + 1, index + 3), 16));
                    index += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // not an escape — fall through and keep the literal character
                }
            }
            bytes.write(character);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
