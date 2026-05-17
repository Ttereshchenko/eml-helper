package com.github.ttereshchenko.mailkit.conversion.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MsgToEmlConverterHelpersTest {

    private Locale previousLocale;
    private TimeZone previousTimeZone;

    @BeforeEach
    void captureDefaults() {
        previousLocale = Locale.getDefault();
        previousTimeZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreDefaults() {
        Locale.setDefault(previousLocale);
        TimeZone.setDefault(previousTimeZone);
    }

    @Test
    void asciiHeaderPassesThrough() {
        assertEquals("Plain ASCII", MsgToEmlConverter.encodeHeaderIfNeeded("Plain ASCII"));
    }

    @Test
    void emptyHeaderReturnsEmpty() {
        assertEquals("", MsgToEmlConverter.encodeHeaderIfNeeded(""));
        assertEquals("", MsgToEmlConverter.encodeHeaderIfNeeded(null));
    }

    @Test
    void nonAsciiHeaderEncodedAsBase64Utf8() {
        var encoded = MsgToEmlConverter.encodeHeaderIfNeeded("Café résumé");
        assertTrue(encoded.startsWith("=?UTF-8?B?"));
        assertTrue(encoded.endsWith("?="));
        assertTrue(MsgToEmlConverter.isPureAscii(encoded));
    }

    @Test
    void formatRfc2822DateUsesEnglishLocale() {
        Locale.setDefault(Locale.forLanguageTag("fr-FR"));
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        var fixedInstant = 1715817600000L;
        var formatted = MsgToEmlConverter.formatRfc2822Date(new Date(fixedInstant));
        assertEquals("Thu, 16 May 2024 00:00:00 +0000", formatted);
    }

    @Test
    void formatAddressWithBothNameAndEmailUsesQuotedAscii() {
        assertEquals(
                "\"Jane Doe\" <jane@example.com>", MsgToEmlConverter.formatAddress("Jane Doe", "jane@example.com"));
    }

    @Test
    void formatAddressEscapesQuotesInAsciiName() {
        var formatted = MsgToEmlConverter.formatAddress("J\"D\"", "x@y");
        assertEquals("\"J\\\"D\\\"\" <x@y>", formatted);
    }

    @Test
    void formatAddressWithEmailOnlyOmitsName() {
        assertEquals("<who@example.com>", MsgToEmlConverter.formatAddress(null, "who@example.com"));
        assertEquals("<who@example.com>", MsgToEmlConverter.formatAddress("", "who@example.com"));
    }

    @Test
    void formatAddressWithUnicodeNameUsesRfc2047() {
        var formatted = MsgToEmlConverter.formatAddress("Café", "c@example.com");
        assertTrue(formatted.startsWith("=?UTF-8?B?"));
        assertTrue(formatted.contains(" <c@example.com>"));
        assertTrue(MsgToEmlConverter.isPureAscii(formatted));
    }

    @Test
    void isPureAsciiFlagsNonAsciiCharacters() {
        assertTrue(MsgToEmlConverter.isPureAscii("hello"));
        assertFalse(MsgToEmlConverter.isPureAscii("héllo"));
    }
}
