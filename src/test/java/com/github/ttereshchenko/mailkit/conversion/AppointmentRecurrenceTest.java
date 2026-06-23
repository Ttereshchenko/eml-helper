package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * F5 coverage: AppointmentRecurrencePattern blobs ([MS-OXOCAL] §2.2.1.44.1) map onto the RRULE
 * pieces a calendar client expects. The blobs are built field by field to the spec layout; the
 * real-archive path is covered end to end against dist-list.pst's recurring appointment in
 * {@code PstConversionTest}.
 */
class AppointmentRecurrenceTest {

    private static final LocalDateTime WINDOWS_EPOCH = LocalDateTime.of(1601, 1, 1, 0, 0);

    @Test
    void biweeklyTuesdayThursdayWithCountMapsToWeeklyRule() {
        var pattern = AppointmentRecurrence.parse(blob(
                0x200B,
                1,
                2,
                new int[] {0x04 | 0x10},
                0x2022,
                10,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 2),
                date(2024, 5, 7)));

        assertEquals("FREQ=WEEKLY;INTERVAL=2;WKST=SU;BYDAY=TU,TH", pattern.coreRule());
        assertEquals(10, pattern.count());
        assertNull(pattern.until());
    }

    @Test
    void thirdFridayMonthlyMapsToBySetPos() {
        var pattern = AppointmentRecurrence.parse(blob(
                0x200C,
                3,
                1,
                new int[] {0x20, 3},
                0x2023,
                0,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 19),
                date(2024, 1, 19)));

        assertEquals("FREQ=MONTHLY;INTERVAL=1;BYDAY=FR;BYSETPOS=3", pattern.coreRule());
        assertNull(pattern.count());
        assertNull(pattern.until());
    }

    @Test
    void lastOccurrenceAndDayThirtyOneMapToMinusOne() {
        var lastWeekday = AppointmentRecurrence.parse(blob(
                0x200C,
                3,
                1,
                new int[] {0x02, 5},
                0x2023,
                0,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 29),
                date(2024, 1, 29)));
        assertEquals("FREQ=MONTHLY;INTERVAL=1;BYDAY=MO;BYSETPOS=-1", lastWeekday.coreRule());

        var dayThirtyOne = AppointmentRecurrence.parse(blob(
                0x200C,
                2,
                1,
                new int[] {31},
                0x2023,
                0,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 31),
                date(2024, 1, 31)));
        assertEquals("FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=-1", dayThirtyOne.coreRule());
    }

    @Test
    void monthEndConsumesItsPatternTypeSpecificField() {
        // PatternType 0x0004 (MonthEnd) carries a 4-byte PatternTypeSpecific_Month (Day) field, exactly
        // like 0x0002 (Month) ([MS-OXOCAL] §2.2.1.44.1.3). Before the fix the MonthEnd arm consumed zero
        // bytes, so EndType/OccurrenceCount/StartDate were read 4 bytes too early: the count clause and
        // the series start both came out wrong. With Day=31 / EndType=END_AFTER_COUNT / count=5, a correct
        // parse keeps the count and the 2024-01-31 start; the misaligned parse loses both.
        var monthEnd = AppointmentRecurrence.parse(blob(
                0x200C,
                0x0004,
                1,
                new int[] {31},
                0x2022,
                5,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 31),
                date(2024, 1, 31)));

        assertEquals("FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=-1", monthEnd.coreRule());
        assertEquals(Integer.valueOf(5), monthEnd.count());
        assertEquals(LocalDate.of(2024, 1, 31), monthEnd.seriesStart());
    }

    @Test
    void yearlyPatternDerivesByMonthFromTheSeriesStart() {
        var pattern = AppointmentRecurrence.parse(blob(
                0x200D,
                2,
                12,
                new int[] {15},
                0x2023,
                0,
                0,
                new long[0],
                new long[0],
                date(2024, 7, 15),
                date(2024, 7, 15)));

        assertEquals("FREQ=YEARLY;INTERVAL=1;BYMONTH=7;BYMONTHDAY=15", pattern.coreRule());
        assertEquals(LocalDate.of(2024, 7, 15), pattern.seriesStart());
    }

    @Test
    void dailyPeriodIsStoredInMinutesAndUntilInTheEndDate() {
        var pattern = AppointmentRecurrence.parse(blob(
                0x200A,
                0,
                2880,
                new int[0],
                0x2021,
                0,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 1),
                date(2024, 3, 1)));

        assertEquals("FREQ=DAILY;INTERVAL=2", pattern.coreRule());
        assertEquals(LocalDate.of(2024, 3, 1), pattern.until());
        assertNull(pattern.count());
    }

    @Test
    void onlyTrulyDeletedInstancesBecomeExDates() {
        var deletedOnly = date(2024, 1, 9);
        var movedInstance = date(2024, 1, 16);
        var pattern = AppointmentRecurrence.parse(blob(
                0x200B,
                1,
                1,
                new int[] {0x04},
                0x2023,
                0,
                0,
                new long[] {deletedOnly, movedInstance},
                new long[] {movedInstance},
                date(2024, 1, 2),
                date(2024, 1, 2)));

        assertEquals(
                java.util.List.of(LocalDate.of(2024, 1, 9)),
                pattern.deletedInstanceDates(),
                "A modified occurrence keeps its slot; only genuine deletions are EXDATEs");
    }

    @Test
    void hijriAndMalformedBlobsYieldNoPattern() {
        assertNull(
                AppointmentRecurrence.parse(blob(
                        0x200C,
                        0x000A,
                        1,
                        new int[] {1},
                        0x2023,
                        0,
                        0,
                        new long[0],
                        new long[0],
                        date(2024, 1, 1),
                        date(2024, 1, 1))),
                "Hijri patterns are not mapped");
        assertNull(AppointmentRecurrence.parse(new byte[10]), "A truncated blob must not parse");
        assertNull(AppointmentRecurrence.parse(null));
    }

    @Test
    void emptyWeeklyDayMaskYieldsNoPatternInsteadOfEmptyByDay() {
        // A corrupt weekly pattern with a zero day-of-week mask would otherwise emit a "BYDAY=" with no
        // value (invalid rfc5545 §3.3.10), making the whole RRULE unparseable; treat it as no recurrence.
        assertNull(AppointmentRecurrence.parse(blob(
                0x200B,
                1,
                1,
                new int[] {0x00},
                0x2023,
                0,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 2),
                date(2024, 1, 2))));
    }

    @Test
    void emptyMonthlyNthDayMaskYieldsNoPattern() {
        // A PATTERN_MONTH_NTH with a zero day-of-week mask likewise has no valid BYDAY rule-part.
        assertNull(AppointmentRecurrence.parse(blob(
                0x200C,
                3,
                1,
                new int[] {0x00, 3},
                0x2023,
                0,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 19),
                date(2024, 1, 19))));
    }

    @Test
    void monthlyDayBelowOneYieldsNoPatternInsteadOfBymonthdayZero() {
        // A PATTERN_MONTH with a day-of-month below 1 (corrupt blob) would otherwise emit "BYMONTHDAY=0",
        // which is invalid (rfc5545 §3.3.10: monthdaynum is 1..31, never 0) and makes the whole RRULE
        // unparseable; treat it as no recurrence so the event exports as a single clean occurrence.
        assertNull(AppointmentRecurrence.parse(blob(
                0x200C,
                2,
                1,
                new int[] {0},
                0x2023,
                0,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 19),
                date(2024, 1, 19))));
    }

    @Test
    void monthlyNthOccurrenceOutOfRangeYieldsNoPatternInsteadOfBysetposZero() {
        // [MS-OXOCAL] §2.2.1.44.1.1 permits N only in 1..5. A corrupt N=0 would emit "BYSETPOS=0"
        // (invalid rfc5545 §3.3.10 setposday) and N=6 an out-of-range BYSETPOS; both make the RRULE
        // unparseable, so a non-conforming N drops the recurrence like the empty-mask arms do.
        assertNull(AppointmentRecurrence.parse(blob(
                0x200C,
                3,
                1,
                new int[] {0x20, 0},
                0x2023,
                0,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 19),
                date(2024, 1, 19))));
        assertNull(AppointmentRecurrence.parse(blob(
                0x200C,
                3,
                1,
                new int[] {0x20, 6},
                0x2023,
                0,
                0,
                new long[0],
                new long[0],
                date(2024, 1, 19),
                date(2024, 1, 19))));
    }

    /** Minutes since 1601-01-01 (local) for midnight of the given date. */
    private static long date(int year, int month, int day) {
        return java.time.Duration.between(
                        WINDOWS_EPOCH, LocalDate.of(year, month, day).atStartOfDay())
                .toMinutes();
    }

    /** Builds an AppointmentRecurrencePattern blob in the spec's field order. */
    private static byte[] blob(
            int recurFrequency,
            int patternType,
            int period,
            int[] patternTypeSpecific,
            int endType,
            int occurrenceCount,
            int firstDayOfWeek,
            long[] deletedDates,
            long[] modifiedDates,
            long startDateMinutes,
            long endDateMinutes) {
        var size = 22
                + patternTypeSpecific.length * 4
                + 12
                + 4
                + deletedDates.length * 4
                + 4
                + modifiedDates.length * 4
                + 8;
        var buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0x3004); // ReaderVersion
        buffer.putShort((short) 0x3004); // WriterVersion
        buffer.putShort((short) recurFrequency);
        buffer.putShort((short) patternType);
        buffer.putShort((short) 0x0001); // CalendarType (Gregorian)
        buffer.putInt(0); // FirstDateTime
        buffer.putInt(period);
        buffer.putInt(0); // SlidingFlag
        for (var value : patternTypeSpecific) {
            buffer.putInt(value);
        }
        buffer.putInt(endType);
        buffer.putInt(occurrenceCount);
        buffer.putInt(firstDayOfWeek);
        buffer.putInt(deletedDates.length);
        for (var value : deletedDates) {
            buffer.putInt((int) value);
        }
        buffer.putInt(modifiedDates.length);
        for (var value : modifiedDates) {
            buffer.putInt((int) value);
        }
        buffer.putInt((int) startDateMinutes);
        buffer.putInt((int) endDateMinutes);
        return buffer.array();
    }
}
