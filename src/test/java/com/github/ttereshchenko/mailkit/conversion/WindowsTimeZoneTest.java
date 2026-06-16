package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * F5 coverage for PidLidTimeZoneStruct parsing ([MS-OXOCAL] §2.2.1.39): offsets, DST rule
 * evaluation on both sides of the transitions, the generated VTIMEZONE, and the wall-clock
 * round-trip that keeps recurring events at their local hour.
 */
class WindowsTimeZoneTest {

    @Test
    void pacificZoneResolvesDaylightAndStandardOffsets() {
        var zone = WindowsTimeZone.parse(pacificStruct());

        assertTrue(zone.hasDst());
        // August 2nd: Pacific Daylight Time, UTC-7.
        assertEquals(ZoneOffset.ofHours(-7), zone.offsetAt(Instant.parse("2016-08-02T15:00:00Z")));
        assertEquals(LocalDateTime.parse("2016-08-02T08:00"), zone.toLocal(Instant.parse("2016-08-02T15:00:00Z")));
        // January 15th: Pacific Standard Time, UTC-8.
        assertEquals(ZoneOffset.ofHours(-8), zone.offsetAt(Instant.parse("2016-01-15T12:00:00Z")));
    }

    @Test
    void localWallClockRoundTripsThroughToInstant() {
        var zone = WindowsTimeZone.parse(pacificStruct());
        var summerLocal = LocalDateTime.parse("2016-08-02T08:00");
        assertEquals(Instant.parse("2016-08-02T15:00:00Z"), zone.toInstant(summerLocal));
        var winterLocal = LocalDateTime.parse("2016-01-15T08:00");
        assertEquals(Instant.parse("2016-01-15T16:00:00Z"), zone.toInstant(winterLocal));
    }

    @Test
    void vTimeZoneCarriesBothObservancesAndWindowsRules() {
        var zone = WindowsTimeZone.parse(pacificStruct());
        var block = zone.toVTimeZone();

        // The TZID is derived from the zone's offsets + DST rules so distinct zones cannot collide.
        assertTrue(block.contains("TZID:" + zone.tzid()));
        assertEquals("MailKit/UTC-0800_DST-0700_0302-1101", zone.tzid());
        assertTrue(block.contains("BEGIN:DAYLIGHT"));
        assertTrue(block.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU"), block);
        assertTrue(block.contains("RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU"), block);
        assertTrue(block.contains("TZOFFSETFROM:-0800"), block);
        assertTrue(block.contains("TZOFFSETTO:-0700"), block);
    }

    @Test
    void zoneWithoutDstHasOneFixedObservance() {
        var zone = WindowsTimeZone.parse(fixedStruct(-330)); // UTC+5:30, no transitions

        assertFalse(zone.hasDst());
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), zone.offsetAt(Instant.parse("2024-06-01T00:00:00Z")));
        var block = zone.toVTimeZone();
        assertTrue(block.contains("TZOFFSETTO:+0530"), block);
        assertFalse(block.contains("BEGIN:DAYLIGHT"), block);
    }

    @Test
    void malformedStructsYieldNull() {
        assertNull(WindowsTimeZone.parse(null));
        assertNull(WindowsTimeZone.parse(new byte[10]));
        assertNull(WindowsTimeZone.parse(fixedStruct(20 * 60)), "An offset beyond UTC±14 is corrupt");
    }

    /** US Pacific: bias 480 (UTC-8), DST -60 starting 2nd Sunday of March, ending 1st Sunday of November. */
    private static byte[] pacificStruct() {
        return struct(480, 0, -60, rule(11, 0, 1, 2), rule(3, 0, 2, 2));
    }

    private static byte[] fixedStruct(int bias) {
        return struct(bias, 0, 0, null, null);
    }

    private static byte[] struct(int bias, int standardBias, int daylightBias, int[] standardRule, int[] daylightRule) {
        var buffer = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, bias);
        buffer.putInt(4, standardBias);
        buffer.putInt(8, daylightBias);
        putRule(buffer, 14, standardRule);
        putRule(buffer, 32, daylightRule);
        return buffer.array();
    }

    /** {month, dayOfWeek (0 = Sunday), occurrence (5 = last), hour} */
    private static int[] rule(int month, int dayOfWeek, int occurrence, int hour) {
        return new int[] {month, dayOfWeek, occurrence, hour};
    }

    private static void putRule(ByteBuffer buffer, int offset, int[] rule) {
        if (rule == null) {
            return; // month 0 = no transition
        }
        buffer.putShort(offset + 2, (short) rule[0]); // wMonth
        buffer.putShort(offset + 4, (short) rule[1]); // wDayOfWeek
        buffer.putShort(offset + 6, (short) rule[2]); // wDay (occurrence)
        buffer.putShort(offset + 8, (short) rule[3]); // wHour
    }
}
