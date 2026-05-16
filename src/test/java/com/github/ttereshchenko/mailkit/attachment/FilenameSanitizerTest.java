package com.github.ttereshchenko.mailkit.attachment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FilenameSanitizerTest {

    @Test
    void stripsForbiddenCharacters() {
        assertEquals("abcdefghij", FilenameSanitizer.sanitize("a/b\\c:d*e?f\"g<h>i|j"));
    }

    @Test
    void stripsLeadingDots() {
        assertEquals("secret.tar", FilenameSanitizer.sanitize("...secret.tar"));
    }

    @Test
    void preservesInternalDots() {
        assertEquals("invoice.final.pdf", FilenameSanitizer.sanitize("invoice.final.pdf"));
    }

    @Test
    void emptyAfterStrippingReturnsFallback() {
        assertEquals(FilenameSanitizer.FALLBACK, FilenameSanitizer.sanitize("///\\?:"));
        assertEquals(FilenameSanitizer.FALLBACK, FilenameSanitizer.sanitize("..."));
        assertEquals(FilenameSanitizer.FALLBACK, FilenameSanitizer.sanitize(""));
    }

    @Test
    void nullInputReturnsFallback() {
        assertEquals(FilenameSanitizer.FALLBACK, FilenameSanitizer.sanitize(null));
    }

    @Test
    void stripsAsciiControlCharacters() {
        var input = "abcd";
        assertEquals("abcd", FilenameSanitizer.sanitize(input));
    }
}
