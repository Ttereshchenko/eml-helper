package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * F3 regression: code pages outside the original dozen special cases (the ISO-8859 family beyond
 * -1/-2, KOI8, DOS code pages, Mac Roman) used to fall back to windows-1252 and mojibake any
 * non-Western ANSI store; the CJK pages mapped to IANA charsets instead of the Microsoft variants
 * the bytes were actually written in.
 */
class CodePagesTest {

    @Test
    void mapsIsoFamilyKoi8AndDosCodePages() {
        assertEquals(Charset.forName("ISO-8859-5"), CodePages.charsetFor(28595));
        assertEquals(Charset.forName("ISO-8859-7"), CodePages.charsetFor(28597));
        assertEquals(Charset.forName("ISO-8859-15"), CodePages.charsetFor(28605));
        assertEquals(Charset.forName("KOI8-R"), CodePages.charsetFor(20866));
        assertEquals(Charset.forName("KOI8-U"), CodePages.charsetFor(21866));
        assertEquals(Charset.forName("IBM866"), CodePages.charsetFor(866));
        assertEquals(Charset.forName("IBM437"), CodePages.charsetFor(437));
        assertEquals(Charset.forName("x-MacRoman"), CodePages.charsetFor(10000));
    }

    @Test
    void prefersMicrosoftVariantsForCjkCodePages() {
        assertEquals(Charset.forName("windows-31j"), CodePages.charsetFor(932));
        assertEquals(Charset.forName("GBK"), CodePages.charsetFor(936));
        assertEquals(Charset.forName("x-windows-949"), CodePages.charsetFor(949));
        assertEquals(Charset.forName("x-windows-950"), CodePages.charsetFor(950));
        assertEquals(Charset.forName("GB18030"), CodePages.charsetFor(54936));
    }

    @Test
    void mapsWindowsUnicodeAndIsoEscapeCodePages() {
        assertEquals(Charset.forName("windows-1251"), CodePages.charsetFor(1251));
        assertEquals(Charset.forName("windows-874"), CodePages.charsetFor(874));
        assertEquals(StandardCharsets.UTF_8, CodePages.charsetFor(65001));
        assertEquals(StandardCharsets.UTF_16LE, CodePages.charsetFor(1200));
        assertEquals(Charset.forName("ISO-2022-JP"), CodePages.charsetFor(50220));
    }

    @Test
    void unknownCodePagesDegradeToWindows1252() {
        assertEquals(Charset.forName("windows-1252"), CodePages.charsetFor(99999));
        assertEquals(Charset.forName("windows-1252"), CodePages.charsetFor(0));
        assertEquals(Charset.forName("windows-1252"), CodePages.charsetFor(-1));
    }

    @Test
    void cyrillicBytesRoundTripThroughTheMappedCharset() {
        var text = "Привет";
        var bytes = text.getBytes(CodePages.charsetFor(1251));
        assertEquals(text, new String(bytes, CodePages.charsetFor(1251)));
        // The same bytes decoded with the old fallback would be mojibake, not equality.
        assertEquals(6, bytes.length, "windows-1251 encodes Cyrillic as single bytes");
    }
}
