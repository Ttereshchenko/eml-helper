package com.github.ttereshchenko.mailkit.smtp;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * RFC 3461 §4 xtext encoding, shared by the DSN parameters (ENVID, ORCPT) and Postfix XCLIENT
 * attribute values: every byte outside the printable ASCII range {@code '!'..'~'}, plus {@code +}
 * and {@code =}, is emitted as {@code +HH} (uppercase hex). Non-ASCII input is encoded via its
 * UTF-8 bytes.
 */
public final class Xtext {

    private static final HexFormat UPPER_HEX = HexFormat.of().withUpperCase();

    private Xtext() {}

    public static String encode(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var builder = new StringBuilder(bytes.length);
        for (var raw : bytes) {
            var unsigned = raw & 0xFF;
            if (unsigned >= '!' && unsigned <= '~' && unsigned != '+' && unsigned != '=') {
                builder.append((char) unsigned);
            } else {
                builder.append('+').append(UPPER_HEX.toHexDigits((byte) unsigned));
            }
        }
        return builder.toString();
    }
}
