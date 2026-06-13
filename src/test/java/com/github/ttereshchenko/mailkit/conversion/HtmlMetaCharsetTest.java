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
}
