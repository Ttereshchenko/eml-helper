package com.github.ttereshchenko.mailkit.smtp;

import java.util.List;
import java.util.Objects;

/**
 * A parsed SMTP server response: the numeric code (e.g. 250, 354, 550) and the list of text
 * lines that followed it (multi-line responses join {@code 250-...} continuations with a final
 * {@code 250 ...}).
 */
public record SmtpResponse(int code, List<String> lines) {

    public SmtpResponse {
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
    }

    public boolean isPositiveCompletion() {
        return code >= 200 && code <= 299;
    }

    public boolean isPositiveIntermediate() {
        return code >= 300 && code <= 399;
    }

    public boolean isTransientNegative() {
        return code >= 400 && code <= 499;
    }

    public boolean isPermanentNegative() {
        return code >= 500 && code <= 599;
    }

    public String firstLine() {
        return lines.isEmpty() ? "" : lines.get(0);
    }
}
