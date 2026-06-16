package com.github.ttereshchenko.mailkit.conversion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * The event time zone of an Outlook appointment, parsed from a PidLidTimeZoneStruct blob
 * ([MS-OXOCAL] §2.2.1.39): the standard/daylight UTC offsets plus the two yearly Windows
 * SYSTEMTIME transition rules. Knows how to render itself as an RFC 5545 VTIMEZONE and to convert
 * a UTC instant to the zone's wall-clock time, which is what keeps a recurring meeting at the same
 * local hour across DST changes (a plain UTC DTSTART would drift by an hour).
 *
 * <p>The DST decision evaluates both transition rules against the instant's standard-time
 * wall clock; the one-hour ambiguity right at a transition resolves toward standard time, which is
 * the conventional approximation for this structure.
 */
public final class WindowsTimeZone {

    /** One Windows SYSTEMTIME yearly transition rule: month is 1-12, occurrence 1-4 or 5 = last. */
    private record TransitionRule(int month, int dayOfWeek, int occurrence, int hour, int minute) {}

    private final int standardOffsetMinutes;
    private final int daylightOffsetMinutes;
    private final TransitionRule standardRule;
    private final TransitionRule daylightRule;

    private WindowsTimeZone(
            int standardOffsetMinutes,
            int daylightOffsetMinutes,
            TransitionRule standardRule,
            TransitionRule daylightRule) {
        this.standardOffsetMinutes = standardOffsetMinutes;
        this.daylightOffsetMinutes = daylightOffsetMinutes;
        this.standardRule = standardRule;
        this.daylightRule = daylightRule;
    }

    /**
     * Parses a 48-byte PidLidTimeZoneStruct, or returns {@code null} when the blob is malformed.
     * The struct stores biases in the Windows sense ({@code UTC = local + bias}), so the UTC
     * offsets used here are their negations.
     */
    public static WindowsTimeZone parse(byte[] timeZoneStruct) {
        if (timeZoneStruct == null || timeZoneStruct.length < 48) {
            return null;
        }
        var buffer = ByteBuffer.wrap(timeZoneStruct).order(ByteOrder.LITTLE_ENDIAN);
        var bias = buffer.getInt(0);
        var standardBias = buffer.getInt(4);
        var daylightBias = buffer.getInt(8);
        var standardRule = readRule(buffer, 14);
        var daylightRule = readRule(buffer, 32);

        var standardOffset = -(bias + standardBias);
        var daylightOffset = -(bias + daylightBias);
        if (Math.abs(standardOffset) > 14 * 60 || Math.abs(daylightOffset) > 14 * 60) {
            return null; // outside the legal UTC offset range — corrupted struct
        }
        var hasDst = standardRule != null && daylightRule != null && standardOffset != daylightOffset;
        return new WindowsTimeZone(
                standardOffset, daylightOffset, hasDst ? standardRule : null, hasDst ? daylightRule : null);
    }

    /** Reads one SYSTEMTIME rule; a zero month means "no transition" and yields {@code null}. */
    private static TransitionRule readRule(ByteBuffer buffer, int offset) {
        var month = Short.toUnsignedInt(buffer.getShort(offset + 2));
        var dayOfWeek = Short.toUnsignedInt(buffer.getShort(offset + 4));
        var occurrence = Short.toUnsignedInt(buffer.getShort(offset + 6));
        var hour = Short.toUnsignedInt(buffer.getShort(offset + 8));
        var minute = Short.toUnsignedInt(buffer.getShort(offset + 10));
        if (month < 1 || month > 12 || dayOfWeek > 6 || occurrence < 1 || occurrence > 5 || hour > 23) {
            return null;
        }
        return new TransitionRule(month, dayOfWeek, occurrence, hour, minute);
    }

    public boolean hasDst() {
        return standardRule != null;
    }

    /**
     * A TZID for this zone's VTIMEZONE, derived from its UTC offsets and DST transition rules (the
     * struct records no display name). It is unique to the zone definition — two events with
     * different zones in one VCALENDAR get distinct TZIDs (RFC 5545 §3.2.19 requires each
     * {@code TZID} reference to match a VTIMEZONE in the object), while two events sharing a zone
     * reuse the same one. Uses only RFC 5545 {@code paramtext} SAFE-CHARs.
     */
    public String tzid() {
        var builder = new StringBuilder("MailKit/UTC").append(formatOffset(standardOffsetMinutes));
        if (hasDst()) {
            builder.append("_DST")
                    .append(formatOffset(daylightOffsetMinutes))
                    .append('_')
                    .append(ruleToken(daylightRule))
                    .append('-')
                    .append(ruleToken(standardRule));
        }
        return builder.toString();
    }

    /** A compact, deterministic token for a transition rule: month (2 digits), weekday, occurrence. */
    private static String ruleToken(TransitionRule rule) {
        return String.format("%02d%d%d", rule.month(), rule.dayOfWeek(), rule.occurrence());
    }

