package com.github.ttereshchenko.mailkit.conversion;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.UUID;

public final class ICalendarGenerator {
    private ICalendarGenerator() {}

    public static String generate(
            Date startTime,
            Date endTime,
            String location,
            String subject,
            String organizerName,
            String organizerEmail,
            String description) {

        SimpleDateFormat dtFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        dtFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        String dtStamp = dtFormat.format(new Date());
        String dtStart = startTime != null ? dtFormat.format(startTime) : dtStamp;
        String dtEnd = endTime != null ? dtFormat.format(endTime) : dtStamp;
        String uid = UUID.randomUUID().toString();

        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n");
        builder.append("VERSION:2.0\r\n");
        builder.append("PRODID:-//MailKit//EN\r\n");
        builder.append("METHOD:REQUEST\r\n");
        builder.append("BEGIN:VEVENT\r\n");
        builder.append("UID:").append(uid).append("\r\n");
        builder.append("DTSTAMP:").append(dtStamp).append("\r\n");
        builder.append("DTSTART:").append(dtStart).append("\r\n");
        builder.append("DTEND:").append(dtEnd).append("\r\n");

        if (organizerEmail != null && !organizerEmail.isBlank()) {
            builder.append("ORGANIZER");
            if (organizerName != null && !organizerName.isBlank()) {
                builder.append(";CN=\"").append(escapeIcal(organizerName)).append("\"");
            }
            builder.append(":mailto:").append(organizerEmail).append("\r\n");
        }

        if (location != null && !location.isBlank()) {
            builder.append("LOCATION:").append(escapeIcal(location)).append("\r\n");
        }
        if (subject != null && !subject.isBlank()) {
            builder.append("SUMMARY:").append(escapeIcal(subject)).append("\r\n");
        }
        if (description != null && !description.isBlank()) {
            builder.append("DESCRIPTION:").append(escapeIcal(description)).append("\r\n");
        }

        builder.append("END:VEVENT\r\n");
        builder.append("END:VCALENDAR\r\n");

        return foldLines(builder.toString());
    }

    private static String escapeIcal(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static String foldLines(String ical) {
        StringBuilder result = new StringBuilder();
        for (String line : ical.split("\r\n")) {
            while (line.length() > 75) {
                result.append(line.substring(0, 75)).append("\r\n ");
                line = line.substring(75);
            }
            result.append(line).append("\r\n");
        }
        return result.toString();
    }
}
