package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(ical.contains("DTSTART;TZID=" + WindowsTimeZone.TZID + ":20160802T080000"), ical);
        assertTrue(ical.contains("DTEND;TZID=" + WindowsTimeZone.TZID + ":20160802T090000"), ical);
        // RFC 5545 §3.3.10: UNTIL with a TZID-anchored DTSTART must be UTC. December 6th is PST
        // (UTC-8), so the 08:00 local start becomes 16:00Z.
        assertTrue(ical.contains("RRULE:FREQ=WEEKLY;INTERVAL=1;WKST=SU;BYDAY=TU;UNTIL=20161206T160000Z"), ical);
        assertTrue(ical.contains("EXDATE;TZID=" + WindowsTimeZone.TZID + ":20160809T080000"), ical);
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
    void todoCarriesDueDateCompletionAndEscaping() {
        var todo = ICalendarGenerator.generateTodo(
                "File; the, report",
                "Details line",
                Date.from(Instant.parse("2024-01-01T08:00:00Z")),
                Date.from(Instant.parse("2024-01-05T17:00:00Z")),
                0.75,
                false);

        assertTrue(todo.contains("BEGIN:VTODO"), todo);
        assertTrue(todo.contains("DTSTART:20240101T080000Z"), todo);
        assertTrue(todo.contains("DUE:20240105T170000Z"), todo);
        assertTrue(todo.contains("SUMMARY:File\\; the\\, report"), todo);
        assertTrue(todo.contains("PERCENT-COMPLETE:75"), todo);
        assertFalse(todo.contains("STATUS:COMPLETED"), todo);

        var completed = ICalendarGenerator.generateTodo("Done", null, null, null, 1.0, true);
        assertTrue(completed.contains("STATUS:COMPLETED"), completed);
        assertTrue(completed.contains("PERCENT-COMPLETE:100"), completed);
    }
}