    /** The wall-clock time of {@code instant} in this zone. */
    public LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, offsetAt(instant));
    }

    /** The UTC instant of the given wall-clock time in this zone. */
    public Instant toInstant(LocalDateTime local) {
        // Resolve the offset via the standard-time interpretation of the wall clock, mirroring
        // offsetAt; ambiguous/skipped times resolve toward standard time.
        var assumingStandard = local.toInstant(ZoneOffset.ofTotalSeconds(standardOffsetMinutes * 60));
        return local.toInstant(offsetAt(assumingStandard));
    }

    /** The UTC offset in effect at {@code instant}. */
    public ZoneOffset offsetAt(Instant instant) {
        if (!hasDst()) {
            return ZoneOffset.ofTotalSeconds(standardOffsetMinutes * 60);
        }
        var standardWallClock = LocalDateTime.ofInstant(instant, ZoneOffset.ofTotalSeconds(standardOffsetMinutes * 60));
        var year = standardWallClock.getYear();
        var daylightStart = transitionFor(year, daylightRule);
        var standardStart = transitionFor(year, standardRule);

        boolean inDaylight;
        if (daylightStart.isBefore(standardStart)) {
            // Northern hemisphere: daylight runs daylightStart..standardStart.
            inDaylight = !standardWallClock.isBefore(daylightStart) && standardWallClock.isBefore(standardStart);
        } else {
            // Southern hemisphere: daylight wraps the new year.
            inDaylight = !standardWallClock.isBefore(daylightStart) || standardWallClock.isBefore(standardStart);
        }
        return ZoneOffset.ofTotalSeconds((inDaylight ? daylightOffsetMinutes : standardOffsetMinutes) * 60);
    }

    /** The rule's transition wall-clock datetime within {@code year}. */
    private static LocalDateTime transitionFor(int year, TransitionRule rule) {
        var dayOfWeek = java.time.DayOfWeek.of(rule.dayOfWeek() == 0 ? 7 : rule.dayOfWeek());
        var firstOfMonth = java.time.LocalDate.of(year, rule.month(), 1);
        var date = rule.occurrence() == 5
                ? firstOfMonth.with(TemporalAdjusters.lastInMonth(dayOfWeek))
                : firstOfMonth.with(TemporalAdjusters.dayOfWeekInMonth(rule.occurrence(), dayOfWeek));
        return date.atTime(rule.hour(), rule.minute());
    }

    /** This zone as a VTIMEZONE block (CRLF-terminated lines, not folded — all lines are short). */
    public String toVTimeZone() {
        var block = new StringBuilder();
        block.append("BEGIN:VTIMEZONE\r\n");
        block.append("TZID:").append(tzid()).append("\r\n");
        if (!hasDst()) {
            block.append("BEGIN:STANDARD\r\n");
            block.append("DTSTART:19700101T000000\r\n");
            block.append("TZOFFSETFROM:")
                    .append(formatOffset(standardOffsetMinutes))
                    .append("\r\n");
            block.append("TZOFFSETTO:")
                    .append(formatOffset(standardOffsetMinutes))
                    .append("\r\n");
            block.append("END:STANDARD\r\n");
        } else {
            appendObservance(block, "DAYLIGHT", daylightRule, standardOffsetMinutes, daylightOffsetMinutes);
            appendObservance(block, "STANDARD", standardRule, daylightOffsetMinutes, standardOffsetMinutes);
        }
        block.append("END:VTIMEZONE\r\n");
        return block.toString();
    }

    private static void appendObservance(
            StringBuilder block, String name, TransitionRule rule, int fromOffsetMinutes, int toOffsetMinutes) {
        var anchor = transitionFor(1970, rule);
        block.append("BEGIN:").append(name).append("\r\n");
        block.append(String.format(
                "DTSTART:%04d%02d%02dT%02d%02d00\r\n",
                anchor.getYear(),
                anchor.getMonthValue(),
                anchor.getDayOfMonth(),
                anchor.getHour(),
                anchor.getMinute()));
        block.append("RRULE:FREQ=YEARLY;BYMONTH=")
                .append(rule.month())
                .append(";BYDAY=")
                .append(rule.occurrence() == 5 ? -1 : rule.occurrence())
                .append(byDayCode(rule.dayOfWeek()))
                .append("\r\n");
        block.append("TZOFFSETFROM:").append(formatOffset(fromOffsetMinutes)).append("\r\n");
        block.append("TZOFFSETTO:").append(formatOffset(toOffsetMinutes)).append("\r\n");
        block.append("END:").append(name).append("\r\n");
    }

    private static String byDayCode(int windowsDayOfWeek) {
        return new String[] {"SU", "MO", "TU", "WE", "TH", "FR", "SA"}[windowsDayOfWeek];
    }

    private static String formatOffset(int offsetMinutes) {
        var sign = offsetMinutes < 0 ? "-" : "+";
        var absolute = Math.abs(offsetMinutes);
        return String.format("%s%02d%02d", sign, absolute / 60, absolute % 60);
    }
}
