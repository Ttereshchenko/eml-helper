package com.github.ttereshchenko.mailkit.conversion.msg;

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
