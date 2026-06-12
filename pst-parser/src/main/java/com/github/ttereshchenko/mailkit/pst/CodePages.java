package com.github.ttereshchenko.mailkit.pst;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps Windows code-page identifiers (PR_INTERNET_CPID / PR_MESSAGE_CODEPAGE values) to Java
 * {@link Charset}s, preferring the Microsoft variant where it differs from the IANA one (e.g.
 * {@code windows-31j} over {@code Shift_JIS}, {@code x-windows-949} over {@code EUC-KR}), because
 * the bytes were written by Windows. Unknown ids fall back to {@code windows-1252} so decoding
 * always succeeds; the degradation is logged once per id so silent mojibake is diagnosable.
 */
final class CodePages {

    private static final System.Logger LOG = System.getLogger(CodePages.class.getName());

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    // Ids whose windows-1252 degradation has already been logged, so a store full of messages in an
    // unsupported code page produces one diagnostic instead of thousands.
    private static final Set<Integer> WARNED_FALLBACK_IDS = ConcurrentHashMap.newKeySet();

    private CodePages() {}

    static Charset defaultCharset() {
        return WINDOWS_1252;
    }

    static Charset charsetFor(int codePageId) {
        var charset = resolve(codePageId);
        if (codePageId != 1252 && charset.equals(WINDOWS_1252) && WARNED_FALLBACK_IDS.add(codePageId)) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    () -> "Unsupported code page " + codePageId
                            + "; decoding as windows-1252 — non-ASCII text may be garbled");
        }
        return charset;
    }

    private static Charset resolve(int codePageId) {
        // ISO-8859 family: 28591 + n maps to ISO-8859-n (28602/-12 does not exist; the fallback
        // chain below degrades it to windows-1252 like any other unknown id).
        if (codePageId >= 28591 && codePageId <= 28606) {
            return firstSupported("ISO-8859-" + (codePageId - 28590));
        }
        return switch (codePageId) {
            case 37 -> firstSupported("IBM037"); // EBCDIC US-Canada ("cp37" is not a JDK alias)
            case 437 -> firstSupported("IBM437");
            case 708 -> firstSupported("ISO-8859-6"); // ASMO 708 is ISO-8859-6 Arabic
            case 850 -> firstSupported("IBM850");
            case 852 -> firstSupported("IBM852");
            case 855 -> firstSupported("IBM855");
            case 857 -> firstSupported("IBM857");
            case 866 -> firstSupported("IBM866");
            case 869 -> firstSupported("IBM869");
            case 874 -> firstSupported("x-windows-874", "TIS-620");
            case 932 -> firstSupported("windows-31j", "Shift_JIS");
            case 936 -> firstSupported("GBK");
            case 949 -> firstSupported("x-windows-949", "EUC-KR");
            case 950 -> firstSupported("x-windows-950", "Big5");
            case 1200 -> StandardCharsets.UTF_16LE;
            case 1201 -> StandardCharsets.UTF_16BE;
            case 12000 -> firstSupported("UTF-32LE");
            case 12001 -> firstSupported("UTF-32BE");
            case 1361 -> firstSupported("x-Johab");
            case 10000 -> firstSupported("x-MacRoman");
            case 10004 -> firstSupported("x-MacArabic");
            case 10005 -> firstSupported("x-MacHebrew");
            case 10006 -> firstSupported("x-MacGreek");
            case 10007 -> firstSupported("x-MacCyrillic");
            case 10010 -> firstSupported("x-MacRomania");
            case 10017 -> firstSupported("x-MacUkraine");
            case 10021 -> firstSupported("x-MacThai");
            case 10029 -> firstSupported("x-MacCentralEurope");
            case 10079 -> firstSupported("x-MacIceland");
            case 10081 -> firstSupported("x-MacTurkish");
            case 10082 -> firstSupported("x-MacCroatian");
            case 20127 -> StandardCharsets.US_ASCII;
            // EBCDIC national pages map to the JDK's IBM charsets by stripping the 20000 offset
            // (20273 -> IBM273 ... 20924 -> cp924). Practically absent from real mail stores;
            // mapped for completeness. 20866/20932/20936 are NOT EBCDIC and keep their cases below.
            case 20273,
                    20277,
                    20278,
                    20280,
                    20284,
                    20285,
                    20290,
                    20297,
                    20420,
                    20423,
                    20424,
                    20833,
                    20838,
                    20871,
                    20880,
                    20905,
                    20924 -> {
                int ibmId = codePageId - 20000;
                yield firstSupported("IBM" + ibmId, "x-IBM" + ibmId, "cp" + ibmId);
            }
            case 20866 -> firstSupported("KOI8-R");
            case 20932 -> firstSupported("EUC-JP"); // JIS X 0208-1990 & 0212-1990
            case 20936, 51936 -> firstSupported("GB2312", "EUC-CN"); // Simplified Chinese (GB2312-80 / EUC-CN)
            case 20949 -> firstSupported("x-IBM970", "EUC-KR"); // Korean Wansung
            case 21025 -> firstSupported("x-IBM1025"); // EBCDIC Cyrillic
            case 21866 -> firstSupported("KOI8-U");
            case 38598 -> firstSupported("ISO-8859-8"); // ISO-8859-8-I (logical Hebrew); same bytes
            case 50220, 50221, 50222 -> firstSupported("ISO-2022-JP");
            case 50225 -> firstSupported("ISO-2022-KR");
            case 50227 -> firstSupported("ISO-2022-CN", "x-ISO-2022-CN-GB");
            case 50229 -> firstSupported("x-ISO-2022-CN-CNS", "ISO-2022-CN"); // Traditional Chinese ISO-2022
            case 51932 -> firstSupported("EUC-JP");
            case 51949 -> firstSupported("EUC-KR");
            case 51950 -> firstSupported("x-EUC-TW"); // Traditional Chinese EUC (CNS 11643)
            case 54936 -> firstSupported("GB18030");
            case 57002 -> firstSupported("x-ISCII91"); // ISCII Devanagari
            case 65001 -> StandardCharsets.UTF_8;
            default -> firstSupported("windows-" + codePageId, "cp" + codePageId);
        };
    }

    private static Charset firstSupported(String... names) {
        for (var name : names) {
            try {
                return Charset.forName(name);
            } catch (Exception ignored) {
                // try the next candidate name
            }
        }
        return WINDOWS_1252;
    }
}
