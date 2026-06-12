package com.github.ttereshchenko.mailkit.pst;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Maps Windows code-page identifiers (PR_INTERNET_CPID / PR_MESSAGE_CODEPAGE values) to Java
 * {@link Charset}s, preferring the Microsoft variant where it differs from the IANA one (e.g.
 * {@code windows-31j} over {@code Shift_JIS}, {@code x-windows-949} over {@code EUC-KR}), because
 * the bytes were written by Windows. Unknown ids fall back to {@code windows-1252} so decoding
 * always succeeds.
 */
final class CodePages {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private CodePages() {}

    static Charset defaultCharset() {
        return WINDOWS_1252;
    }

    static Charset charsetFor(int codePageId) {
        // ISO-8859 family: 28591 + n maps to ISO-8859-n (28602/-12 does not exist; the fallback
        // chain below degrades it to windows-1252 like any other unknown id).
        if (codePageId >= 28591 && codePageId <= 28606) {
            return firstSupported("ISO-8859-" + (codePageId - 28590));
        }
        return switch (codePageId) {
            case 437 -> firstSupported("IBM437");
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
            case 1361 -> firstSupported("x-Johab");
            case 10000 -> firstSupported("x-MacRoman");
            case 10007 -> firstSupported("x-MacCyrillic");
            case 20127 -> StandardCharsets.US_ASCII;
            case 20866 -> firstSupported("KOI8-R");
            case 21866 -> firstSupported("KOI8-U");
            case 50220, 50221, 50222 -> firstSupported("ISO-2022-JP");
            case 50225 -> firstSupported("ISO-2022-KR");
            case 51932 -> firstSupported("EUC-JP");
            case 51949 -> firstSupported("EUC-KR");
            case 54936 -> firstSupported("GB18030");
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
