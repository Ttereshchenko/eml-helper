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

    /**
     * A meeting participant exported as an {@code ATTENDEE} property. {@code partStat} is the iTIP
     * participation status (RFC 5545 §3.2.12) — {@code ACCEPTED}/{@code DECLINED}/{@code TENTATIVE}
     * on a meeting-response REPLY, or {@code null} when unknown (NEEDS-ACTION is then implied).
     */
    public record Attendee(String name, String email, String partStat) {
        /** A participant whose response status is unknown ({@code PARTSTAT} omitted). */
        public Attendee(String name, String email) {
            this(name, email, null);
        }
    }

    private ICalendarGenerator() {}

    /**
     * Everything one VEVENT needs beyond the original nine arguments: the all-day flag
     * (PidLidAppointmentSubType), the event time zone (PidLidTimeZoneStruct), the recurrence
     * (PidLidAppointmentRecur) and the iTIP revision number (PidLidAppointmentSequence) — each
     * optional. {@code sequence} is the RFC 5546 {@code SEQUENCE} (RFC 5545 §3.8.7.4): a REPLY must
     * echo the request's value and a CANCEL/updated REQUEST must carry a higher value than the
     * original, or a client that already holds the event ignores the update; it defaults to 0.
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
            AppointmentRecurrence.Pattern recurrence,
            int sequence) {

        /** An event with no explicit iTIP revision number ({@code SEQUENCE:0}). */
        public EventDetails(
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
                AppointmentRecurrence.Pattern recurrence) {
            this(
                    method,
                    startTime,
                    endTime,
                    location,
                    subject,
                    organizerName,
                    organizerEmail,
                    description,
                    attendees,
                    allDay,
                    timeZone,
                    recurrence,
                    0);
        }
    }

    /**
     * The iTIP method (RFC 5546) matching a MAPI calendar message class: meeting requests,
     * cancellations and responses map to REQUEST/CANCEL/REPLY; a plain calendar item — or any
     * meeting message without attendees — is published as-is.
     */
    public static String method(String messageClass, boolean hasAttendees) {
        if (!hasAttendees || !messageClass.startsWith("IPM.Schedule.Meeting")) {
            return "PUBLISH";
        }
        if (messageClass.startsWith("IPM.Schedule.Meeting.Canceled")) {
            return "CANCEL";
        }
        if (messageClass.startsWith("IPM.Schedule.Meeting.Resp")) {
            return "REPLY";
        }
        return "REQUEST";
    }

    /**
     * The iTIP {@code PARTSTAT} (RFC 5545 §3.2.12) a meeting-response message class conveys:
     * {@code IPM.Schedule.Meeting.Resp.Pos}→ACCEPTED, {@code .Neg}→DECLINED, {@code .Tent}→TENTATIVE.
     * Returns {@code null} for anything that is not a recognised response (NEEDS-ACTION is implied).
     */
    public static String responsePartStat(String messageClass) {
        if (messageClass == null || !messageClass.startsWith("IPM.Schedule.Meeting.Resp")) {
            return null;
        }
        if (messageClass.startsWith("IPM.Schedule.Meeting.Resp.Pos")) {
            return "ACCEPTED";
        }
        if (messageClass.startsWith("IPM.Schedule.Meeting.Resp.Neg")) {
            return "DECLINED";
        }
        if (messageClass.startsWith("IPM.Schedule.Meeting.Resp.Tent")) {
            return "TENTATIVE";
        }
        return null;
    }

    /**
     * The iTIP {@code PARTSTAT} (RFC 5545 §3.2.12) a task-response message class conveys:
     * {@code IPM.TaskRequest.Accept}→ACCEPTED, {@code IPM.TaskRequest.Decline}→DECLINED. Returns
     * {@code null} for a status update or anything unrecognised (NEEDS-ACTION is then implied).
     */
    public static String taskResponsePartStat(String messageClass) {
        if (messageClass == null) {
            return null;
        }
        if (messageClass.startsWith("IPM.TaskRequest.Accept")) {
            return "ACCEPTED";
        }
        if (messageClass.startsWith("IPM.TaskRequest.Decline")) {
            return "DECLINED";
        }
        return null;
    }

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
     * The iTIP {@code METHOD} {@link #generate(EventDetails)} will actually emit for {@code event}: a
     * scheduling method (REQUEST/REPLY/CANCEL) requires both a DTSTART and a resolvable ORGANIZER
     * (RFC 5546 §3.2), and downgrades to {@code PUBLISH} otherwise. A caller stamping the
     * {@code text/calendar; method=} parameter (rfc6047 §2.4) must use this, not the requested method,
     * so the MIME parameter and the body {@code METHOD} agree.
     */
    public static String effectiveMethod(EventDetails event) {
        var requestedMethod = event.method() == null || event.method().isBlank()
                ? "PUBLISH"
                : escapeParameterValue(event.method().trim()).toUpperCase(Locale.ROOT);
        var hasOrganizer = !sanitizeCalAddress(event.organizerEmail()).isEmpty();
        return "PUBLISH".equals(requestedMethod) || (event.startTime() != null && hasOrganizer)
                ? requestedMethod
                : "PUBLISH";
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

        // RFC 5546 §3.2: a scheduling object (REQUEST/REPLY/CANCEL) requires both a DTSTART and an
        // ORGANIZER, else it downgrades to PUBLISH (which also correctly drops the ATTENDEE list a
        // publication must not carry). effectiveMethod() is the single source of truth, so the body
        // METHOD and the caller's text/calendar; method= parameter (rfc6047 §2.4) cannot disagree.
        var safeMethod = effectiveMethod(event);
        String dtStamp = utcFormat.format(new Date());
        String uid = UUID.randomUUID().toString();

        // The zone only matters for timed events; all-day events are pure dates. (Real Outlook all-day
        // appointments — see MsgSampleCorpusTest — store a start whose UTC calendar date is already the
        // intended day, so re-interpreting it through the event zone shifts the date by one and is wrong.)
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
        builder.append("SEQUENCE:").append(Math.max(0, event.sequence())).append("\r\n");
        if (event.startTime() != null) {
            appendEventDate(builder, "DTSTART", event.startTime(), event.allDay(), timeZone, utcFormat);
        }
        if (event.endTime() != null) {
            appendEventDate(builder, "DTEND", event.endTime(), event.allDay(), timeZone, utcFormat);
        }
        if (event.recurrence() != null && event.startTime() != null) {
            appendRecurrence(builder, event.recurrence(), event.startTime(), event.allDay(), timeZone, utcFormat);
        }

        appendParticipant(builder, "ORGANIZER", event.organizerName(), event.organizerEmail(), null);
        if (event.attendees() != null && !"PUBLISH".equals(safeMethod)) {
            for (var attendee : event.attendees()) {
                appendParticipant(builder, "ATTENDEE", attendee.name(), attendee.email(), attendee.partStat());
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
     * Builds a folded VCALENDAR/VTODO document for a task ({@code IPM.Task}), published as a plain
     * calendar object. All fields except the subject may be {@code null}; {@code percentComplete} is
     * clamped to 0–100.
     */
    public static String generateTodo(
            String subject,
            String description,
            Date startDate,
            Date dueDate,
            Double percentComplete,
            Boolean complete) {
        return generateTodo(subject, description, startDate, dueDate, percentComplete, complete, "PUBLISH");
    }

    /**
     * Builds a folded VCALENDAR/VTODO document with an explicit iTIP {@code METHOD} (RFC 5546 §3.4) but
     * no participants — so a {@code REQUEST}/{@code REPLY} downgrades to {@code PUBLISH} (a scheduling
     * VTODO requires an ORGANIZER and ATTENDEE). Prefer the participant-aware overload for an assigned
     * task; this one suits a plain task. All fields except the subject may be {@code null}.
     */
    public static String generateTodo(
            String subject,
            String description,
            Date startDate,
            Date dueDate,
            Double percentComplete,
            Boolean complete,
            String method) {
        return generateTodo(
                subject, description, startDate, dueDate, percentComplete, complete, method, null, null, null);
    }

    /**
     * The iTIP {@code METHOD} a VTODO will actually carry (RFC 5546 §3.4): a scheduling method
     * ({@code REQUEST}/{@code REPLY}) needs a resolvable ORGANIZER and at least one ATTENDEE, and
     * downgrades to {@code PUBLISH} otherwise. A caller stamping {@code text/calendar; method=}
     * (rfc6047 §2.4) must use this, not the requested method, so the parameter and the body agree.
     */
    public static String effectiveTodoMethod(String method, String organizerEmail, List<Attendee> attendees) {
        var requested = method == null || method.isBlank()
                ? "PUBLISH"
                : escapeParameterValue(method.trim()).toUpperCase(Locale.ROOT);
        if ("PUBLISH".equals(requested)) {
            return "PUBLISH";
        }
        var hasOrganizer = !sanitizeCalAddress(organizerEmail).isEmpty();
        var hasAttendee = attendees != null
                && attendees.stream()
                        .anyMatch(attendee ->
                                !sanitizeCalAddress(attendee.email()).isEmpty());
        return hasOrganizer && hasAttendee ? requested : "PUBLISH";
    }

    /**
     * Builds a folded VCALENDAR/VTODO document with an explicit iTIP {@code METHOD} (RFC 5546 §3.4):
     * {@code REQUEST} for an assigned task request ({@code IPM.TaskRequest}), {@code REPLY} for its
     * accept/decline/update responses, or {@code PUBLISH} for a plain task. A scheduling method emits the
     * ORGANIZER (the assigner) and ATTENDEE(s) (the assignee[s]); when those cannot be resolved it
     * downgrades to {@code PUBLISH} rather than emit an invalid scheduling object (see
     * {@link #effectiveTodoMethod}). All fields except the subject may be {@code null};
     * {@code percentComplete} is clamped to 0–100.
     */
    public static String generateTodo(
            String subject,
            String description,
            Date startDate,
            Date dueDate,
            Double percentComplete,
            Boolean complete,
            String method,
            String organizerName,
            String organizerEmail,
            List<Attendee> attendees) {
        var utcFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        utcFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        var safeMethod = effectiveTodoMethod(method, organizerEmail, attendees);

        var todo = new StringBuilder();
        todo.append("BEGIN:VCALENDAR\r\n");
        todo.append("VERSION:2.0\r\n");
        todo.append("PRODID:-//MailKit//EN\r\n");
        todo.append("METHOD:").append(safeMethod).append("\r\n");
        todo.append("BEGIN:VTODO\r\n");
        todo.append("UID:").append(UUID.randomUUID()).append("\r\n");
        todo.append("DTSTAMP:").append(utcFormat.format(new Date())).append("\r\n");
        todo.append("SEQUENCE:0\r\n");
        if (startDate != null) {
            appendTaskDate(todo, "DTSTART", startDate, utcFormat);
        }
        if (dueDate != null) {
            appendTaskDate(todo, "DUE", dueDate, utcFormat);
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
        // RFC 5546 §3.4: a scheduling VTODO carries an ORGANIZER and ATTENDEE(s); effectiveTodoMethod has
        // already downgraded to PUBLISH when those are absent. Emit the ORGANIZER whenever it resolves and
        // the ATTENDEE list only for an actual scheduling method (a PUBLISH must not carry attendees).
        appendParticipant(todo, "ORGANIZER", organizerName, organizerEmail, null);
        if (attendees != null && !"PUBLISH".equals(safeMethod)) {
            for (var attendee : attendees) {
                appendParticipant(todo, "ATTENDEE", attendee.name(), attendee.email(), attendee.partStat());
            }
        }
        todo.append("END:VTODO\r\n");
        todo.append("END:VCALENDAR\r\n");
        return foldLines(todo.toString());
    }

    /**
     * Emits a VTODO {@code DTSTART}/{@code DUE} line, distinguishing a date-only value from a real
     * date-time. Outlook stores task dates ({@code PidLidTaskStartDate}/{@code PidLidTaskDueDate},
     * [MS-OXOTASK] §2.2.2.2.4–.5) as midnight-UTC date-only values; rendering those as a
     * {@code yyyymmddT000000Z} DATE-TIME shifts the day one west of UTC for every reader east of the
     * prime meridian. Per rfc5545 §3.3.4 (DATE) vs §3.3.5 (DATE-TIME) and §3.8.2.3 (DUE), a date-only
     * value is emitted as {@code ;VALUE=DATE:yyyymmdd}; a value carrying a non-midnight time of day is
     * a genuine date-time and stays a UTC DATE-TIME.
     */
    private static void appendTaskDate(StringBuilder todo, String property, Date value, SimpleDateFormat utcFormat) {
        if (isDateOnly(value)) {
            var date =
                    LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC).toLocalDate();
            todo.append(property)
                    .append(";VALUE=DATE:")
                    .append(DATE_ONLY.format(date))
                    .append("\r\n");
        } else {
            todo.append(property).append(':').append(utcFormat.format(value)).append("\r\n");
        }
    }

    /**
     * True when {@code value} falls on a UTC midnight boundary. Its only callers pass
     * {@code PidLidTaskStartDate}/{@code PidLidTaskDueDate}, which [MS-OXOTASK] §2.2.2.2.4–.5 stores as
     * date-only midnight-UTC values, so a midnight result here is unambiguously a date-only task date
     * rather than a task that merely happens to be timed for 00:00 UTC (tasks carry no time of day).
     */
    private static boolean isDateOnly(Date value) {
        return value.toInstant().toEpochMilli() % 86_400_000L == 0L;
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
                    .append(timeZone.tzid())
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
                builder.append(";TZID=").append(timeZone.tzid());
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
     * parameter and an optional {@code PARTSTAT=} (RFC 5545 §3.2.12). Both values come from
     * attacker-controlled store properties, so the cal-address is stripped of anything that could
     * terminate the line and the CN/PARTSTAT of anything that could escape their parameter values.
     */
    private static void appendParticipant(
            StringBuilder builder, String property, String name, String email, String partStat) {
        var safeEmail = sanitizeCalAddress(email);
        if (safeEmail.isEmpty()) {
            return;
        }
        builder.append(property);
        if (name != null && !name.isBlank()) {
            builder.append(";CN=\"").append(escapeParameterValue(name)).append('"');
        }
        if (partStat != null && !partStat.isBlank()) {
            builder.append(";PARTSTAT=").append(escapeUnquotedParameterValue(partStat));
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
     * Like {@link #escapeParameterValue} but for an <em>unquoted</em> param-value: additionally drops
     * {@code ;}, {@code :} and {@code ,}, each of which would otherwise start another parameter or
     * terminate the parameter section (RFC 5545 §3.1). {@code PARTSTAT} is emitted unquoted, so its
     * value must not carry these.
     */
    private static String escapeUnquotedParameterValue(String value) {
        var builder = new StringBuilder(value.length());
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character != '"'
                    && character >= 0x20
                    && character != 0x7F
                    && character != ';'
                    && character != ':'
                    && character != ',') {
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

    /**
     * Escapes an iCalendar TEXT value (rfc5545 §3.3.11): backslash, semicolon and comma are
     * backslash-escaped and every newline becomes a literal {@code \n}. A line break in TEXT must be
     * represented, never dropped, so CRLF, a lone CR and a lone LF all map to {@code \n} (matching the
     * vCard escaper) rather than silently deleting a stray CR.
     */
    private static String escapeIcal(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\r", "\\n")
                .replace("\n", "\\n");
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
