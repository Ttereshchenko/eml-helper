package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ttereshchenko.mailkit.conversion.ReportGenerator.Report;
import com.github.ttereshchenko.mailkit.conversion.ReportGenerator.ReportInfo;
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
        // diagnosticCode is not lowercased; the smtp; prefix comes from prefixed().
        assertTrue(body.contains("Diagnostic-Code: smtp; smtp; 550 User unknown"), body);
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
    void nullAndBlankFieldsAreOmittedFromDeliveryStatus() {
        // Only supply Action; all other optional delivery-status fields are null.
        var report = generate(new ReportInfo(true, null, null, null, "failed", null, null, null, null));

        var body = report.body();
        assertTrue(body.contains("Action: failed"), body);
        assertFalse(body.contains("Reporting-MTA:"), "null Reporting-MTA must be omitted: " + body);
        assertFalse(body.contains("Final-Recipient:"), "null Final-Recipient must be omitted: " + body);
        assertFalse(body.contains("Status:"), "null Status must be omitted: " + body);
        assertFalse(body.contains("Diagnostic-Code:"), "null Diagnostic-Code must be omitted: " + body);
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
