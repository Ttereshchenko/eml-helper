package com.github.ttereshchenko.mailkit.smtp.esmtp;

import java.util.List;
import java.util.OptionalLong;

/**
 * Honours the server's SIZE advertisement (RFC 1870). When the server advertises an upper bound
 * AND the message source can report its length up front, the client refuses before MAIL rather
 * than streaming bytes the server will discard.
 */
public final class SizePreflight {

    private SizePreflight() {}

    public static OptionalLong advertisedLimit(List<String> sizeArguments) {
        if (sizeArguments == null || sizeArguments.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(sizeArguments.get(0)));
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }

    /**
     * Returns true when the declared message size exceeds the server's advertised SIZE.
     * RFC 1870 §3: an advertised value of zero means "no fixed maximum is in force, but the
     * server has volunteered that fact" — treat it as unlimited, never as a hard zero-byte cap.
     */
    public static boolean exceedsLimit(long messageSize, OptionalLong advertised) {
        if (advertised.isEmpty() || advertised.getAsLong() == 0L) {
            return false;
        }
        return messageSize > advertised.getAsLong();
    }
}
