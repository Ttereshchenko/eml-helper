package com.github.ttereshchenko.mailkit.conversion;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
     * Everything one VEVENT needs beyond the original nine arguments: the all-day flag
     * (PidLidAppointmentSubType), the event time zone (PidLidTimeZoneStruct) and the recurrence
     * (PidLidAppointmentRecur) — each optional.
     */
    public record EventDetails(
            String method,
            Date startTime,
            Date endTime,
            String location,
            String subject,
            String organizerName,
            String organizerEmail,
            String description,
            List<Attendee> attendees,
            boolean allDay,
            WindowsTimeZone timeZone,
            AppointmentRecurrence.Pattern recurrence) {}

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
        return generate(new EventDetails(
                method,
                startTime,
                endTime,
                location,
                subject,
                organizerName,
                organizerEmail,
                description,
                attendees,
                false,
                null,
                null));
    }

    /**
     * Builds a folded VCALENDAR/VEVENT document. Timed events with a known {@link WindowsTimeZone}
     * get a VTIMEZONE plus {@code TZID}-anchored local times, so a recurring event keeps its
     * wall-clock hour across DST changes; without one, times stay UTC (correct for single
     * occurrences, but a recurrence then drifts an hour across DST — unavoidable when the source
     * stores no zone). All-day events use {@code VALUE=DATE}. A recurrence emits {@code RRULE} plus
     * {@code EXDATE}s for deleted occurrences.
     */
    public static String generate(EventDetails event) {
        var utcFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        var safeMethod = event.method() == null || event.method().isBlank()
                ? "PUBLISH"
                : escapeParameterValue(event.method().trim()).toUpperCase(Locale.ROOT);
        String dtStamp = utcFormat.format(new Date());
        String uid = UUID.randomUUID().toString();

        // The zone only matters for timed events; all-day events are pure dates.
        var timeZone = event.allDay() ? null : event.timeZone();

        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n");
        builder.append("VERSION:2.0\r\n");
        builder.append("PRODID:-//MailKit//EN\r\n");
        builder.append("METHOD:").append(safeMethod).append("\r\n");
        if (timeZone != null) {
            builder.append(timeZone.toVTimeZone());
        }
        builder.append("BEGIN:VEVENT\r\n");
        builder.append("UID:").append(uid).append("\r\n");
        builder.append("DTSTAMP:").append(dtStamp).append("\r\n");
        if (event.startTime() != null) {
            appendEventDate(builder, "DTSTART", event.startTime(), event.allDay(), timeZone, utcFormat);
        }
        if (event.endTime() != null) {
            appendEventDate(builder, "DTEND", event.endTime(), event.allDay(), timeZone, utcFormat);
        }
        if (event.recurrence() != null && event.startTime() != null) {
            appendRecurrence(builder, event.recurrence(), event.startTime(), event.allDay(), timeZone, utcFormat);
        }

        appendParticipant(builder, "ORGANIZER", event.organizerName(), event.organizerEmail());
        if (event.attendees() != null && !"PUBLISH".equals(safeMethod)) {
            for (var attendee : event.attendees()) {
                appendParticipant(builder, "ATTENDEE", attendee.name(), attendee.email());
            }
        }

        if (event.location() != null && !event.location().isBlank()) {
            builder.append("LOCATION:").append(escapeIcal(event.location())).append("\r\n");
        }
        if (event.subject() != null && !event.subject().isBlank()) {
            builder.append("SUMMARY:").append(escapeIcal(event.subject())).append("\r\n");
        }
        if (event.description() != null && !event.description().isBlank()) {
            builder.append("DESCRIPTION:")
                    .append(escapeIcal(event.description()))
                    .append("\r\n");
        }

        builder.append("END:VEVENT\r\n");
        builder.append("END:VCALENDAR\r\n");

        return foldLines(builder.toString());
    }

    /**
     * Builds a folded VCALENDAR/VTODO document for a task ({@code IPM.Task}). All fields except the
     * subject may be {@code null}; {@code percentComplete} is clamped to 0–100.
     */
    public static String generateTodo(
            String subject,
            String description,
            Date startDate,
            Date dueDate,
            Double percentComplete,
            Boolean complete) {
        var utcFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        var todo = new StringBuilder();
        todo.append("BEGIN:VCALENDAR\r\n");
        todo.append("VERSION:2.0\r\n");
        todo.append("PRODID:-//MailKit//EN\r\n");
        todo.append("METHOD:PUBLISH\r\n");
        todo.append("BEGIN:VTODO\r\n");
        todo.append("UID:").append(UUID.randomUUID()).append("\r\n");
        todo.append("DTSTAMP:").append(utcFormat.format(new Date())).append("\r\n");
        if (startDate != null) {
            todo.append("DTSTART:").append(utcFormat.format(startDate)).append("\r\n");
        }
        if (dueDate != null) {
            todo.append("DUE:").append(utcFormat.format(dueDate)).append("\r\n");
        }
        if (subject != null && !subject.isBlank()) {
            todo.append("SUMMARY:").append(escapeIcal(subject)).append("\r\n");
        }
        if (description != null && !description.isBlank()) {
            todo.append("DESCRIPTION:").append(escapeIcal(description)).append("\r\n");
        }
        if (Boolean.TRUE.equals(complete)) {
            todo.append("STATUS:COMPLETED\r\n");
        }
        if (percentComplete != null) {
            var percent = (int) Math.round(Math.clamp(percentComplete * 100, 0, 100));
            todo.append("PERCENT-COMPLETE:").append(percent).append("\r\n");
        }
        todo.append("END:VTODO\r\n");
        todo.append("END:VCALENDAR\r\n");
        return foldLines(todo.toString());
    }

    /** One DTSTART/DTEND line: {@code VALUE=DATE} for all-day, TZID-local or UTC for timed events. */
    private static void appendEventDate(
            StringBuilder builder,
            String property,
            Date value,
            boolean allDay,
            WindowsTimeZone timeZone,
            SimpleDateFormat utcFormat) {
        if (allDay) {
            builder.append(property)
                    .append(";VALUE=DATE:")
                    .append(DATE_ONLY.format(localDateTime(value, timeZone).toLocalDate()))
                    .append("\r\n");
        } else if (timeZone != null) {
            builder.append(property)
                    .append(";TZID=")
                    .append(WindowsTimeZone.TZID)
                    .append(':')
                    .append(LOCAL_DATE_TIME.format(timeZone.toLocal(value.toInstant())))
                    .append("\r\n");
        } else {
            builder.append(property).append(':').append(utcFormat.format(value)).append("\r\n");
        }
    }

    /** The RRULE (core + UNTIL/COUNT) and, when occurrences were deleted, one EXDATE line. */
    private static void appendRecurrence(
            StringBuilder builder,
            AppointmentRecurrence.Pattern recurrence,
            Date startTime,
            boolean allDay,
            WindowsTimeZone timeZone,
            SimpleDateFormat utcFormat) {
        var localStart = localDateTime(startTime, timeZone);
        builder.append("RRULE:").append(recurrence.coreRule());
        if (recurrence.count() != null) {
            builder.append(";COUNT=").append(recurrence.count());
        } else if (recurrence.until() != null) {
            if (allDay) {
                builder.append(";UNTIL=").append(DATE_ONLY.format(recurrence.until()));
            } else {
                // RFC 5545 §3.3.10: with a TZID-anchored DTSTART, UNTIL must be in UTC.
                var untilLocal = recurrence.until().atTime(localStart.toLocalTime());
                var untilUtc = timeZone != null ? timeZone.toInstant(untilLocal) : untilLocal.toInstant(ZoneOffset.UTC);
                builder.append(";UNTIL=").append(utcFormat.format(Date.from(untilUtc)));
            }
        }
        builder.append("\r\n");

        if (!recurrence.deletedInstanceDates().isEmpty()) {
            var values = new ArrayList<String>();
            for (var deletedDate : recurrence.deletedInstanceDates()) {
                if (allDay) {
                    values.add(DATE_ONLY.format(deletedDate));
                } else if (timeZone != null) {
                    values.add(LOCAL_DATE_TIME.format(deletedDate.atTime(localStart.toLocalTime())));
                } else {
                    values.add(utcFormat.format(Date.from(
                            deletedDate.atTime(localStart.toLocalTime()).toInstant(ZoneOffset.UTC))));
                }
            }
            builder.append("EXDATE");
            if (allDay) {
                builder.append(";VALUE=DATE");
            } else if (timeZone != null) {
                builder.append(";TZID=").append(WindowsTimeZone.TZID);
            }
            builder.append(':').append(String.join(",", values)).append("\r\n");
        }
    }

    /** The event-local wall-clock time of {@code value}: zone-local when known, otherwise UTC. */
    private static LocalDateTime localDateTime(Date value, WindowsTimeZone timeZone) {
        return timeZone != null
                ? timeZone.toLocal(value.toInstant())
                : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

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
