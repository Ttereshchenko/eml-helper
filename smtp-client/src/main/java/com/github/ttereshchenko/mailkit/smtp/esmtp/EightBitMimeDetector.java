package com.github.ttereshchenko.mailkit.smtp.esmtp;

import java.io.IOException;
import java.io.InputStream;

/**
 * Streams a message and decides whether it contains any byte with the high bit set. The result
 * determines whether {@code BODY=8BITMIME} can be safely declared on the MAIL line and whether
 * the server's 8BITMIME advertisement is *required* to send without downgrade.
 */
public final class EightBitMimeDetector {

    private static final int CHUNK = 8192;

    private EightBitMimeDetector() {}

    public static boolean containsEightBitBytes(InputStream input) throws IOException {
        var buffer = new byte[CHUNK];
        int read;
        while ((read = input.read(buffer)) != -1) {
            for (var index = 0; index < read; index++) {
                if ((buffer[index] & 0x80) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
