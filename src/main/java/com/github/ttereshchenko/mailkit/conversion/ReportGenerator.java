package com.github.ttereshchenko.mailkit.conversion;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Generates an RFC 6522 {@code multipart/report} body for Outlook {@code REPORT.*} messages —
 * non-delivery reports / delivery-status notifications (DSNs) and read receipts (MDNs). The output
 * is a fully assembled MIME entity: a {@link Report} carries the top-level {@code Content-Type}
 * value (boundary included) and the complete multipart body, ready to be written verbatim.
 *
 * <p>Per rfc6522 §3 a {@code multipart/report} has two parts here: a human-readable
 * {@code text/plain} explanation first, then a machine-parsable status part. For delivery reports
 * the status part is {@code message/delivery-status} with per-message and per-recipient fields
 * (rfc3464 §2.2 / §2.3); for read receipts it is {@code message/disposition-notification} with the
 * {@code Final-Recipient} and {@code Disposition} fields (rfc8098 §3).
 *
 * <p>This generator is POI-free and reusable from both the MSG and PST conversion paths: callers
 * extract values into a plain-String {@link ReportInfo}. All field values are header-escaped (CR and
 * LF stripped) so a crafted Outlook property cannot inject extra header fields or break the
 * multipart structure.
 */
public final class ReportGenerator {

    /**
     * The values a delivery report or read receipt maps onto; every field may be {@code null} or
     * blank and is then omitted. Plain Strings/booleans only — no POI types — so the same record is
     * populated from either a {@code MAPIMessage} (MSG) or a PST message object.
     *
     * @param isDeliveryReport {@code true} emits a {@code message/delivery-status} report (NDR/DSN);
     *     {@code false} emits a {@code message/disposition-notification} read receipt (MDN)
     * @param humanReadableText the first-part {@code text/plain} explanation shown to the reader
     *     (e.g. the report text); when blank a terse default sentence is substituted
     * @param reportingMta the {@code Reporting-MTA} per-message value (delivery reports; rfc3464
     *     §2.2.2); ignored for read receipts
     * @param finalRecipient the {@code Final-Recipient} address (rfc3464 §2.3.2 / rfc8098 §3.2.4)
     * @param action the {@code Action} value for delivery reports — {@code failed} / {@code delayed}
     *     / {@code delivered} / {@code relayed} / {@code expanded} (rfc3464 §2.3.3); ignored for
     *     read receipts
     * @param status the {@code Status} {@code class.subject.detail} code (rfc3464 §2.3.4); ignored
     *     for read receipts
     * @param diagnosticCode the transport {@code Diagnostic-Code} text (rfc3464 §2.3.6); ignored for
     *     read receipts
     * @param originalMessageId the {@code Original-Message-ID} of the message the receipt concerns
     *     (rfc8098 §3.2.5); ignored for delivery reports
     * @param dispositionType the read-receipt {@code disposition-type} — typically {@code displayed}
     *     or {@code deleted} (rfc8098 §3.2.6); ignored for delivery reports
     */
    public record ReportInfo(
            boolean isDeliveryReport,
            String humanReadableText,
            String reportingMta,
            String finalRecipient,
            String action,
            String status,
            String diagnosticCode,
            String originalMessageId,
            String dispositionType) {}

    /**
     * A rendered report.
     *
     * @param contentType the full top-level {@code Content-Type} header value, boundary included
     *     (e.g. {@code multipart/report; report-type=delivery-status; boundary="..."})
     * @param body the complete multipart body — every part, CRLF-terminated, ready to write verbatim
     */
    public record Report(String contentType, String body) {}

    private static final String CRLF = "\r\n";

    private ReportGenerator() {}

    /**
     * Builds the {@code multipart/report} entity. A report with no machine-readable values still
     * yields a structurally valid two-part body (the status part simply carries only the fields that
     * were present, with sensible {@code address-type} / {@code mta-name-type} prefixes supplied).
     */
    public static Report generate(ReportInfo info) {
        Objects.requireNonNull(info, "info");

        var boundary = "MailKit-Report-" + UUID.randomUUID();
        var reportType = info.isDeliveryReport() ? "delivery-status" : "disposition-notification";
        var contentType = "multipart/report; report-type=" + reportType + "; boundary=\"" + boundary + "\"";

        var body = new StringBuilder();

        // Part 1 (rfc6522 §3): the human-readable explanation.
        appendBoundary(body, boundary, false);
        body.append("Content-Type: text/plain; charset=utf-8").append(CRLF);
        body.append("Content-Transfer-Encoding: 8bit").append(CRLF);
        body.append(CRLF);
        // Defense-in-depth: Part 1 deliberately preserves the original CRLFs of the human-readable
        // text, so guard against a crafted body whose line reproduces the (random) boundary delimiter
        // and could otherwise be parsed as a part separator (rfc2046 §5.1.1). The status-part header
        // fields are already CRLF-scrubbed; this removes the remaining implicit reliance on the
        // boundary's unguessability.
        body.append(neutralizeBoundary(humanReadableText(info), boundary));
        body.append(CRLF);

        // Part 2 (rfc6522 §3): the machine-parsable status part.
        appendBoundary(body, boundary, false);
        if (info.isDeliveryReport()) {
            appendDeliveryStatus(body, info);
        } else {
            appendDispositionNotification(body, info);
        }

        appendBoundary(body, boundary, true);
        return new Report(contentType, body.toString());
    }

