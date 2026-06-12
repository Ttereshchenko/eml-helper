package com.github.ttereshchenko.mailkit.conversion;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Generates RFC 5545 iCalendar payloads for calendar items extracted from Outlook stores.
 *
 * <p>The caller chooses the iTIP method (RFC 5546): {@code PUBLISH} for plain appointments,
 * {@code REQUEST}/{@code CANCEL}/{@code REPLY} for meeting messages. {@code REQUEST} (and the other
 * scheduling methods) are only valid with at least one {@code ATTENDEE}; callers enforce that by
 * downgrading to {@code PUBLISH} when no attendee is available, and {@code PUBLISH} itself must
 * carry none, so {@code attendees} is ignored for it.
 */
public final class ICalendarGenerator {

    /** A meeting participant exported as an {@code ATTENDEE} property. */
    public record Attendee(String name, String email) {}

    private ICalendarGenerator() {}

    /**
     * Builds a folded VCALENDAR/VEVENT document. {@code startTime} is expected to be non-null (a
     * caller that has no real start time should not emit an invite at all rather than fabricate
     * one); when it is null the DTSTART line is omitted. A null {@code endTime} omits DTEND.
     */
    public static String generate(
            String method,
            Date startTime,
            Date endTime,
            String location,
            String subject,
            String organizerName,
            String organizerEmail,
            String description,
            List<Attendee> attendees) {

        var dateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        var safeMethod = method == null || method.isBlank()
                ? "PUBLISH"
                : escapeParameterValue(method.trim()).toUpperCase(Locale.ROOT);
        String dtStamp = dateFormat.format(new Date());
        String uid = UUID.randomUUID().toString();

        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n");
        builder.append("VERSION:2.0\r\n");
        builder.append("PRODID:-//MailKit//EN\r\n");
        builder.append("METHOD:").append(safeMethod).append("\r\n");
        builder.append("BEGIN:VEVENT\r\n");
        builder.append("UID:").append(uid).append("\r\n");
        builder.append("DTSTAMP:").append(dtStamp).append("\r\n");
        if (startTime != null) {
            builder.append("DTSTART:").append(dateFormat.format(startTime)).append("\r\n");
        }
        if (endTime != null) {
            builder.append("DTEND:").append(dateFormat.format(endTime)).append("\r\n");
        }

        appendParticipant(builder, "ORGANIZER", organizerName, organizerEmail);
        if (attendees != null && !"PUBLISH".equals(safeMethod)) {
            for (var attendee : attendees) {
                appendParticipant(builder, "ATTENDEE", attendee.name(), attendee.email());
            }
        }

        if (location != null && !location.isBlank()) {
            builder.append("LOCATION:").append(escapeIcal(location)).append("\r\n");
        }
        if (subject != null && !subject.isBlank()) {
            builder.append("SUMMARY:").append(escapeIcal(subject)).append("\r\n");
        }
        if (description != null && !description.isBlank()) {
            builder.append("DESCRIPTION:").append(escapeIcal(description)).append("\r\n");
        }

        builder.append("END:VEVENT\r\n");
        builder.append("END:VCALENDAR\r\n");

        return foldLines(builder.toString());
    }

    /**
     * Appends an {@code ORGANIZER}/{@code ATTENDEE} line with an optional quoted {@code CN=}
     * parameter. Both values come from attacker-controlled store properties, so the cal-address is
     * stripped of anything that could terminate the line and the CN of anything that could escape
     * its quotes.
     */
    private static void appendParticipant(StringBuilder builder, String property, String name, String email) {
        var safeEmail = sanitizeCalAddress(email);
        if (safeEmail.isEmpty()) {
            return;
        }
        builder.append(property);
        if (name != null && !name.isBlank()) {
            builder.append(";CN=\"").append(escapeParameterValue(name)).append('"');
        }
        builder.append(":mailto:").append(safeEmail).append("\r\n");
    }

    /**
     * Strips DQUOTE and control characters from a parameter value: RFC 5545 §3.2 forbids both
     * inside a quoted param-value, and a raw {@code "} would otherwise escape the quotes
     * {@link #appendParticipant} supplies (TEXT-style backslash escapes do not apply to
     * parameters).
     */
    private static String escapeParameterValue(String value) {
        var builder = new StringBuilder(value.length());
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character != '"' && character >= 0x20 && character != 0x7F) {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    /**
     * Strips control characters and whitespace from a cal-address: CR/LF would split the property
     * line (ICS content-line injection) and a mailto URI can never legitimately contain either.
     */
    private static String sanitizeCalAddress(String email) {
        if (email == null) {
            return "";
        }
        var builder = new StringBuilder(email.length());
        for (var index = 0; index < email.length(); index++) {
            var character = email.charAt(index);
            if (character > 0x20 && character != 0x7F && character != '"') {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private static String escapeIcal(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    /**
     * Folds content lines at 75 octets of UTF-8 (RFC 5545 §3.1 measures octets, not chars), never
     * splitting inside a code point — a fold between the halves of a surrogate pair would turn an
     * emoji into two unencodable lone surrogates.
     */
    private static String foldLines(String ical) {
        StringBuilder result = new StringBuilder();
        for (String line : ical.split("\r\n")) {
            appendFolded(result, line);
        }
        return result.toString();
    }

    private static void appendFolded(StringBuilder result, String line) {
        var octets = 0;
        var index = 0;
        while (index < line.length()) {
            var codePoint = line.codePointAt(index);
            var width = utf8Width(codePoint);
            if (octets + width > 75) {
                result.append("\r\n ");
                octets = 1; // the folding space counts toward the continuation line's 75 octets
            }
            result.appendCodePoint(codePoint);
            octets += width;
            index += Character.charCount(codePoint);
        }
        result.append("\r\n");
    }

    private static int utf8Width(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }
}
