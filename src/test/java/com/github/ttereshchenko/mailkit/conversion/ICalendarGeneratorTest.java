package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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
}
