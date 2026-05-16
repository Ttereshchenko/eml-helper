package com.github.ttereshchenko.mailkit.inspections.rules;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Optional;

/**
 * Tolerant RFC 2822 {@code Date:} header parser. Accepts the canonical
 * {@code RFC_1123_DATE_TIME} form plus a few real-world deviations: missing
 * day-of-week, single-digit day, missing seconds, lowercase month / zone.
 */
public final class DateParseRule {

    private static final DateTimeFormatter TOLERANT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .parseLenient()
            .optionalStart()
            .appendPattern("EEE, ")
            .optionalEnd()
            .appendPattern("[d ][dd ]")
            .appendPattern("MMM ")
            .appendPattern("yyyy ")
            .appendPattern("HH:mm")
            .optionalStart()
            .appendPattern(":ss")
            .optionalEnd()
            .appendPattern(" ")
            .appendZoneOrOffsetId()
            .toFormatter(Locale.ROOT);

    private DateParseRule() {}

    public static Optional<OffsetDateTime> tryParse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        var trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME));
        } catch (DateTimeParseException ignored) {
            // Fall back to the tolerant parser.
        }
        try {
            var temporal = TOLERANT.parse(trimmed);
            var instant = ZoneId.from(temporal).getRules().getOffset(java.time.Instant.now());
            return Optional.of(OffsetDateTime.of(
                    temporal.get(ChronoField.YEAR),
                    temporal.get(ChronoField.MONTH_OF_YEAR),
                    temporal.get(ChronoField.DAY_OF_MONTH),
                    temporal.get(ChronoField.HOUR_OF_DAY),
                    temporal.get(ChronoField.MINUTE_OF_HOUR),
                    temporal.isSupported(ChronoField.SECOND_OF_MINUTE) ? temporal.get(ChronoField.SECOND_OF_MINUTE) : 0,
                    0,
                    instant));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    public static String formatNow() {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(OffsetDateTime.now(ZoneId.systemDefault()));
    }
}
