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
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
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

    // RFC 5546 §3.2.5: a cancelled meeting must carry STATUS:CANCELLED in the VEVENT so a non-iTIP
    // consumer that imports the .ics as a plain file (ignoring the scheduling METHOD) still sees the
    // cancellation. It is gated on an effective CANCEL, so a REQUEST/REPLY/PUBLISH invite never gains it.
    @Test
    void cancelCarriesCancelledStatusButOtherMethodsDoNot() {
        var cancelled = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
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
        assertTrue(cancelled.contains("METHOD:CANCEL\r\n"), cancelled);
        assertTrue(
                cancelled.contains("STATUS:CANCELLED\r\n"),
                "a CANCEL VEVENT must carry STATUS:CANCELLED (RFC 5546 §3.2.5): " + cancelled);

        var request = generate("REQUEST", List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com")));
        assertTrue(request.contains("METHOD:REQUEST\r\n"), request);
        assertFalse(
                request.contains("STATUS:CANCELLED"), "a non-cancel invite must not gain STATUS:CANCELLED: " + request);
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
        // 75% with the completion flag clear is in progress (RFC 5545 §3.8.1.11), not completed.
        assertTrue(todo.contains("STATUS:IN-PROCESS"), todo);

        var completed = ICalendarGenerator.generateTodo("Done", null, null, null, 1.0, true);
        assertTrue(completed.contains("STATUS:COMPLETED"), completed);
        assertTrue(completed.contains("PERCENT-COMPLETE:100"), completed);
    }

    @Test
    void todoStatusReflectsProgressAndRecordsCompletionDate() {
        // RFC 5545 §3.8.1.11: a VTODO carries a tri-state STATUS derived from PidLidTaskComplete /
        // PidLidPercentComplete — not-started is NEEDS-ACTION, partial progress IN-PROCESS, finished
        // COMPLETED — so the in-progress/not-started distinction survives, not only completion.
        var notStarted = ICalendarGenerator.generateTodo("Todo", null, null, null, 0.0, false);
        assertTrue(notStarted.contains("STATUS:NEEDS-ACTION"), notStarted);
        assertFalse(notStarted.contains("STATUS:IN-PROCESS"), notStarted);

        var noPercent = ICalendarGenerator.generateTodo("Todo", null, null, null, null, false);
        assertTrue(noPercent.contains("STATUS:NEEDS-ACTION"), noPercent);

        var inProgress = ICalendarGenerator.generateTodo("Todo", null, null, null, 0.4, false);
        assertTrue(inProgress.contains("STATUS:IN-PROCESS"), inProgress);

        // 100% counts as completed even when the explicit completion flag is clear.
        var fullPercent = ICalendarGenerator.generateTodo("Todo", null, null, null, 1.0, false);
        assertTrue(fullPercent.contains("STATUS:COMPLETED"), fullPercent);

        // RFC 5545 §3.8.2.1: COMPLETED is a UTC date-time (never a VALUE=DATE), emitted only when the
        // completion time (PidLidTaskDateCompleted) is known.
        var withDate = ICalendarGenerator.generateTodo(
                "Done",
                null,
                null,
                null,
                1.0,
                true,
                Date.from(Instant.parse("2024-03-04T12:30:00Z")),
                "PUBLISH",
                null,
                null,
                List.of());
        assertTrue(withDate.contains("STATUS:COMPLETED"), withDate);
        assertTrue(withDate.contains("COMPLETED:20240304T123000Z"), withDate);
        assertFalse(withDate.contains("COMPLETED;VALUE=DATE"), "COMPLETED must be a UTC date-time: " + withDate);

        // A completed task with no recorded completion time keeps STATUS:COMPLETED but no COMPLETED line.
        var noDate = ICalendarGenerator.generateTodo("Done", null, null, null, null, true);
        assertTrue(noDate.contains("STATUS:COMPLETED"), noDate);
        assertFalse(noDate.contains("\r\nCOMPLETED:"), noDate);
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

    @Test
    void escapeIcalDropsForbiddenControlCharacters() {
        // rfc5545 §3.3.11: a TEXT value may not contain C0 controls other than TAB (and the CR/LF that
        // become the \n escape). A stray control — here a form feed and a backspace — must be dropped, not
        // emitted raw, while a TAB (a valid WSP) is preserved.
        var description = "Room\f1\tkeep\bdrop";
        var todo = ICalendarGenerator.generateTodo("Subject", description, null, null, null, false);

        var unfolded = todo.replace("\r\n ", "");
        assertTrue(unfolded.contains("DESCRIPTION:Room1\tkeepdrop"), "controls dropped, TAB kept: " + unfolded);
        assertFalse(unfolded.contains("\f"), "no form feed in output: " + unfolded);
        assertFalse(unfolded.contains("\b"), "no backspace in output: " + unfolded);
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
    void todoWithRequestMethodEmitsMethodRequestAndParticipants() {
        var todo = ICalendarGenerator.generateTodo(
                "Assigned task",
                null,
                null,
                null,
                null,
                null,
                null,
                "REQUEST",
                "Boss",
                "boss@example.com",
                List.of(new ICalendarGenerator.Attendee("Worker", "worker@example.com")));

        assertTrue(todo.contains("METHOD:REQUEST\r\n"), "task request must emit METHOD:REQUEST: " + todo);
        assertTrue(todo.contains("BEGIN:VTODO"), todo);
        assertTrue(
                todo.contains("ORGANIZER") && todo.contains("mailto:boss@example.com"),
                "a REQUEST VTODO must carry an ORGANIZER (RFC 5546 §3.4): " + todo);
        assertTrue(
                todo.contains("ATTENDEE") && todo.contains("mailto:worker@example.com"),
                "a REQUEST VTODO must carry an ATTENDEE (RFC 5546 §3.4): " + todo);
    }

    @Test
    void todoWithReplyMethodEmitsMethodReplyAndPartStat() {
        var todo = ICalendarGenerator.generateTodo(
                "Task accepted",
                null,
                null,
                null,
                null,
                null,
                null,
                "REPLY",
                "Boss",
                "boss@example.com",
                List.of(new ICalendarGenerator.Attendee("Worker", "worker@example.com", "ACCEPTED")));

        assertTrue(todo.contains("METHOD:REPLY\r\n"), "task response must emit METHOD:REPLY: " + todo);
        assertTrue(todo.contains("PARTSTAT=ACCEPTED"), "a REPLY VTODO must carry the responder's PARTSTAT: " + todo);
    }

    @Test
    void todoSchedulingMethodWithoutParticipantsDowngradesToPublish() {
        // RFC 5546 §3.4: a REQUEST/REPLY VTODO needs an ORGANIZER and ATTENDEE; without them the
        // participant-free overload must downgrade rather than emit an invalid scheduling object.
        var request = ICalendarGenerator.generateTodo("Assigned task", null, null, null, null, null, "REQUEST");
        assertTrue(request.contains("METHOD:PUBLISH\r\n"), "REQUEST without participants must downgrade: " + request);
        assertFalse(request.contains("ATTENDEE"), "a PUBLISH VTODO must not carry attendees: " + request);

        var reply = ICalendarGenerator.generateTodo("Task accepted", null, null, null, null, null, "REPLY");
        assertTrue(reply.contains("METHOD:PUBLISH\r\n"), "REPLY without participants must downgrade: " + reply);
    }

    @Test
    void effectiveMethodMatchesTheGeneratedBodyMethod() {
        // rfc6047 §2.4: the text/calendar method= parameter must equal the body METHOD. A REQUEST with
        // no resolvable organizer downgrades to PUBLISH in the body, so effectiveMethod() (which a caller
        // uses for the parameter) must report PUBLISH too — and REQUEST when the organizer is present.
        var noOrganizer = new ICalendarGenerator.EventDetails(
                "REQUEST", new Date(), null, null, "Meeting", "Boss", "", "body", List.of(), false, null, null);
        assertEquals("PUBLISH", ICalendarGenerator.effectiveMethod(noOrganizer));
        assertTrue(ICalendarGenerator.generate(noOrganizer).contains("METHOD:PUBLISH\r\n"));

        var withOrganizer = new ICalendarGenerator.EventDetails(
                "REQUEST",
                new Date(),
                null,
                null,
                "Meeting",
                "Boss",
                "boss@example.com",
                "body",
                List.of(new ICalendarGenerator.Attendee("W", "w@example.com")),
                false,
                null,
                null);
        assertEquals("REQUEST", ICalendarGenerator.effectiveMethod(withOrganizer));
        assertTrue(ICalendarGenerator.generate(withOrganizer).contains("METHOD:REQUEST\r\n"));
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

    // F6 (audit follow-up): a scheduling method (REQUEST/REPLY/CANCEL) with no resolvable ORGANIZER
    // address must downgrade to PUBLISH rather than emit a scheduling VEVENT carrying ATTENDEEs but no
    // ORGANIZER line (invalid iTIP, RFC 5546 §3.2).
    @Test
    void schedulingMethodWithoutOrganizerDowngradesToPublish() {
        var ical = ICalendarGenerator.generate(
                "REQUEST",
                START,
                END,
                "Room 1",
                "Subject",
                "Organizer",
                "",
                "Description",
                List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com")));

        assertTrue(ical.contains("METHOD:PUBLISH\r\n"), "must downgrade to PUBLISH without an organizer: " + ical);
        assertFalse(ical.contains("ATTENDEE"), "a publication must not carry attendees: " + ical);
        assertFalse(ical.contains("ORGANIZER"), "no organizer address means no ORGANIZER line: " + ical);
    }

    // -----------------------------------------------------------------------
    // UID — the meeting's stable identity (PidLidCleanGlobalObjectId)
    // -----------------------------------------------------------------------

    // rfc5545 §3.8.4.7 + rfc5546 §3.2: a REQUEST/REPLY/CANCEL of one meeting MUST share one UID so a
    // client can correlate them. [MS-OXCICAL] §2.1.3.1.1.20.26 derives that UID from
    // PidLidCleanGlobalObjectId as an uppercase-hex string.
    @Test
    void uidIsUppercaseHexOfCleanGlobalObjectIdWhenPresent() {
        var cleanGoid = new byte[] {0x01, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x10, 0x20, 0x30, 0x40};
        var ical = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                "REQUEST",
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
                0,
                cleanGoid));

        assertTrue(ical.contains("UID:01ABCDEF10203040\r\n"), "UID must be uppercase hex of the GOID: " + ical);
    }

    // [MS-OXOCAL] §2.2.1.27.1 / [MS-OXCICAL] §2.1.3.1.1.20.26: a meeting received from an external
    // RFC 5545 client carries that client's original UID inside the GlobalObjectId Data field, tagged
    // by ASCII "vCal-Uid" + 0x01000000. That embedded UID — not the hex of the whole blob — must be
    // exported, so a later REPLY/CANCEL still correlates with the originating event.
    @Test
    void uidUsesEmbeddedVCalUidWhenGlobalObjectIdWrapsExternalUid() {
        var embeddedUid = "external-client-uid-123";
        var tag = "vCal-Uid".getBytes(StandardCharsets.US_ASCII);
        var uidBytes = embeddedUid.getBytes(StandardCharsets.US_ASCII);
        // 40-byte fixed header (content irrelevant to the lookup) + tag + 0x01000000 + UID + NUL.
        var goid = new byte[40 + tag.length + 4 + uidBytes.length + 1];
        System.arraycopy(tag, 0, goid, 40, tag.length);
        goid[40 + tag.length] = 0x01;
        System.arraycopy(uidBytes, 0, goid, 40 + tag.length + 4, uidBytes.length);

        var ical = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                "REQUEST",
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
                0,
                goid));

        assertTrue(
                ical.contains("UID:" + embeddedUid + "\r\n"),
                "UID must reuse the embedded vCal-Uid, not hex-encode the blob: " + ical);
        assertFalse(
                ical.contains(HexFormat.of().withUpperCase().formatHex(goid)),
                "the whole-blob hex form must not be emitted when a vCal-Uid is embedded: " + ical);
    }

    // The fallback: with no stored GlobalObjectId the UID is a fresh value, but a non-empty UID line
    // must still be emitted (and an empty byte[] is treated the same as absent).
    @Test
    void uidFallsBackToGeneratedValueWhenCleanGlobalObjectIdAbsentOrEmpty() {
        var withoutGoid = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                "PUBLISH", START, END, null, "Subject", "Org", "org@example.com", null, List.of(), false, null, null));
        assertTrue(
                Pattern.compile("\r\nUID:\\S+\r\n").matcher(withoutGoid).find(),
                "a UID line must still be present without a GOID: " + withoutGoid);

        var emptyGoid = ICalendarGenerator.generate(new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "Org",
                "org@example.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                new byte[0]));
        // An empty GOID must not produce "UID:\r\n" — it falls back to a generated value.
        assertFalse(emptyGoid.contains("UID:\r\n"), "an empty GOID must not yield a blank UID: " + emptyGoid);
        assertTrue(
                Pattern.compile("\r\nUID:\\S+\r\n").matcher(emptyGoid).find(),
                "an empty GOID must fall back to a generated UID: " + emptyGoid);
    }

    // -----------------------------------------------------------------------
    // Round-21 audit tests
    // -----------------------------------------------------------------------

    // Fix #3 — VALARM: a non-null reminderMinutes emits a DISPLAY VALARM with the right TRIGGER.

    @Test
    void reminderMinutesEmitsValarmBlock() {
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                "Room",
                "Subject",
                "Organizer",
                "org@example.com",
                "Desc",
                List.of(),
                false,
                null,
                null,
                0,
                null,
                15,
                null);
        var ical = ICalendarGenerator.generate(event);

        assertTrue(ical.contains("BEGIN:VALARM\r\n"), "reminderMinutes must emit BEGIN:VALARM: " + ical);
        assertTrue(ical.contains("ACTION:DISPLAY\r\n"), ical);
        assertTrue(ical.contains("TRIGGER:-PT15M\r\n"), "TRIGGER must carry the minute count: " + ical);
        assertTrue(ical.contains("DESCRIPTION:Reminder\r\n"), ical);
        assertTrue(ical.contains("END:VALARM\r\n"), ical);
        // VALARM must appear before END:VEVENT
        assertTrue(
                ical.indexOf("BEGIN:VALARM") < ical.indexOf("END:VEVENT"),
                "VALARM must be nested inside VEVENT: " + ical);
    }

    @Test
    void nullReminderOmitsValarmBlock() {
        // The 14-arg ctor delegates with null, null for reminderMinutes and busyStatus.
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                "Room",
                "Subject",
                "Organizer",
                "org@example.com",
                "Desc",
                List.of(),
                false,
                null,
                null,
                0);
        var ical = ICalendarGenerator.generate(event);

        assertFalse(ical.contains("BEGIN:VALARM"), "null reminderMinutes must not emit VALARM: " + ical);
    }

    // Fix #7 — TRANSP / X-MICROSOFT-CDO-BUSYSTATUS: emitted only when busyStatus is non-null.

    @Test
    void busyStatusFreeEmitsTransparentAndFreeLabel() {
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "Organizer",
                "org@example.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                0);
        var ical = ICalendarGenerator.generate(event);

        assertTrue(ical.contains("TRANSP:TRANSPARENT\r\n"), "Free must emit TRANSP:TRANSPARENT: " + ical);
        assertTrue(ical.contains("X-MICROSOFT-CDO-BUSYSTATUS:FREE\r\n"), "Free must emit CDO-BUSYSTATUS:FREE: " + ical);
    }

    @Test
    void busyStatusBusyEmitsOpaqueAndBusyLabel() {
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "Organizer",
                "org@example.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                2);
        var ical = ICalendarGenerator.generate(event);

        assertTrue(ical.contains("TRANSP:OPAQUE\r\n"), "Busy must emit TRANSP:OPAQUE: " + ical);
        assertTrue(ical.contains("X-MICROSOFT-CDO-BUSYSTATUS:BUSY\r\n"), ical);
    }

    @Test
    void busyStatusTentativeEmitsOpaqueAndTentativeLabel() {
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "Organizer",
                "org@example.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                1);
        var ical = ICalendarGenerator.generate(event);

        assertTrue(ical.contains("TRANSP:OPAQUE\r\n"), ical);
        assertTrue(ical.contains("X-MICROSOFT-CDO-BUSYSTATUS:TENTATIVE\r\n"), ical);
    }

    @Test
    void nullBusyStatusOmitsTranspAndCdoHeader() {
        // The 14-arg ctor delegates with null busyStatus.
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "Organizer",
                "org@example.com",
                null,
                List.of(),
                false,
                null,
                null,
                0);
        var ical = ICalendarGenerator.generate(event);

        assertFalse(ical.contains("TRANSP"), "null busyStatus must not emit TRANSP: " + ical);
        assertFalse(ical.contains("X-MICROSOFT-CDO-BUSYSTATUS"), ical);
    }

    // Fix #8 — ATTENDEE ROLE: Attendee.role is emitted as ;ROLE= only when non-null.

    @Test
    void attendeeWithOptParticipantRoleEmitsRoleParam() {
        var ical = generate(
                "REQUEST", List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com", null, "OPT-PARTICIPANT")));

        assertTrue(ical.contains(";ROLE=OPT-PARTICIPANT"), "OPT-PARTICIPANT must appear in ATTENDEE line: " + ical);
        assertTrue(
                ical.contains("ATTENDEE;CN=\"Bob\";ROLE=OPT-PARTICIPANT:mailto:bob@example.com"),
                "Full ATTENDEE line with ROLE param: " + ical);
    }

    @Test
    void attendeeWithNullRoleOmitsRoleParam() {
        // The 2-arg Attendee ctor sets role = null → default REQ-PARTICIPANT, not emitted.
        var ical = generate("REQUEST", List.of(new ICalendarGenerator.Attendee("Bob", "bob@example.com")));

        assertFalse(ical.contains(";ROLE="), "null role must not emit ;ROLE= in ATTENDEE: " + ical);
    }

    // -----------------------------------------------------------------------
    // Round-22 audit tests
    // -----------------------------------------------------------------------

    // Fix ICAL-1 — CLASS: PidTagSensitivity(1=Personal,2=Private)→CLASS:PRIVATE,
    //                       (3=Confidential)→CLASS:CONFIDENTIAL, 0/null → no CLASS.

    @Test
    void sensitivityPrivateEmitsClassPrivate() {
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                2,
                null,
                null);
        var ical = ICalendarGenerator.generate(event);
        assertTrue(ical.contains("CLASS:PRIVATE\r\n"), "sensitivity=2 (Private) must emit CLASS:PRIVATE: " + ical);
    }

    @Test
    void sensitivityPersonalEmitsClassPrivate() {
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                1,
                null,
                null);
        var ical = ICalendarGenerator.generate(event);
        assertTrue(ical.contains("CLASS:PRIVATE\r\n"), "sensitivity=1 (Personal) must emit CLASS:PRIVATE: " + ical);
    }

    @Test
    void sensitivityConfidentialEmitsClassConfidential() {
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                3,
                null,
                null);
        var ical = ICalendarGenerator.generate(event);
        assertTrue(
                ical.contains("CLASS:CONFIDENTIAL\r\n"),
                "sensitivity=3 (Confidential) must emit CLASS:CONFIDENTIAL: " + ical);
    }

    @Test
    void sensitivityNormalOrAbsentOmitsClassProperty() {
        // sensitivity=0 (Normal): CLASS must be absent (RFC 5545 §3.8.1.3 defaults to PUBLIC).
        var eventNormal = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                0,
                null,
                null);
        assertFalse(
                ICalendarGenerator.generate(eventNormal).contains("CLASS:"),
                "sensitivity=0 (Normal) must not emit CLASS");

        // null sensitivity: CLASS must also be absent.
        var eventNull = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null);
        assertFalse(ICalendarGenerator.generate(eventNull).contains("CLASS:"), "null sensitivity must not emit CLASS");
    }

    // Fix ICAL-3 — PRIORITY: PidTagImportance(2=High)→PRIORITY:1, (0=Low)→PRIORITY:9,
    //                         (1=Normal)/null → no PRIORITY line.

    @Test
    void importanceHighEmitsPriorityOne() {
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                2,
                null);
        assertTrue(
                ICalendarGenerator.generate(event).contains("PRIORITY:1\r\n"),
                "importance=2 (High) must emit PRIORITY:1");
    }

    @Test
    void importanceLowEmitsPriorityNine() {
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                0,
                null);
        assertTrue(
                ICalendarGenerator.generate(event).contains("PRIORITY:9\r\n"),
                "importance=0 (Low) must emit PRIORITY:9");
    }

    @Test
    void importanceNormalOrAbsentOmitsPriorityProperty() {
        // importance=1 (Normal): PRIORITY must be absent.
        var eventNormal = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                1,
                null);
        assertFalse(
                ICalendarGenerator.generate(eventNormal).contains("PRIORITY:"),
                "importance=1 (Normal) must not emit PRIORITY");

        // null importance: PRIORITY must also be absent.
        var eventNull = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null);
        assertFalse(
                ICalendarGenerator.generate(eventNull).contains("PRIORITY:"), "null importance must not emit PRIORITY");
    }

    // Fix ICAL-4 — CATEGORIES: PidNameKeywords comma-joined with embedded commas TEXT-escaped.

    @Test
    void categoriesAreJoinedAndEmbeddedCommasAreEscaped() {
        // A category that contains a comma must be TEXT-escaped (RFC 5545 §3.3.11);
        // the unescaped comma is the separator between category values.
        var event = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                List.of("Red, urgent", "Travel"));
        var ical = ICalendarGenerator.generate(event);
        assertTrue(
                ical.contains("CATEGORIES:Red\\, urgent,Travel\r\n"),
                "embedded comma must be backslash-escaped; separator commas must be unescaped: " + ical);
    }

    @Test
    void absentOrEmptyCategoriesOmitsCategoriesProperty() {
        // null categories: CATEGORIES must be absent.
        var eventNull = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null);
        assertFalse(
                ICalendarGenerator.generate(eventNull).contains("CATEGORIES:"),
                "null categories must not emit CATEGORIES");

        // Empty list: CATEGORIES must also be absent.
        var eventEmpty = new ICalendarGenerator.EventDetails(
                "PUBLISH",
                START,
                END,
                null,
                "Subject",
                "O",
                "o@ex.com",
                null,
                List.of(),
                false,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                List.of());
        assertFalse(
                ICalendarGenerator.generate(eventEmpty).contains("CATEGORIES:"),
                "empty categories list must not emit CATEGORIES");
    }

    // Fix ICAL-TODO-1 — generateTodo 12-arg overload carries PRIORITY; 11-arg defaults to none.

    @Test
    void todoHighPriorityEmitsPriorityOne() {
        var vtodo = ICalendarGenerator.generateTodo(
                "Task", null, null, null, null, null, null, "PUBLISH", null, null, null, 2);
        assertTrue(vtodo.contains("PRIORITY:1\r\n"), "generateTodo priority=2 (High) must emit PRIORITY:1: " + vtodo);
    }

    @Test
    void todoLowPriorityEmitsPriorityNine() {
        var vtodo = ICalendarGenerator.generateTodo(
                "Task", null, null, null, null, null, null, "PUBLISH", null, null, null, 0);
        assertTrue(vtodo.contains("PRIORITY:9\r\n"), "generateTodo priority=0 (Low) must emit PRIORITY:9: " + vtodo);
    }

    @Test
    void todoElevenArgOverloadEmitsNoPriority() {
        // The 11-arg overload passes null for priority → no PRIORITY line.
        var vtodo = ICalendarGenerator.generateTodo(
                "Task", null, null, null, null, null, null, "PUBLISH", null, null, null);
        assertFalse(vtodo.contains("PRIORITY:"), "11-arg generateTodo must not emit PRIORITY: " + vtodo);
    }
}
