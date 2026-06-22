package com.github.ttereshchenko.mailkit.conversion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Parses an Outlook AppointmentRecurrencePattern blob (PidLidAppointmentRecur, [MS-OXOCAL]
 * §2.2.1.44.1) into the RFC 5545 RRULE pieces a calendar invite needs. Only the Gregorian pattern
 * types are mapped; Hijri patterns return {@code null} (the caller exports a single occurrence, as
 * before). Modified exceptions keep their original slot — only truly deleted instances become
 * {@code EXDATE}s — so a moved occurrence still shows at its original time rather than vanishing.
 */
public final class AppointmentRecurrence {

    /** Minutes between 1601-01-01 (the pattern's epoch, local time) and the value's date. */
    private static final LocalDateTime WINDOWS_EPOCH = LocalDateTime.of(1601, 1, 1, 0, 0);

    private static final int PATTERN_DAY = 0x0000;
    private static final int PATTERN_WEEK = 0x0001;
    private static final int PATTERN_MONTH = 0x0002;
    private static final int PATTERN_MONTH_NTH = 0x0003;
    private static final int PATTERN_MONTH_END = 0x0004;

    private static final int FREQUENCY_YEARLY = 0x200D;

    private static final int END_BY_DATE = 0x2021;
    private static final int END_AFTER_COUNT = 0x2022;

    private static final String[] BYDAY_CODES = {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};

    /**
     * One parsed recurrence: the RRULE without its end clause, how the series ends ({@code until}
     * <em>or</em> {@code count}, possibly neither), and the local dates of deleted occurrences.
     */
    public record Pattern(
            String coreRule,
            LocalDate until,
            Integer count,
            LocalDate seriesStart,
            List<LocalDate> deletedInstanceDates) {}

    private AppointmentRecurrence() {}

