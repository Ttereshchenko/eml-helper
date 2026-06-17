package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class HtmlMetaCharsetTest {

    @Test
    void html5MetaCharsetIsRewritten() {
        assertEquals(
                "<html><head><meta charset=\"UTF-8\"></head><body>ok</body></html>",
                HtmlMetaCharset.rewriteToUtf8(
                        "<html><head><meta charset=\"windows-1251\"></head><body>ok</body></html>"));
    }

    @Test
    void legacyHttpEquivCharsetIsRewritten() {
        assertEquals(
                "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\">",
                HtmlMetaCharset.rewriteToUtf8(
                        "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=Shift_JIS\">"));
    }

    @Test
    void unquotedAndSingleQuotedFormsAreRewritten() {
        assertEquals("<meta charset=UTF-8>", HtmlMetaCharset.rewriteToUtf8("<meta charset=big5>"));
        assertEquals("<meta charset='UTF-8'>", HtmlMetaCharset.rewriteToUtf8("<meta charset='koi8-r'>"));
    }

    @Test
    void htmlWithoutDeclarationIsReturnedUnchanged() {
        var html = "<html><body>no meta here</body></html>";
        assertSame(html, HtmlMetaCharset.rewriteToUtf8(html));
    }

    @Test
    void charsetOutsideMetaTagsIsLeftAlone() {
        var html = "<p>the word charset=latin1 in prose</p>";
        assertSame(html, HtmlMetaCharset.rewriteToUtf8(html));
    }

    @Test
    void unrelatedAttributeEndingInCharsetIsNotRewritten() {
        // charset must be preceded by a real separator: a different attribute that merely ends in
        // "charset" (data-charset=) is left intact, while the genuine <meta charset> is still normalized.
        var html = "<meta data-charset=\"latin1\" charset=\"windows-1251\">";
        assertEquals("<meta data-charset=\"latin1\" charset=\"UTF-8\">", HtmlMetaCharset.rewriteToUtf8(html));
    }

    @Test
    void multiParameterContentTypeKeepsSeparatorAfterCharset() {
        // The ';' is the MIME parameter separator (rfc2045 §5.1); rewriting the charset must stop at it
        // rather than swallow it and merge the following parameter into the charset value.
        assertEquals(
                "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8; format=flowed\">",
                HtmlMetaCharset.rewriteToUtf8(
                        "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1250; format=flowed\">"));
    }
}
