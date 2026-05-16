package com.github.ttereshchenko.mailkit.attachment;

import java.util.Locale;

public enum ContentTransferEncoding {
    BIT_7,
    BIT_8,
    BINARY,
    BASE64,
    QUOTED_PRINTABLE;

    public static ContentTransferEncoding parse(String headerValue) {
        if (headerValue == null) {
            return BIT_7;
        }
        var trimmed = headerValue.trim().toLowerCase(Locale.ROOT);
        return switch (trimmed) {
            case "base64" -> BASE64;
            case "quoted-printable" -> QUOTED_PRINTABLE;
            case "8bit" -> BIT_8;
            case "binary" -> BINARY;
            case "7bit", "" -> BIT_7;
            default -> BIT_7;
        };
    }
}
