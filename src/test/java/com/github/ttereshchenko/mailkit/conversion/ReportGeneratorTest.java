package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.conversion.ReportGenerator.Report;
import com.github.ttereshchenko.mailkit.conversion.ReportGenerator.ReportInfo;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReportGeneratorTest {

    // -----------------------------------------------------------------------
    // Delivery-status (NDR / DSN) — rfc3464 / rfc6522
    // -----------------------------------------------------------------------

    @Test
    void deliveryReportContentTypeIsMultipartReportWithDeliveryStatusReportType() {
        var report = generate(deliveryInfo("failed", "5.1.1", null));

        assertTrue(
                report.contentType().startsWith("multipart/report;"),
                "must be multipart/report: " + report.contentType());
        assertTrue(
                report.contentType().contains("report-type=delivery-status"),
                "delivery report must carry report-type=delivery-status: " + report.contentType());
        assertTrue(
                report.contentType().contains("boundary=\""),
                "Content-Type must declare a boundary: " + report.contentType());
    }

    @Test
    void deliveryReportBodyContainsDeliveryStatusPart() {
        var report = generate(deliveryInfo("failed", "5.1.1", "smtp; 550 User unknown"));

        assertTrue(
                report.body().contains("Content-Type: message/delivery-status"),
                "body must include a message/delivery-status part: " + report.body());
    }

    @Test
    void deliveryReportCarriesReportingMtaFinalRecipientActionAndStatus() {
        var report = generate(new ReportInfo(
                true,
                "Your message could not be delivered.",
                "mail.example.com",
                "user@example.com",
                "failed",
                "5.1.1",
                "smtp; 550 User unknown",
                null,
                null));

        var body = report.body();
        assertTrue(body.contains("Reporting-MTA: dns; mail.example.com"), body);
        assertTrue(body.contains("Final-Recipient: rfc822; user@example.com"), body);
        assertTrue(body.contains("Action: failed"), body);
        assertTrue(body.contains("Status: 5.1.1"), body);
        // diagnosticCode already carries its own "smtp;" type token, so prefixed() must NOT add a second
        // one (rfc3464 §2.3.6: the field is <type> ";" <text>).
        assertTrue(body.contains("Diagnostic-Code: smtp; 550 User unknown"), body);
        assertFalse(body.contains("smtp; smtp;"), "the type token must not be doubled: " + body);
    }

    @Test
    void diagnosticWithoutTypeIsPrefixedAndStatusLeadingZerosAreStripped() {
        var report = generate(new ReportInfo(
                true,
                "Delivery failed.",
                "mail.example.com",
                "user@example.com",
                "failed",
                "5.01.001",
                "550 mailbox full",
                null,
                null));

        var body = report.body();
        // A bare diagnostic with no type token still gets the smtp; qualifier...
        assertTrue(body.contains("Diagnostic-Code: smtp; 550 mailbox full"), body);
        // ...and a non-canonical status with leading-zero sub-fields is normalized (rfc3464 §2.3.4).
        assertTrue(body.contains("Status: 5.1.1"), body);
        assertFalse(body.contains("5.01.001"), body);
    }

    @Test
    void deliveryReportBoundaryAppearsInBodyAndMatchesContentType() {
        var report = generate(deliveryInfo("delivered", "2.0.0", null));

        var boundary = extractBoundary(report.contentType());
        assertNotNull(boundary, "must find boundary in Content-Type");
        assertTrue(
                report.body().contains("--" + boundary + "\r\n"), "body must contain the opening boundary delimiter");
        assertTrue(report.body().contains("--" + boundary + "--"), "body must contain the closing boundary delimiter");
    }

    @Test
    void deliveryReportHumanReadablePartAppearsFirst() {
        var report = generate(new ReportInfo(
                true, "Delivery failed.", "mta.example.com", "r@example.com", "failed", "5.1.0", null, null, null));

        var body = report.body();
        var textPartPos = body.indexOf("Content-Type: text/plain");
        var statusPartPos = body.indexOf("Content-Type: message/delivery-status");
        assertTrue(textPartPos >= 0, "text/plain part must exist");
        assertTrue(statusPartPos >= 0, "delivery-status part must exist");
        assertTrue(
                textPartPos < statusPartPos,
                "text/plain (human-readable) must come before message/delivery-status (rfc6522 §3)");
    }

    @Test
    void deliveryReportFallsBackToDefaultTextWhenHumanReadableIsBlank() {
        var report = generate(new ReportInfo(true, "", null, null, null, null, null, null, null));

        assertTrue(
                report.body().contains("This is a delivery status notification"),
                "blank humanReadableText must produce a default fallback: " + report.body());
    }

    @Test
    void deliveryReportFallsBackToDefaultTextWhenHumanReadableIsNull() {
        var report = generate(new ReportInfo(true, null, null, null, null, null, null, null, null));

        assertTrue(
                report.body().contains("This is a delivery status notification"),
                "null humanReadableText must produce a default fallback: " + report.body());
    }

    @Test
    void mandatoryFieldsAreDefaultedWhileOptionalFieldsStayOmitted() {
        // A sparse NDR supplies nothing. rfc3464 makes Reporting-MTA (§2.2.2), Final-Recipient
        // (§2.3.1/§2.3.2), Action (§2.3.3) and Status (§2.3.4) MANDATORY, so a structurally valid
        // delivery-status block must default them rather than drop them; Diagnostic-Code (§2.3.6)
        // is optional and is still omitted when absent.
        var report = generate(new ReportInfo(true, null, null, null, null, null, null, null, null));

        var body = report.body();
        assertTrue(body.contains("Reporting-MTA: dns; unknown"), "missing Reporting-MTA must default: " + body);
        assertTrue(body.contains("Final-Recipient: rfc822; unknown"), "missing Final-Recipient must default: " + body);
        assertTrue(body.contains("Action: failed"), "missing Action must default to failed: " + body);
        assertTrue(body.contains("Status: 5.0.0"), "missing Status must default to 5.0.0: " + body);
        assertFalse(body.contains("Diagnostic-Code:"), "optional Diagnostic-Code must stay omitted: " + body);
    }

    @Test
    void suppliedMandatoryFieldsAreNotOverriddenByDefaults() {
        // When the values are present they must win over the defaults.
        var report = generate(new ReportInfo(
                true, null, "mta.example.com", "user@example.com", "delayed", "4.4.7", null, null, null));

        var body = report.body();
        assertTrue(body.contains("Reporting-MTA: dns; mta.example.com"), body);
        assertTrue(body.contains("Final-Recipient: rfc822; user@example.com"), body);
        assertTrue(body.contains("Action: delayed"), body);
        assertTrue(body.contains("Status: 4.4.7"), body);
        assertFalse(body.contains("dns; unknown"), "a present host must not be replaced by the sentinel: " + body);
        assertFalse(body.contains("rfc822; unknown"), "a present recipient must not be replaced: " + body);
    }

    // -----------------------------------------------------------------------
    // Disposition-notification (MDN / read receipt) — rfc8098 / rfc6522
    // -----------------------------------------------------------------------

    @Test
    void readReceiptContentTypeIsMultipartReportWithDispositionNotificationReportType() {
        var report = generate(receiptInfo("displayed", "<orig-001@example.com>"));

        assertTrue(
                report.contentType().startsWith("multipart/report;"),
                "must be multipart/report: " + report.contentType());
        assertTrue(
                report.contentType().contains("report-type=disposition-notification"),
                "read receipt must carry report-type=disposition-notification: " + report.contentType());
    }

    @Test
    void readReceiptBodyContainsDispositionNotificationPart() {
        var report = generate(receiptInfo("displayed", "<orig-001@example.com>"));

        assertTrue(
                report.body().contains("Content-Type: message/disposition-notification"),
                "body must include a message/disposition-notification part: " + report.body());
    }

    @Test
    void readReceiptCarriesFinalRecipientOriginalMessageIdAndDisposition() {
        var report = generate(new ReportInfo(
                false,
                "Your message was read.",
                null,
                "reader@example.com",
                null,
                null,
                null,
                "<orig-abc@example.com>",
                "displayed"));

        var body = report.body();
        assertTrue(body.contains("Final-Recipient: rfc822; reader@example.com"), body);
        assertTrue(body.contains("Original-Message-ID: <orig-abc@example.com>"), body);
        assertTrue(
                body.contains("Disposition: automatic-action/MDN-sent-automatically; displayed"),
                "must include a well-formed Disposition field: " + body);
    }

    @Test
    void readReceiptWrapsUnbracketedOriginalMessageIdInAngleBrackets() {
        // rfc8098 §3.2.5 / rfc5322 §3.6.4 require msg-id angle brackets; Outlook stores
        // PidTagOriginalMessageId both with and without them, so a bare value must be wrapped to
        // emit a grammatically valid (and self-consistent) Original-Message-ID.
        var report = generate(receiptInfo("displayed", "orig-bare@example.com"));

        assertTrue(
                report.body().contains("Original-Message-ID: <orig-bare@example.com>"),
                "unbracketed Original-Message-ID must be wrapped in angle brackets: " + report.body());
    }

    @Test
    void readReceiptDeletedDispositionTypeIsNormalized() {
        var report = generate(receiptInfo("deleted", null));

        assertTrue(
                report.body().contains("Disposition: automatic-action/MDN-sent-automatically; deleted"),
                "deleted disposition must appear verbatim: " + report.body());
    }

    @Test
    void readReceiptFallsBackToDisplayedWhenDispositionTypeIsNull() {
        var report = generate(receiptInfo(null, null));

        assertTrue(
                report.body().contains("Disposition: automatic-action/MDN-sent-automatically; displayed"),
                "null dispositionType must default to displayed: " + report.body());
    }

    @Test
    void readReceiptDefaultsMandatoryFinalRecipientWhenAddressMissing() {
        // rfc8098 §3.1 makes final-recipient-field REQUIRED in an MDN. A read receipt with no
        // recipient address must still emit the field (defaulted), exactly as the DSN path defaults
        // its mandatory fields — not drop it and produce a structurally invalid notification.
        var report = generate(new ReportInfo(
                false, "Your message was read.", null, null, null, null, null, "<orig-1@example.com>", "displayed"));

        assertTrue(
                report.body().contains("Final-Recipient: rfc822; unknown"),
                "a missing MDN Final-Recipient must be defaulted, not omitted: " + report.body());
    }

    @Test
    void readReceiptFallsBackToDefaultTextWhenHumanReadableIsBlank() {
        var report = generate(new ReportInfo(false, "  ", null, null, null, null, null, null, null));

        assertTrue(
                report.body().contains("This is a return receipt"),
                "blank humanReadableText must produce a default fallback for MDN: " + report.body());
    }

    @Test
    void readReceiptDeliveryStatusFieldsAreOmitted() {
        // NDR-only fields must not appear on an MDN.
        var report = generate(new ReportInfo(
                false, null, "mta.example.com", "r@example.com", "failed", "5.1.1", "smtp; 550", null, "displayed"));

        var body = report.body();
        assertFalse(body.contains("Reporting-MTA: dns;"), "MDN must not carry Reporting-MTA: " + body);
        assertFalse(body.contains("Action:"), "MDN must not carry Action: " + body);
        assertFalse(body.contains("Status:"), "MDN must not carry Status: " + body);
        assertFalse(body.contains("Diagnostic-Code:"), "MDN must not carry Diagnostic-Code: " + body);
    }

    // -----------------------------------------------------------------------
    // Header-injection / boundary-injection hardening
    // -----------------------------------------------------------------------

    @Test
    void crlfInHumanReadableTextCannotInjectBoundaryOrHeaders() {
        // A crafted text that embeds CRLF + the boundary token must not break the multipart
        // structure by injecting a false boundary delimiter.
        var crafted = "Normal text\r\n--INJECT--\r\nEvil-Header: gotcha\r\nmore text";
        var report = generate(new ReportInfo(true, crafted, null, null, null, null, null, null, null));

        // Every line that starts with "--" must be one of the real boundary delimiters; no injected
        // line starting with "--INJECT" may survive into the output as an actual boundary.
        for (var line : report.body().split("\r\n")) {
            if (line.startsWith("--INJECT")) {
                // The crafted boundary string must not appear as a standalone line in the body
                // (it may appear within the text/plain content body, but NOT as a delimiter, i.e.
                // it must not be preceded by a real CRLF on its own line at the multipart level).
                // Verifying via the full content-type boundary is sufficient: the real boundary
                // from the Content-Type does not equal "INJECT".
                var realBoundary = extractBoundary(report.contentType());
                assertFalse(
                        line.equals("--" + realBoundary) || line.equals("--" + realBoundary + "--"),
                        "crafted text must not produce a real boundary delimiter: " + line);
            }
        }
        // The structure must still be parseable: both boundary delimiters present.
        var boundary = extractBoundary(report.contentType());
        assertTrue(report.body().contains("--" + boundary + "\r\n"));
        assertTrue(report.body().contains("--" + boundary + "--"));
    }

    @Test
    void crlfInStatusFieldCannotInjectAStandaloneHeaderLine() {
        // clean() replaces CR/LF with spaces, so the injection becomes part of the field value on
        // one line rather than a separate header field. The attacker string X-Injected: may still
        // appear as field-value text, but must never appear at the start of its own CRLF-delimited line.
        var report = generate(
                new ReportInfo(true, null, null, null, "failed", "5.1.1\r\nX-Injected: evil", null, null, null));

        for (var line : report.body().split("\r\n")) {
            assertFalse(
                    line.startsWith("X-Injected:"),
                    "CRLF in Status must not inject X-Injected as a standalone header line: " + report.body());
        }
    }

    @Test
    void crlfInFinalRecipientCannotInjectAStandaloneHeaderLine() {
        // Same invariant: the CR/LF is collapsed to space, so the injected text is folded into the
        // field value and cannot start a new line.
        var report = generate(
                new ReportInfo(true, null, null, "user@example.com\r\nX-Bad: evil", null, null, null, null, null));

        for (var line : report.body().split("\r\n")) {
            assertFalse(
                    line.startsWith("X-Bad:"),
                    "CRLF in Final-Recipient must not inject X-Bad as a standalone header line: " + report.body());
        }
    }

    // -----------------------------------------------------------------------
    // Part 1 human-readable — 8bit vs quoted-printable (rfc5322 §2.1.1)
    // -----------------------------------------------------------------------

    @Test
    void humanReadableLineOverHardLimitSwitchesPartOneToQuotedPrintable() {
        // Since round 20 the human-readable text can be an unwrapped PR_BODY fallback: a single line
        // longer than the rfc5322 §2.1.1 998-octet hard limit emitted under 8bit is truncated or
        // hard-folded by a strict MTA. Part 1 must switch to quoted-printable (self-wraps <=76).
        var longLine = "x".repeat(1500);
        var report = generate(new ReportInfo(true, longLine, null, null, null, null, null, null, null));

        var body = report.body();
        assertTrue(
                body.contains("Content-Transfer-Encoding: quoted-printable"),
                "an overlong human-readable line must switch Part 1 to quoted-printable: " + body);
        assertFalse(
                body.contains("Content-Transfer-Encoding: 8bit"),
                "the 8bit CTE must be gone once Part 1 is quoted-printable: " + body);
        // The whole assembled body must now be free of any line over the 998-octet hard limit.
        for (var line : body.split("\r\n", -1)) {
            assertTrue(
                    line.getBytes(StandardCharsets.UTF_8).length <= 998,
                    "no line may exceed 998 octets after QP encoding: [" + line + "]");
        }
        // The encoder soft-wrapped, so the raw 1500-char run must not survive intact on one line.
        assertTrue(body.contains("=\r\n"), "quoted-printable must soft-wrap the long run: " + body);
    }

    @Test
    void shortHumanReadablePartKeepsEightBitEmissionByteIdentical() {
        // The >998 gate is load-bearing: a short, pre-wrapped body must keep the verbatim 8bit
        // emission byte-for-byte (an unconditional CTE switch was excluded in a prior round).
        var report = generate(
                new ReportInfo(true, "Your message was not delivered.", null, null, null, null, null, null, null));

        var body = report.body();
        assertTrue(body.contains("Content-Transfer-Encoding: 8bit"), "a short body must stay on 8bit: " + body);
        assertFalse(body.contains("quoted-printable"), "a short body must not switch to quoted-printable: " + body);

        // Byte-identity of the 8bit Part 1: the 8bit CTE, a blank line, then the verbatim text
        // terminated by exactly one CRLF that precedes the (random) boundary delimiter.
        var boundary = extractBoundary(report.contentType());
        assertTrue(
                body.contains("Content-Transfer-Encoding: 8bit\r\n\r\nYour message was not delivered.\r\n"),
                "the 8bit Part 1 must carry the verbatim human-readable text: " + body);
        assertTrue(
                body.contains("Your message was not delivered.\r\n--" + boundary),
                "the human-readable text must be followed by exactly one CRLF then the boundary: " + body);
    }

    @Test
    void quotedPrintablePartOneStillYieldsExactlyTheRealBoundaryDelimiters() {
        // caveat (b): boundary neutralization must still run on the QP path. Even a body long enough
        // to trigger quoted-printable must not forge a part separator; the structure stays parseable
        // with exactly the two real boundary delimiters.
        var longText = "This report is padded to force quoted-printable. " + "y".repeat(1200);
        var report = generate(new ReportInfo(true, longText, null, null, null, null, null, null, null));

        var body = report.body();
        assertTrue(
                body.contains("Content-Transfer-Encoding: quoted-printable"),
                "the padded body must trigger quoted-printable: " + body);
        var boundary = extractBoundary(report.contentType());
        assertTrue(body.contains("--" + boundary + "\r\n"), "opening boundary delimiter must be present: " + body);
        assertTrue(body.contains("--" + boundary + "--"), "closing boundary delimiter must be present: " + body);
    }

    // -----------------------------------------------------------------------
    // Header folding (rfc5322 §2.2.3) — must not collapse internal whitespace
    // -----------------------------------------------------------------------

    @Test
    void foldingPreservesInternalWhitespaceInDiagnosticCode() {
        // A Diagnostic-Code whose value embeds runs of significant whitespace and is long enough to
        // force at least one fold. rfc5322 §2.2.3 unfolds a header by deleting the CRLF that precedes
        // a WSP — folding must therefore insert CRLF+WSP at a fold point WITHOUT collapsing the
        // value's own internal whitespace runs. The pre-fix split(" ") collapsed every run to a
        // single space, corrupting the value.
        var diagnostic = "550 5.1.1   mailbox    unavailable        rejected    by    remote    host    table";
        var report = generate(new ReportInfo(true, null, null, null, "failed", "5.1.1", diagnostic, null, null));

        var body = report.body();
        var headerStart = body.indexOf("Diagnostic-Code:");
        assertTrue(headerStart >= 0, "Diagnostic-Code must be present: " + body);
        // Collect the folded field: its first line plus every continuation line (those beginning with
        // WSP), then unfold per rfc5322 §2.2.3 (drop the CRLF before the leading WSP).
        var field = new StringBuilder();
        var lines = body.substring(headerStart).split("\r\n", -1);
        field.append(lines[0]);
        for (var index = 1; index < lines.length; index++) {
            var line = lines[index];
            if (line.startsWith(" ") || line.startsWith("\t")) {
                field.append('\n').append(line);
            } else {
                break;
            }
        }
        var unfolded = field.toString().replace("\n ", " ").replace("\n\t", "\t");
        assertTrue(
                unfolded.contains(diagnostic),
                "unfolding must reproduce the original internal whitespace verbatim: [" + unfolded + "]");
    }

    @Test
    void foldingBreaksLongFieldAtSeventyEightCharacters() {
        // A long Final-Recipient must be folded so every emitted line stays within the rfc5322
        // §2.1.1 soft limit of 78 characters where a fold opportunity exists.
        var report = generate(new ReportInfo(
                true,
                null,
                "this-is-a-long-reporting-host.subdomain.example.com",
                "a-very-long-local-part.that-keeps-going@a-long-domain.subdomain.example.com",
                "failed",
                "5.1.1",
                null,
                null,
                null));

        var body = report.body();
        var headerStart = body.indexOf("Final-Recipient:");
        var firstLine = body.substring(headerStart, body.indexOf("\r\n", headerStart));
        assertTrue(
                firstLine.length() <= 78,
                "the first folded line of a long field must respect the 78-char soft limit: " + firstLine);
    }

    @Test
    void generateThrowsOnNullInfo() {
        try {
            ReportGenerator.generate(null);
            throw new AssertionError("generate(null) must throw NullPointerException");
        } catch (NullPointerException expected) {
            // correct
        }
    }

    // -----------------------------------------------------------------------
    // Enhanced status code (rfc3464 §2.3.4) — shared by the MSG and PST report paths
    // -----------------------------------------------------------------------

    @Test
    void statusCodeExtractsEmbeddedEnhancedCode() {
        // A d.d.d token mined from free-form text is preferred over any class default.
        assertEquals("5.1.1", ReportGenerator.statusCode(true, "Delivery failed: #5.1.1 user unknown", null));
    }

    @Test
    void statusCodeFallsBackToClassDefault() {
        // No embedded code: a permanent failure defaults to 5.0.0, a success to 2.0.0.
        assertEquals("5.0.0", ReportGenerator.statusCode(true, "no code here", null));
        assertEquals("2.0.0", ReportGenerator.statusCode(false, null, "still nothing"));
    }

    @Test
    void statusCodePrefersFirstCandidateThatCarriesACode() {
        assertEquals("4.4.7", ReportGenerator.statusCode(true, "queued 4.4.7", "later 5.5.0"));
    }

    @Test
    void extractStatusCodeIgnoresNonStatusNumbers() {
        // A version/date (leading digit not 2/4/5) must not be mistaken for an enhanced status code.
        assertNull(ReportGenerator.extractStatusCode("build 1.2.3 dated 2026.06.16"));
        assertEquals("5.1.1", ReportGenerator.extractStatusCode("oops 5.1.1 here"));
    }

    @Test
    void extractStatusCodeIgnoresVersionAndBuildNumbers() {
        // Audit: a 2/4/5 class digit *inside* a longer dotted number — an Exchange version/build banner
        // like "15.2.1544.5" — must not be mined as a fabricated status (5.2.154). The token boundaries
        // reject it, while a genuine standalone code in the same text is still extracted.
        assertNull(ReportGenerator.extractStatusCode("Generating server: EXCH 15.2.1544.5"));
        assertNull(ReportGenerator.extractStatusCode("agent 14.3.123.4 build"));
        assertEquals("5.1.1", ReportGenerator.extractStatusCode("550 5.1.1 User unknown (15.2.1544.5)"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Report generate(ReportInfo info) {
        return ReportGenerator.generate(info);
    }

    private static ReportInfo deliveryInfo(String action, String status, String diagnosticCode) {
        return new ReportInfo(
                true,
                "Your message was not delivered.",
                "mta.example.com",
                "recipient@example.com",
                action,
                status,
                diagnosticCode,
                null,
                null);
    }

    private static ReportInfo receiptInfo(String dispositionType, String originalMessageId) {
        return new ReportInfo(
                false,
                "Your message was read.",
                null,
                "reader@example.com",
                null,
                null,
                null,
                originalMessageId,
                dispositionType);
    }

    /** Extracts the bare boundary token from a {@code Content-Type} value. */
    private static String extractBoundary(String contentType) {
        var marker = "boundary=\"";
        var start = contentType.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        var end = contentType.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return contentType.substring(start, end);
    }
}
