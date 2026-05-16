package com.github.ttereshchenko.mailkit.attachment;

public final class FilenameSanitizer {

    public static final String FALLBACK = "attachment";

    private FilenameSanitizer() {}

    public static String sanitize(String raw) {
        if (raw == null) {
            return FALLBACK;
        }
        var builder = new StringBuilder(raw.length());
        for (var index = 0; index < raw.length(); index++) {
            var character = raw.charAt(index);
            if (isForbidden(character)) {
                continue;
            }
            if (Character.isISOControl(character)) {
                continue;
            }
            builder.append(character);
        }
        var stripped = stripLeadingDots(builder.toString().trim());
        return stripped.isEmpty() ? FALLBACK : stripped;
    }

    private static boolean isForbidden(char character) {
        return switch (character) {
            case '\\', '/', ':', '*', '?', '"', '<', '>', '|' -> true;
            default -> false;
        };
    }

    private static String stripLeadingDots(String input) {
        var index = 0;
        while (index < input.length() && input.charAt(index) == '.') {
            index++;
        }
        return input.substring(index);
    }
}