    /**
     * Parses the recurrence blob, or returns {@code null} when it is malformed or uses a calendar
     * this mapping does not cover (Hijri pattern types).
     */
    public static Pattern parse(byte[] blob) {
        if (blob == null || blob.length < 30) {
            return null;
        }
        try {
            return parseValidated(ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN));
        } catch (RuntimeException malformed) {
            // Truncated or internally inconsistent blob — treat as no recurrence rather than fail
            // the whole message export.
            return null;
        }
    }

    private static Pattern parseValidated(ByteBuffer buffer) {
        buffer.position(4); // ReaderVersion + WriterVersion
        var recurFrequency = Short.toUnsignedInt(buffer.getShort());
        var patternType = Short.toUnsignedInt(buffer.getShort());
        buffer.getShort(); // CalendarType
        buffer.getInt(); // FirstDateTime
        var period = buffer.getInt();
        buffer.getInt(); // SlidingFlag

        String byParts;
        switch (patternType) {
            case PATTERN_DAY -> byParts = null;
            case PATTERN_WEEK -> {
                var byDay = byDayList(buffer.getInt());
                if (byDay.isEmpty()) {
                    // A zero day-of-week mask has no valid BYDAY rule-part (rfc5545 §3.3.10); emitting
                    // "BYDAY=" would make the whole RRULE unparseable. Treat the (corrupt) blob as
                    // carrying no usable recurrence, exactly like the unknown-pattern arm below.
                    return null;
                }
                byParts = ";BYDAY=" + byDay;
            }
            case PATTERN_MONTH -> {
                var dayOfMonth = buffer.getInt();
                // Outlook stores 31 for "the last day of the month" — RRULE expresses that as -1
                // (a literal 31 would silently skip every short month).
                byParts = ";BYMONTHDAY=" + (dayOfMonth >= 31 ? -1 : dayOfMonth);
            }
            case PATTERN_MONTH_NTH -> {
                var dayOfWeekMask = buffer.getInt();
                var occurrence = buffer.getInt();
                var byDay = byDayList(dayOfWeekMask);
                if (byDay.isEmpty()) {
                    // As above: an empty BYDAY is invalid, so drop the unusable recurrence.
                    return null;
                }
                byParts = ";BYDAY=" + byDay + ";BYSETPOS=" + (occurrence == 5 ? -1 : occurrence);
            }
            case PATTERN_MONTH_END -> {
                // [MS-OXOCAL] §2.2.1.44.1.3: PatternType 0x0004 (MonthEnd) carries the same 4-byte
                // PatternTypeSpecific_Month (Day) field as 0x0002 (Month). Consume it — exactly like the
                // Month arm above — so EndType/OccurrenceCount/FirstDOW and the instance-date arrays that
                // follow are read from their real offsets instead of 4 bytes too early.
                buffer.getInt();
                byParts = ";BYMONTHDAY=-1";
            }
            default -> {
                return null; // Hijri (0x0A..0x0C) and unknown pattern types are not mapped
            }
        }

        var endType = buffer.getInt();
        var occurrenceCount = buffer.getInt();
        var firstDayOfWeek = buffer.getInt();
        var deletedDates = readInstanceDates(buffer);
        var modifiedDates = new HashSet<>(readInstanceDates(buffer));
        var startDate = minutesToDate(Integer.toUnsignedLong(buffer.getInt()));
        var endDate = minutesToDate(Integer.toUnsignedLong(buffer.getInt()));

        var rule = new StringBuilder("FREQ=");
        switch (patternType) {
            case PATTERN_DAY ->
                // For daily patterns the period is stored in minutes ([MS-OXOCAL] §2.2.1.44.1).
                rule.append("DAILY;INTERVAL=").append(Math.max(1, period / 1440));
            case PATTERN_WEEK -> {
                rule.append("WEEKLY;INTERVAL=").append(Math.max(1, period));
                if (firstDayOfWeek >= 0 && firstDayOfWeek <= 6) {
                    rule.append(";WKST=").append(BYDAY_CODES[firstDayOfWeek]);
                }
            }
            case PATTERN_MONTH, PATTERN_MONTH_NTH, PATTERN_MONTH_END -> {
                if (recurFrequency == FREQUENCY_YEARLY) {
                    rule.append("YEARLY;INTERVAL=")
                            .append(Math.max(1, period / 12))
                            .append(";BYMONTH=")
                            .append(startDate.getMonthValue());
                } else {
                    rule.append("MONTHLY;INTERVAL=").append(Math.max(1, period));
                }
            }
            default -> throw new IllegalStateException("unreachable pattern type " + patternType);
        }
        if (byParts != null) {
            rule.append(byParts);
        }

        var until = endType == END_BY_DATE ? endDate : null;
        var count = endType == END_AFTER_COUNT && occurrenceCount > 0 ? occurrenceCount : null;

        // A modified occurrence is listed in BOTH instance arrays; removing it from the deleted set
        // keeps its original slot occupied (we do not export exception data), so only genuine
        // deletions become EXDATEs.
        var trulyDeleted = new ArrayList<LocalDate>();
        for (var date : deletedDates) {
            if (!modifiedDates.contains(date)) {
                trulyDeleted.add(date);
            }
        }

        return new Pattern(rule.toString(), until, count, startDate, Collections.unmodifiableList(trulyDeleted));
    }

    private static List<LocalDate> readInstanceDates(ByteBuffer buffer) {
        var count = buffer.getInt();
        if (count < 0 || count > buffer.remaining() / 4) {
            throw new IllegalStateException("instance-date count out of range: " + count);
        }
        var dates = new ArrayList<LocalDate>(count);
        for (var index = 0; index < count; index++) {
            dates.add(minutesToDate(Integer.toUnsignedLong(buffer.getInt())));
        }
        return dates;
    }

    /** Converts "minutes since 1601-01-01, local time" to the (local) calendar date it names. */
    private static LocalDate minutesToDate(long minutes) {
        return WINDOWS_EPOCH.plusMinutes(minutes).toLocalDate();
    }

    /** The {@code BYDAY} list for a PatternTypeSpecific day-of-week bitmask (bit 0 = Sunday). */
    private static String byDayList(int dayOfWeekMask) {
        var days = new StringBuilder();
        for (var day = 0; day < 7; day++) {
            if ((dayOfWeekMask & (1 << day)) != 0) {
                if (days.length() > 0) {
                    days.append(',');
                }
                days.append(BYDAY_CODES[day]);
            }
        }
        return days.toString();
    }
}
