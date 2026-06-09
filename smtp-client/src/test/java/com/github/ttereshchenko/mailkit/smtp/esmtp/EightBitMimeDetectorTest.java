package com.github.ttereshchenko.mailkit.smtp.esmtp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EightBitMimeDetectorTest {

    @Test
    void asciiOnlyBodyDoesNotRequire8bitMime() throws Exception {
        var input = "Subject: hi\r\n\r\nplain ascii body\r\n".getBytes(StandardCharsets.US_ASCII);
        assertFalse(EightBitMimeDetector.containsEightBitBytes(new ByteArrayInputStream(input)));
    }

    @Test
    void highBitByteAnywhereInBodyRequires8bitMime() throws Exception {
        var input = "Subject: hi\r\n\r\nbody with high-bit: é\r\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(EightBitMimeDetector.containsEightBitBytes(new ByteArrayInputStream(input)));
    }
}
