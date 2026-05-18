package com.github.ttereshchenko.mailkit.smtp.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled JSON for {@link SmtpAuditEntry}. Avoids adding a JSON-library dependency for what
 * is a structurally simple file. Tolerates only the keys we know about — unknown keys are
 * silently dropped, which keeps forward-compat with future fields manageable.
 */
public final class SmtpAuditJson {

    private SmtpAuditJson() {}

    public static String writeAll(List<SmtpAuditEntry> entries) {
        var builder = new StringBuilder();
        builder.append("[");
        for (var index = 0; index < entries.size(); index++) {
            if (index > 0) {
                builder.append(",");
            }
            writeEntry(builder, entries.get(index));
        }
        builder.append("]");
        return builder.toString();
    }

    public static List<SmtpAuditEntry> readAll(String json) {
        var reader = new Reader(json);
        reader.skipWhitespace();
        var entries = new ArrayList<SmtpAuditEntry>();
        if (!reader.tryConsume('[')) {
            return entries;
        }
        reader.skipWhitespace();
        if (reader.tryConsume(']')) {
            return entries;
        }
        while (true) {
            reader.skipWhitespace();
            entries.add(readEntry(reader));
            reader.skipWhitespace();
            if (reader.tryConsume(',')) {
                continue;
            }
            if (reader.tryConsume(']')) {
                break;
            }
            throw new IllegalArgumentException("expected ',' or ']' at " + reader.position());
        }
        return entries;
    }

    private static void writeEntry(StringBuilder builder, SmtpAuditEntry entry) {
        builder.append("{");
        writeStringField(builder, "timestamp", entry.timestamp().toString(), true);
        writeStringField(builder, "profileName", entry.profileName(), false);
        writeStringField(builder, "host", entry.host(), false);
        writeNumberField(builder, "port", entry.port(), false);
        writeStringField(builder, "tlsProtocol", entry.tlsProtocol(), false);
        writeStringField(builder, "tlsCipherSuite", entry.tlsCipherSuite(), false);
        writeStringField(builder, "authMechanism", entry.authMechanism(), false);
        writeStringField(builder, "envelopeFrom", entry.envelopeFrom(), false);
        builder.append(",\"recipients\":[");
        for (var index = 0; index < entry.recipients().size(); index++) {
            if (index > 0) {
                builder.append(",");
            }
            var recipient = entry.recipients().get(index);
            builder.append("{");
            writeStringField(builder, "address", recipient.address(), true);
            writeNumberField(builder, "code", recipient.code(), false);
            writeStringField(builder, "text", recipient.text(), false);
            writeBooleanField(builder, "accepted", recipient.accepted(), false);
            builder.append("}");
        }
        builder.append("]");
        writeNumberField(builder, "sourceBytes", entry.sourceBytes(), false);
        writeNumberField(builder, "durationMillis", entry.durationMillis(), false);
        writeStringField(builder, "stopAfterPhase", entry.stopAfterPhase(), false);
        writeBooleanField(builder, "dropAfter", entry.dropAfter(), false);
        writeBooleanField(builder, "success", entry.success(), false);
        writeStringField(builder, "errorKind", entry.errorKind(), false);
        writeStringField(builder, "errorPhase", entry.errorPhase(), false);
        writeStringField(builder, "errorMessage", entry.errorMessage(), false);
        builder.append("}");
    }

    private static SmtpAuditEntry readEntry(Reader reader) {
        if (!reader.tryConsume('{')) {
            throw new IllegalArgumentException("expected '{' at " + reader.position());
        }
        Instant timestamp = Instant.EPOCH;
        var profileName = "";
        var host = "";
        var port = 0;
        var tlsProtocol = "";
        var tlsCipher = "";
        var authMechanism = "";
        var envelopeFrom = "";
        var recipients = new ArrayList<SmtpAuditEntry.Recipient>();
        var sourceBytes = 0L;
        var durationMillis = 0L;
        var stopAfterPhase = "";
        var dropAfter = false;
        var success = false;
        var errorKind = "";
        var errorPhase = "";
        var errorMessage = "";

        while (true) {
            reader.skipWhitespace();
            if (reader.tryConsume('}')) {
                break;
            }
            var fieldName = reader.readString();
            reader.skipWhitespace();
            if (!reader.tryConsume(':')) {
                throw new IllegalArgumentException("expected ':' after field name " + fieldName);
            }
            reader.skipWhitespace();
            switch (fieldName) {
                case "timestamp" -> timestamp = Instant.parse(reader.readString());
                case "profileName" -> profileName = reader.readString();
                case "host" -> host = reader.readString();
                case "port" -> port = (int) reader.readLong();
                case "tlsProtocol" -> tlsProtocol = reader.readString();
                case "tlsCipherSuite" -> tlsCipher = reader.readString();
                case "authMechanism" -> authMechanism = reader.readString();
                case "envelopeFrom" -> envelopeFrom = reader.readString();
                case "recipients" -> recipients.addAll(readRecipientArray(reader));
                case "sourceBytes" -> sourceBytes = reader.readLong();
                case "durationMillis" -> durationMillis = reader.readLong();
                case "stopAfterPhase" -> stopAfterPhase = reader.readString();
                case "dropAfter" -> dropAfter = reader.readBoolean();
                case "success" -> success = reader.readBoolean();
                case "errorKind" -> errorKind = reader.readString();
                case "errorPhase" -> errorPhase = reader.readString();
                case "errorMessage" -> errorMessage = reader.readString();
                default -> reader.skipValue();
            }
            reader.skipWhitespace();
            if (reader.tryConsume(',')) {
                continue;
            }
        }
        return new SmtpAuditEntry(
                timestamp,
                profileName,
                host,
                port,
                tlsProtocol,
                tlsCipher,
                authMechanism,
                envelopeFrom,
                recipients,
                sourceBytes,
                durationMillis,
                stopAfterPhase,
                dropAfter,
                success,
                errorKind,
                errorPhase,
                errorMessage);
    }

