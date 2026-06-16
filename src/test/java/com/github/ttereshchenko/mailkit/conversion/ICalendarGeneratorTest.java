package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class ICalendarGeneratorTest {

    private static final Date START = new Date(1470150000000L); // 2016-08-02T15:00:00Z
    private static final Date END = new Date(1470151800000L); // 2016-08-02T15:30:00Z

    private static String generate(String method, List<ICalendarGenerator.Attendee> attendees) {
        return ICalendarGenerator.generate(
                method, START, END, "Room 1", "Subject", "Organizer", "org@example.com", "Description", attendees);
    }

    // F2: plain appointments are PUBLISHed; the old unconditional METHOD:REQUEST without ATTENDEEs
    // was invalid iTIP (RFC 5546 §3.2.2) and strict clients refused to render it.
    @Test
    void publishCarriesNoAttendees() {
        var ical = generate("PUBLISH", List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com")));
        assertTrue(ical.contains("METHOD:PUBLISH\r\n"), ical);
        assertFalse(ical.contains("ATTENDEE"), "PUBLISH must not carry attendees (RFC 5546 §3.2.1): " + ical);
        assertTrue(ical.contains("DTSTART:20160802T150000Z"), ical);
        assertTrue(ical.contains("DTEND:20160802T153000Z"), ical);
        assertTrue(ical.contains("ORGANIZER;CN=\"Organizer\":mailto:org@example.com"), ical);
        assertTrue(ical.contains("SUMMARY:Subject"), ical);
    }

    @Test
    void requestCarriesAttendees() {
        var ical = generate("REQUEST", List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com")));
        assertTrue(ical.contains("METHOD:REQUEST\r\n"), ical);
        assertTrue(ical.contains("ATTENDEE;CN=\"Bob\":mailto:bob@example.com"), ical);
    }

    // RFC 5546 §2.1.4/§3.2.4 + RFC 5545 §3.8.7.4: every scheduling object needs a SEQUENCE so a
    // CANCEL or updated REQUEST supersedes the original a client already holds. It defaults to 0 and
    // carries PidLidAppointmentSequence when present.
    @Test
    void sequenceDefaultsToZeroAndIsThreadedFromTheStore() {
        var defaulted = generate("REQUEST", List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com")));
        assertTrue(defaulted.contains("SEQUENCE:0\r\n"), "every VEVENT must carry a SEQUENCE: " + defaulted);

        var updated = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                "CANCEL",
                START,
                END,
                null,
                "Subject",
                "Org",
                "org@example.com",
                null,
                List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com")),
                false,
                null,
                null,
                3));
        assertTrue(updated.contains("METHOD:CANCEL\r\n"), updated);
        assertTrue(updated.contains("SEQUENCE:3\r\n"), "the stored sequence must be emitted: " + updated);
    }

    // RFC 5546 §3.2: a scheduling method requires a DTSTART. With no start time the object is
    // downgraded to PUBLISH rather than emitting an invalid METHOD:REQUEST without a DTSTART.
    @Test
    void schedulingMethodWithoutStartTimeDowngradesToPublish() {
        var ical = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                "REQUEST",
                null,
                null,
                null,
                "Subject",
                "Org",
                "org@example.com",
                null,
                List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com")),
                false,
                null,
                null));
        assertTrue(ical.contains("METHOD:PUBLISH\r\n"), "a start-less REQUEST must downgrade to PUBLISH: " + ical);
        assertFalse(ical.contains("DTSTART"), "no DTSTART is available to emit: " + ical);
        assertFalse(ical.contains("ATTENDEE"), "a downgraded PUBLISH must not carry attendees: " + ical);
    }

    @Test
    void nullEndOmitsDtendAndNullMethodDefaultsToPublish() {
        var ical = ICalendarGenerator.generate(null, START, null, null, "S", null, null, null, List.of());
        assertTrue(ical.contains("METHOD:PUBLISH\r\n"), ical);
        assertTrue(ical.contains("DTSTART:"), ical);
        assertFalse(ical.contains("DTEND:"), "an absent end time must not be fabricated: " + ical);
        assertFalse(ical.contains("ORGANIZER"), "no organizer line without an address: " + ical);
        assertFalse(ical.contains("LOCATION"), ical);
    }

    @Test
    void blankSubjectOmitsSummary() {
        var ical = ICalendarGenerator.generate("PUBLISH", START, END, null, "", null, null, null, List.of());
        assertFalse(ical.contains("SUMMARY"), "a blank subject must not fabricate a SUMMARY: " + ical);
    }

    // F7 regression: a double quote in the organizer name escaped the CN="…" quoting and produced
    // an unparsable property line.
    @Test
    void quoteInOrganizerNameCannotEscapeCnQuotes() {
        var ical = ICalendarGenerator.generate(
                "PUBLISH", START, END, null, "S", "Evil\"X", "org@example.com", null, List.of());
        assertTrue(ical.contains("CN=\"EvilX\""), "the DQUOTE must be stripped from the param value: " + ical);
    }

    // F7 regression: CR/LF in the organizer address used to be written raw after "mailto:",
    // letting a crafted store property inject arbitrary ICS content lines.
    @Test
    void crlfInOrganizerEmailCannotInjectContentLines() {
        var ical = ICalendarGenerator.generate(
                "PUBLISH", START, END, null, "S", "Org", "a@example.com\r\nATTENDEE:mailto:evil@x", null, List.of());
        for (var line : ical.split("\r\n")) {
            assertFalse(line.startsWith("ATTENDEE"), "injected content line: " + ical);
        }
    }

    // F6 regression: folding used to count chars and split anywhere, slicing surrogate pairs in
    // half at the fold point; RFC 5545 §3.1 measures 75 octets and a fold must never split a
    // code point.
    @Test
    void foldingCountsOctetsAndNeverSplitsCodePoints() {
        var emojiSubject = "📈".repeat(60); // 4 UTF-8 octets each — guarantees folds inside non-ASCII runs
        var ical = ICalendarGenerator.generate("PUBLISH", START, END, null, emojiSubject, null, null, null, List.of());

        for (var line : ical.split("\r\n")) {
            assertTrue(
                    line.getBytes(StandardCharsets.UTF_8).length <= 75,
                    "folded line exceeds 75 octets: " + line.getBytes(StandardCharsets.UTF_8).length);
            if (!line.isEmpty()) {
                assertFalse(Character.isLowSurrogate(line.charAt(0)), "fold split a surrogate pair");
                assertFalse(Character.isHighSurrogate(line.charAt(line.length() - 1)), "fold split a surrogate pair");
            }
        }
        // Unfolding (removing CRLF + space) must reproduce the full emoji run without corruption.
        var unfolded = ical.replace("\r\n ", "");
        assertTrue(unfolded.contains("SUMMARY:" + emojiSubject), "unfolded content must round-trip");
        assertEquals(-1, unfolded.indexOf('�'), "no replacement characters may appear");
    }

    // --- F5: all-day, time-zone-anchored and recurring events ---

    private static byte[] pacificStruct() {
        var buffer = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, 480); // bias: UTC-8
        buffer.putInt(8, -60); // daylight bias
        buffer.putShort(16, (short) 11); // standard: November
        buffer.putShort(20, (short) 1); //   1st Sunday
        buffer.putShort(22, (short) 2); //   02:00
        buffer.putShort(34, (short) 3); // daylight: March
        buffer.putShort(38, (short) 2); //   2nd Sunday
        buffer.putShort(40, (short) 2); //   02:00
        return buffer.array();
    }

    @Test
    void allDayEventsUseDateValues() {
        var ical = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                "PUBLISH",
                Date.from(Instant.parse("2016-08-02T00:00:00Z")),
                Date.from(Instant.parse("2016-08-03T00:00:00Z")),
                null,
                "Company holiday",
                null,
                null,
                null,
                List.of(),
                true,
                null,
                null));

        assertTrue(ical.contains("DTSTART;VALUE=DATE:20160802"), ical);
        assertTrue(ical.contains("DTEND;VALUE=DATE:20160803"), ical);
        assertFalse(ical.contains("VTIMEZONE"), "An all-day event needs no time zone");
    }

    @Test
    void timeZoneAnchoredRecurrenceKeepsLocalTimesAndEmitsExDates() {
        var timeZone = WindowsTimeZone.parse(pacificStruct());
        var recurrence = new AppointmentRecurrence.Pattern(
                "FREQ=WEEKLY;INTERVAL=1;WKST=SU;BYDAY=TU",
                LocalDate.of(2016, 12, 6),
                null,
                LocalDate.of(2016, 8, 2),
                List.of(LocalDate.of(2016, 8, 9)));
        var ical = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                "PUBLISH",
                Date.from(Instant.parse("2016-08-02T15:00:00Z")),
                Date.from(Instant.parse("2016-08-02T16:00:00Z")),
                null,
                "Weekly sync",
                null,
                null,
                null,
                List.of(),
                false,
                timeZone,
                recurrence));

        assertTrue(ical.contains("BEGIN:VTIMEZONE"), ical);
        assertTrue(ical.contains("DTSTART;TZID=" + timeZone.tzid() + ":20160802T080000"), ical);
        assertTrue(ical.contains("DTEND;TZID=" + timeZone.tzid() + ":20160802T090000"), ical);
        // RFC 5545 §3.3.10: UNTIL with a TZID-anchored DTSTART must be UTC. December 6th is PST
        // (UTC-8), so the 08:00 local start becomes 16:00Z.
        assertTrue(ical.contains("RRULE:FREQ=WEEKLY;INTERVAL=1;WKST=SU;BYDAY=TU;UNTIL=20161206T160000Z"), ical);
        assertTrue(ical.contains("EXDATE;TZID=" + timeZone.tzid() + ":20160809T080000"), ical);
    }

    @Test
    void recurrenceWithCountAndNoZoneStaysUtc() {
        var recurrence = new AppointmentRecurrence.Pattern(
                "FREQ=DAILY;INTERVAL=1", null, 5, LocalDate.of(2024, 1, 1), List.of());
        var ical = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                "PUBLISH",
                Date.from(Instant.parse("2024-01-01T09:00:00Z")),
                null,
                null,
                "Daily",
                null,
                null,
                null,
                List.of(),
                false,
                null,
                recurrence));

        assertTrue(ical.contains("DTSTART:20240101T090000Z"), ical);
        assertTrue(ical.contains("RRULE:FREQ=DAILY;INTERVAL=1;COUNT=5"), ical);
        assertFalse(ical.contains("VTIMEZONE"), ical);
    }

    @Test
    void todoCarriesDateOnlyDueDateCompletionAndEscaping() {
        // Outlook stores PidLidTaskStartDate / PidLidTaskDueDate ([MS-OXOTASK] §2.2.2.2.4–.5) as
        // midnight-UTC date-only values. rfc5545 §3.3.4 (DATE) vs §3.3.5 (DATE-TIME) and §3.8.2.3
        // (DUE) require those to be emitted as ;VALUE=DATE:yyyymmdd — rendering them as a
        // T000000Z DATE-TIME shifts the day one west of UTC for every reader east of Greenwich.
        var todo = ICalendarGenerator.generateTodo(
                "File; the, report",
                "Details line",
                Date.from(Instant.parse("2024-01-01T00:00:00Z")),
                Date.from(Instant.parse("2024-01-05T00:00:00Z")),
                0.75,
                false);

        assertTrue(todo.contains("BEGIN:VTODO"), todo);
        assertTrue(todo.contains("DTSTART;VALUE=DATE:20240101"), todo);
        assertTrue(todo.contains("DUE;VALUE=DATE:20240105"), todo);
        assertFalse(
                todo.contains("DTSTART:20240101T000000Z"), "date-only DTSTART must not be a UTC DATE-TIME: " + todo);
        assertFalse(todo.contains("DUE:20240105T000000Z"), "date-only DUE must not be a UTC DATE-TIME: " + todo);
        assertTrue(todo.contains("SUMMARY:File\\; the\\, report"), todo);
        assertTrue(todo.contains("PERCENT-COMPLETE:75"), todo);
        assertFalse(todo.contains("STATUS:COMPLETED"), todo);

        var completed = ICalendarGenerator.generateTodo("Done", null, null, null, 1.0, true);
        assertTrue(completed.contains("STATUS:COMPLETED"), completed);
        assertTrue(completed.contains("PERCENT-COMPLETE:100"), completed);
    }

    @Test
    void todoKeepsRealDateTimeAsUtc() {
        // A task date carrying a non-midnight time of day is a genuine date-time and must remain a
        // UTC DATE-TIME (rfc5545 §3.3.5), not be downgraded to a date.
        var todo = ICalendarGenerator.generateTodo(
                "Timed task",
                null,
                Date.from(Instant.parse("2024-01-01T08:30:00Z")),
                Date.from(Instant.parse("2024-01-05T17:15:00Z")),
                null,
                false);

        assertTrue(todo.contains("DTSTART:20240101T083000Z"), todo);
        assertTrue(todo.contains("DUE:20240105T171500Z"), todo);
        assertFalse(todo.contains("VALUE=DATE"), "a real date-time must not be emitted as a DATE: " + todo);
    }

    @Test
    void escapeIcalRepresentsEveryNewlineFormAsBackslashN() {
        // rfc5545 §3.3.11: a newline inside a TEXT value is escaped as \n and must never be dropped.
        // The pre-fix escaper deleted a lone CR (Mac-classic line break) outright, silently joining
        // the two lines; CRLF, a lone CR and a lone LF must all become the literal escape \n.
        var description = "alpha\r\nbeta\rgamma\ndelta";
        var todo = ICalendarGenerator.generateTodo("Subject", description, null, null, null, false);

        // Recover the (possibly folded) DESCRIPTION value and assert each break became a literal "\n".
        var unfolded = todo.replace("\r\n ", "");
        assertTrue(
                unfolded.contains("DESCRIPTION:alpha\\nbeta\\ngamma\\ndelta"),
                "every newline form must be escaped as \\n, none dropped: " + unfolded);
        // The pre-fix escaper deleted the lone CR, joining "beta" and "gamma" into "betagamma".
        assertFalse(
                unfolded.contains("betagamma"),
                "lone CR must not be deleted (which would join beta and gamma): " + unfolded);
    }

    // -----------------------------------------------------------------------
    // responsePartStat — maps meeting-response message classes to PARTSTAT
    // -----------------------------------------------------------------------

    @Test
    void responsePartStatReturnedForRespPos() {
        assertEquals(
                "ACCEPTED",
                ICalendarGenerator.responsePartStat("IPM.Schedule.Meeting.Resp.Pos"),
                "Resp.Pos must map to ACCEPTED");
    }

    @Test
    void responsePartStatReturnedForRespNeg() {
        assertEquals(
                "DECLINED",
                ICalendarGenerator.responsePartStat("IPM.Schedule.Meeting.Resp.Neg"),
                "Resp.Neg must map to DECLINED");
    }

    @Test
    void responsePartStatReturnedForRespTent() {
        assertEquals(
                "TENTATIVE",
                ICalendarGenerator.responsePartStat("IPM.Schedule.Meeting.Resp.Tent"),
                "Resp.Tent must map to TENTATIVE");
    }

    @Test
    void responsePartStatReturnsNullForNonResponseClass() {
        assertNull(
                ICalendarGenerator.responsePartStat("IPM.Schedule.Meeting.Request"),
                "a REQUEST class must not produce a PARTSTAT");
        assertNull(
                ICalendarGenerator.responsePartStat("IPM.Appointment"),
                "a plain appointment must not produce a PARTSTAT");
        assertNull(ICalendarGenerator.responsePartStat("IPM.Note"), "IPM.Note must not produce a PARTSTAT");
    }

    @Test
    void responsePartStatReturnsNullForNull() {
        assertNull(ICalendarGenerator.responsePartStat(null), "null message class must return null PARTSTAT");
    }

    @Test
    void responsePartStatReturnsNullForUnrecognisedRespSuffix() {
        // An unknown *.Resp.* variant returns null; the caller omits PARTSTAT and implies NEEDS-ACTION.
        assertNull(ICalendarGenerator.responsePartStat("IPM.Schedule.Meeting.Resp.Unknown"));
    }

    // -----------------------------------------------------------------------
    // generateTodo(method) — explicit iTIP METHOD line
    // -----------------------------------------------------------------------

    @Test
    void todoWithRequestMethodEmitsMethodRequest() {
        var todo = ICalendarGenerator.generateTodo("Assigned task", null, null, null, null, null, "REQUEST");

        assertTrue(todo.contains("METHOD:REQUEST\r\n"), "task request must emit METHOD:REQUEST: " + todo);
        assertTrue(todo.contains("BEGIN:VTODO"), todo);
    }

    @Test
    void todoWithReplyMethodEmitsMethodReply() {
        var todo = ICalendarGenerator.generateTodo("Task accepted", null, null, null, null, null, "REPLY");

        assertTrue(todo.contains("METHOD:REPLY\r\n"), "task response must emit METHOD:REPLY: " + todo);
    }

    @Test
    void todoWithPublishMethodEmitsMethodPublish() {
        var todo = ICalendarGenerator.generateTodo("Plain task", null, null, null, null, null, "PUBLISH");

        assertTrue(todo.contains("METHOD:PUBLISH\r\n"), "plain task must emit METHOD:PUBLISH: " + todo);
    }

    @Test
    void todoSixArgOverloadDefaultsToPublish() {
        // The 6-arg overload must still produce METHOD:PUBLISH so existing callers are not broken.
        var todo = ICalendarGenerator.generateTodo("Task", null, null, null, null, null);

        assertTrue(todo.contains("METHOD:PUBLISH\r\n"), "6-arg overload must default to PUBLISH: " + todo);
    }

    @Test
    void todoWithNullMethodDefaultsToPublish() {
        var todo = ICalendarGenerator.generateTodo("Task", null, null, null, null, null, (String) null);

        assertTrue(todo.contains("METHOD:PUBLISH\r\n"), "null method must default to PUBLISH: " + todo);
    }

    // -----------------------------------------------------------------------
    // Attendee.partStat — PARTSTAT parameter rendered on ATTENDEE lines
    // -----------------------------------------------------------------------

    @Test
    void attendeeWithPartStatRendersPartstatParameter() {
        var ical = generate("REPLY", List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com", "ACCEPTED")));

        assertTrue(
                ical.contains("ATTENDEE;CN=\"Bob\";PARTSTAT=ACCEPTED:mailto:bob@example.com"),
                "ATTENDEE with partStat must include ;PARTSTAT=: " + ical);
    }

    @Test
    void attendeeTwoArgConstructorOmitsPartstat() {
        var ical = generate("REQUEST", List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com")));

        assertTrue(ical.contains("ATTENDEE"), ical);
        assertFalse(
                ical.contains("PARTSTAT"),
                "2-arg Attendee constructor must omit ;PARTSTAT= (RFC 5545 §3.2.12 implies NEEDS-ACTION): " + ical);
    }

    @Test
    void attendeeWithNullPartStatOmitsPartstat() {
        var ical = generate("REQUEST", List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com", null)));

        assertFalse(ical.contains("PARTSTAT"), "null partStat must omit ;PARTSTAT=: " + ical);
    }

    @Test
    void attendeeDeclinedPartstatRendered() {
        var ical =
                generate("REPLY", List.of(new ICalendarGenerator.Attendee("Carol", "carol@example.com", "DECLINED")));

        assertTrue(ical.contains("PARTSTAT=DECLINED"), "DECLINED partStat must appear: " + ical);
    }

    @Test
    void attendeeTentativePartstatRendered() {
        var ical = generate("REPLY", List.of(new ICalendarGenerator.Attendee("Dave", "dave@example.com", "TENTATIVE")));

        assertTrue(ical.contains("PARTSTAT=TENTATIVE"), "TENTATIVE partStat must appear: " + ical);
    }
}
