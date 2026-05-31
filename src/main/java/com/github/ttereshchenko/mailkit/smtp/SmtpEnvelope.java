package com.github.ttereshchenko.mailkit.smtp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SMTP envelope — the addresses the server routes on, distinct from the {@code From:} / {@code To:}
 * headers carried inside the message body. DSN fields ({@code NOTIFY}, {@code ORCPT}, {@code ENVID},
 * {@code RET}) are placeholders in Phase 1 and wired into MAIL/RCPT lines in Phase 3.
 */
public record SmtpEnvelope(String mailFrom, List<Recipient> recipients, String envid, RetMode ret) {

    public enum RetMode {
        DEFAULT,
        FULL,
        HDRS
    }

    public enum DsnNotify {
        SUCCESS,
        FAILURE,
        DELAY,
        NEVER
    }

    public record Recipient(String address, List<DsnNotify> notifyOn, String orcpt) {
        public Recipient {
            Objects.requireNonNull(address, "address");
            requireNoLineBreaks(address, "recipient address");
            requireNoLineBreaks(orcpt, "ORCPT");
            notifyOn = notifyOn == null ? List.of() : List.copyOf(notifyOn);
        }

        public static Recipient of(String address) {
            return new Recipient(address, List.of(), null);
        }
    }

    public SmtpEnvelope {
        Objects.requireNonNull(mailFrom, "mailFrom");
        Objects.requireNonNull(recipients, "recipients");
        requireNoLineBreaks(mailFrom, "MAIL FROM");
        requireNoLineBreaks(envid, "ENVID");
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("recipients must not be empty");
        }
        recipients = List.copyOf(recipients);
        ret = ret == null ? RetMode.DEFAULT : ret;
    }

    public static SmtpEnvelope of(String mailFrom, String... recipients) {
        var list = new ArrayList<Recipient>(recipients.length);
        for (var address : recipients) {
            list.add(Recipient.of(address));
        }
        return new SmtpEnvelope(mailFrom, list, null, RetMode.DEFAULT);
    }

    /**
     * Rejects CR, LF, and NUL in any value that is later written onto an SMTP command line. SMTP
     * commands are single lines terminated by CRLF (rfc5321 §4.1.1, §2.3.8), so an embedded line
     * break in an envelope address or DSN parameter would let extra commands be smuggled onto the
     * wire (SMTP command injection). {@code null} is permitted — these fields are optional.
     */
    static void requireNoLineBreaks(String value, String field) {
        if (value == null) {
            return;
        }
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character == '\r' || character == '\n' || character == '\0') {
                throw new IllegalArgumentException(
                        field + " must not contain a line break or NUL (SMTP command injection) at index " + index);
            }
        }
    }
}
