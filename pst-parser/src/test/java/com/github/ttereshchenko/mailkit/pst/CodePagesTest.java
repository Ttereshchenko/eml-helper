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

    /**
     * C1 regression: these ids used to degrade to windows-1252 even though the JDK ships a charset
     * for each — most damagingly the Simplified-Chinese internet pages (GB2312/EUC-CN), which are
     * common in older Chinese mail.
     */
    @Test
    void mapsSimplifiedChineseJapaneseArabicHebrewAndIndicPages() {
        assertEquals(Charset.forName("GB2312"), CodePages.charsetFor(20936));
        assertEquals(Charset.forName("GB2312"), CodePages.charsetFor(51936));
        assertEquals(Charset.forName("ISO-2022-CN"), CodePages.charsetFor(50227));
        assertEquals(Charset.forName("EUC-JP"), CodePages.charsetFor(20932));
        assertEquals(Charset.forName("ISO-8859-6"), CodePages.charsetFor(708));
        assertEquals(Charset.forName("ISO-8859-8"), CodePages.charsetFor(38598));
        assertEquals(Charset.forName("x-ISCII91"), CodePages.charsetFor(57002));
    }

    /** C1 regression: only Mac Roman and Mac Cyrillic of the Macintosh family were mapped. */
    @Test
    void mapsMacintoshCodePages() {
        assertEquals(Charset.forName("x-MacArabic"), CodePages.charsetFor(10004));
        assertEquals(Charset.forName("x-MacHebrew"), CodePages.charsetFor(10005));
        assertEquals(Charset.forName("x-MacGreek"), CodePages.charsetFor(10006));
        assertEquals(Charset.forName("x-MacRomania"), CodePages.charsetFor(10010));
        assertEquals(Charset.forName("x-MacUkraine"), CodePages.charsetFor(10017));
        assertEquals(Charset.forName("x-MacThai"), CodePages.charsetFor(10021));
        assertEquals(Charset.forName("x-MacCentralEurope"), CodePages.charsetFor(10029));
        assertEquals(Charset.forName("x-MacIceland"), CodePages.charsetFor(10079));
        assertEquals(Charset.forName("x-MacTurkish"), CodePages.charsetFor(10081));
        assertEquals(Charset.forName("x-MacCroatian"), CodePages.charsetFor(10082));
    }

    @Test
    void simplifiedChineseBytesRoundTripThroughGb2312() {
        var text = "中文邮件";
        var bytes = text.getBytes(CodePages.charsetFor(51936));
        assertEquals(text, new String(bytes, CodePages.charsetFor(51936)));
        assertEquals(8, bytes.length, "GB2312 encodes CJK as two-byte sequences");
    }

    // Review finding #3: these ids have a JDK charset but used to degrade to windows-1252 because
    // no case (and no "windows-N"/"cpN" alias) matched them.
    @Test
    void mapsJdkSupportedEbcdicUtf32AndCjkVariantPages() {
        assertEquals(Charset.forName("IBM037"), CodePages.charsetFor(37));
        assertEquals(Charset.forName("UTF-32LE"), CodePages.charsetFor(12000));
        assertEquals(Charset.forName("UTF-32BE"), CodePages.charsetFor(12001));
        assertEquals(Charset.forName("IBM273"), CodePages.charsetFor(20273));
        assertEquals(Charset.forName("IBM420"), CodePages.charsetFor(20420));
        assertEquals(Charset.forName("IBM424"), CodePages.charsetFor(20424));
        assertEquals(Charset.forName("IBM-Thai"), CodePages.charsetFor(20838));
        assertEquals(Charset.forName("x-IBM970"), CodePages.charsetFor(20949));
        assertEquals(Charset.forName("x-IBM1025"), CodePages.charsetFor(21025));
        assertEquals(Charset.forName("x-ISO-2022-CN-CNS"), CodePages.charsetFor(50229));
        assertEquals(Charset.forName("x-EUC-TW"), CodePages.charsetFor(51950));
    }

    // The EBCDIC id band must not swallow the non-EBCDIC 20xxx pages that sit between its members.
    @Test
    void ebcdicBandDoesNotShadowNonEbcdicTwentyThousandPages() {
        assertEquals(Charset.forName("KOI8-R"), CodePages.charsetFor(20866));
        assertEquals(Charset.forName("EUC-JP"), CodePages.charsetFor(20932));
        assertEquals(Charset.forName("GB2312"), CodePages.charsetFor(20936));
        assertEquals(StandardCharsets.US_ASCII, CodePages.charsetFor(20127));
    }
}