    /**
     * Breaks any run that would be read as this entity's boundary delimiter, so a crafted
     * human-readable body cannot forge a part separator (rfc2046 §5.1.1). Belt-and-suspenders given
     * the boundary already carries a random UUID — it stops the safety from depending on that.
     */
    private static String neutralizeBoundary(String text, String boundary) {
        return text.replace("--" + boundary, "- -" + boundary);
    }

    /** Falls back to a terse sentence so part 1 is never empty (rfc6522 §3 requires it). */
    private static String humanReadableText(ReportInfo info) {
        if (info.humanReadableText() != null && !info.humanReadableText().isBlank()) {
            // Normalise to CRLF so the body uses one line ending throughout; never escaped (this is
            // a body, not a header), but surrounding whitespace is stripped to avoid a doubled blank line.
            return info.humanReadableText()
                    .strip()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .replace("\n", CRLF);
        }
        return info.isDeliveryReport()
                ? "This is a delivery status notification for a message you sent."
                : "This is a return receipt for a message you sent.";
    }

    /** {@code message/delivery-status}: per-message fields, a blank line, then per-recipient fields (rfc3464 §2.1). */
    private static void appendDeliveryStatus(StringBuilder body, ReportInfo info) {
        body.append("Content-Type: message/delivery-status").append(CRLF);
        body.append(CRLF);

        // Per-message fields (rfc3464 §2.2). Reporting-MTA carries an mta-name-type prefix.
        appendField(body, "Reporting-MTA", prefixed("dns", info.reportingMta()));
        body.append(CRLF);

        // Per-recipient fields (rfc3464 §2.3): Final-Recipient / Action / Status / Diagnostic-Code.
        appendField(body, "Final-Recipient", prefixed("rfc822", info.finalRecipient()));
        appendField(body, "Action", lower(info.action()));
        appendField(body, "Status", clean(info.status()));
        appendField(body, "Diagnostic-Code", prefixed("smtp", info.diagnosticCode()));
        body.append(CRLF);
    }

    /** {@code message/disposition-notification}: Final-Recipient / Original-Message-ID / Disposition (rfc8098 §3). */
    private static void appendDispositionNotification(StringBuilder body, ReportInfo info) {
        body.append("Content-Type: message/disposition-notification").append(CRLF);
        body.append(CRLF);

        appendField(body, "Reporting-UA", "MailKit");
        appendField(body, "Final-Recipient", prefixed("rfc822", info.finalRecipient()));
        appendField(body, "Original-Message-ID", clean(info.originalMessageId()));
        appendField(body, "Disposition", disposition(info.dispositionType()));
        body.append(CRLF);
    }

    /**
     * Builds the {@code Disposition} value (rfc8098 §3.2.6). An MDN converted from a stored Outlook
     * receipt was generated by the MUA without the recipient's explicit per-message consent, so the
     * mode is {@code automatic-action/MDN-sent-automatically}; the type defaults to {@code displayed}.
     */
    private static String disposition(String dispositionType) {
        var type = dispositionType != null && !dispositionType.isBlank() ? lower(dispositionType.strip()) : "displayed";
        return "automatic-action/MDN-sent-automatically; " + type;
    }

    /** Appends one folded header field, skipping it entirely when the value is null or blank. */
    private static void appendField(StringBuilder body, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        appendFolded(body, name + ": " + value);
    }

    /** Prefixes a value with a {@code type;} qualifier (e.g. {@code rfc822;}) when the value is present. */
    private static String prefixed(String type, String value) {
        var cleaned = clean(value);
        return cleaned == null || cleaned.isBlank() ? null : type + "; " + cleaned;
    }

    private static String lower(String value) {
        var cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    /** Strips CR/LF so a field value cannot inject extra header fields, and trims surrounding space. */
    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r", " ").replace("\n", " ").strip();
    }

    private static void appendBoundary(StringBuilder body, String boundary, boolean closing) {
        body.append("--").append(boundary);
        if (closing) {
            body.append("--");
        }
        body.append(CRLF);
    }

    /**
     * Folds a header field at 78 characters per rfc5322 §2.1.1, inserting a CRLF + space before a
     * whitespace-delimited token that would overflow. Never splits inside a token, so a long
     * Diagnostic-Code stays intact even when no fold opportunity exists.
     */
    private static void appendFolded(StringBuilder body, String headerLine) {
        var tokens = headerLine.split(" ");
        var lineLength = 0;
        for (var index = 0; index < tokens.length; index++) {
            var token = tokens[index];
            if (index == 0) {
                body.append(token);
                lineLength = token.length();
            } else if (lineLength + 1 + token.length() > 78) {
                body.append(CRLF).append(' ').append(token);
                lineLength = 1 + token.length();
            } else {
                body.append(' ').append(token);
                lineLength += 1 + token.length();
            }
        }
        body.append(CRLF);
    }
}