    private static List<SmtpAuditEntry.Recipient> readRecipientArray(Reader reader) {
        var list = new ArrayList<SmtpAuditEntry.Recipient>();
        if (!reader.tryConsume('[')) {
            throw new IllegalArgumentException("expected '[' at " + reader.position());
        }
        reader.skipWhitespace();
        if (reader.tryConsume(']')) {
            return list;
        }
        while (true) {
            reader.skipWhitespace();
            list.add(readRecipient(reader));
            reader.skipWhitespace();
            if (reader.tryConsume(',')) {
                continue;
            }
            if (reader.tryConsume(']')) {
                break;
            }
            throw new IllegalArgumentException("expected ',' or ']' at " + reader.position());
        }
        return list;
    }

    private static SmtpAuditEntry.Recipient readRecipient(Reader reader) {
        if (!reader.tryConsume('{')) {
            throw new IllegalArgumentException("expected '{' at " + reader.position());
        }
        var address = "";
        var code = 0;
        var text = "";
        var accepted = false;
        while (true) {
            reader.skipWhitespace();
            if (reader.tryConsume('}')) {
                break;
            }
            var fieldName = reader.readString();
            reader.skipWhitespace();
            if (!reader.tryConsume(':')) {
                throw new IllegalArgumentException("expected ':' after " + fieldName);
            }
            reader.skipWhitespace();
            switch (fieldName) {
                case "address" -> address = reader.readString();
                case "code" -> code = (int) reader.readLong();
                case "text" -> text = reader.readString();
                case "accepted" -> accepted = reader.readBoolean();
                default -> reader.skipValue();
            }
            reader.skipWhitespace();
            if (reader.tryConsume(',')) {
                continue;
            }
        }
        return new SmtpAuditEntry.Recipient(address, code, text, accepted);
    }

    private static void writeStringField(StringBuilder builder, String key, String value, boolean first) {
        if (!first) {
            builder.append(",");
        }
        builder.append("\"").append(key).append("\":\"").append(escape(value)).append("\"");
    }

    private static void writeNumberField(StringBuilder builder, String key, long value, boolean first) {
        if (!first) {
            builder.append(",");
        }
        builder.append("\"").append(key).append("\":").append(value);
    }

    private static void writeBooleanField(StringBuilder builder, String key, boolean value, boolean first) {
        if (!first) {
            builder.append(",");
        }
        builder.append("\"").append(key).append("\":").append(value);
    }

    private static String escape(String value) {
        var builder = new StringBuilder(value.length());
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static final class Reader {

        private final String source;
        private int cursor;

        Reader(String source) {
            this.source = source;
        }

        int position() {
            return cursor;
        }

        void skipWhitespace() {
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
            }
        }

        boolean tryConsume(char expected) {
            if (cursor < source.length() && source.charAt(cursor) == expected) {
                cursor++;
                return true;
            }
            return false;
        }

        String readString() {
            if (cursor >= source.length() || source.charAt(cursor) != '"') {
                throw new IllegalArgumentException("expected string at " + cursor);
            }
            cursor++;
            var builder = new StringBuilder();
            while (cursor < source.length()) {
                var character = source.charAt(cursor);
                if (character == '\\' && cursor + 1 < source.length()) {
                    var escape = source.charAt(cursor + 1);
                    switch (escape) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'u' -> {
                            if (cursor + 6 > source.length()) {
                                throw new IllegalArgumentException("truncated unicode escape");
                            }
                            var code = Integer.parseInt(source.substring(cursor + 2, cursor + 6), 16);
                            builder.append((char) code);
                            cursor += 4;
                        }
                        default -> builder.append(escape);
                    }
                    cursor += 2;
                    continue;
                }
                if (character == '"') {
                    cursor++;
                    return builder.toString();
                }
                builder.append(character);
                cursor++;
            }
            throw new IllegalArgumentException("unterminated string at " + cursor);
        }

        long readLong() {
            var start = cursor;
            if (cursor < source.length() && (source.charAt(cursor) == '-' || source.charAt(cursor) == '+')) {
                cursor++;
            }
            while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) {
                cursor++;
            }
            if (cursor == start) {
                throw new IllegalArgumentException("expected number at " + cursor);
            }
            return Long.parseLong(source.substring(start, cursor));
        }

        boolean readBoolean() {
            if (source.startsWith("true", cursor)) {
                cursor += 4;
                return true;
            }
            if (source.startsWith("false", cursor)) {
                cursor += 5;
                return false;
            }
            throw new IllegalArgumentException("expected true/false at " + cursor);
        }

        void skipValue() {
            skipWhitespace();
            if (cursor >= source.length()) {
                return;
            }
            var character = source.charAt(cursor);
            if (character == '"') {
                readString();
            } else if (character == '{' || character == '[') {
                var open = character;
                var close = open == '{' ? '}' : ']';
                var depth = 1;
                cursor++;
                while (cursor < source.length() && depth > 0) {
                    var current = source.charAt(cursor);
                    if (current == '"') {
                        readString();
                        continue;
                    }
                    if (current == open) {
                        depth++;
                    } else if (current == close) {
                        depth--;
                    }
                    cursor++;
                }
            } else {
                while (cursor < source.length()) {
                    var current = source.charAt(cursor);
                    if (current == ',' || current == '}' || current == ']') {
                        return;
                    }
                    cursor++;
                }
            }
        }
    }
}
