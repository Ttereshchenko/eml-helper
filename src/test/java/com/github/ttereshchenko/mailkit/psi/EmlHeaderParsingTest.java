package com.github.ttereshchenko.mailkit.psi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmlHeaderParsingTest {

    @Test
    void headerNameForSimpleHeader() {
        assertEquals("From", EmlHeaderParsing.headerName("From: test@example.com"));
    }

    @Test
    void headerNameWithHyphen() {
        assertEquals("Content-Type", EmlHeaderParsing.headerName("Content-Type: text/plain"));
    }

    @Test
    void headerNameTrimsWhitespace() {
        assertEquals("From", EmlHeaderParsing.headerName("From : test"));
    }

    @Test
    void headerNameStopsAtFirstColon() {
        assertEquals("Subject", EmlHeaderParsing.headerName("Subject: Re: Hello"));
    }

    @Test
    void headerNameNoColonReturnsNull() {
        assertNull(EmlHeaderParsing.headerName("NoColonHere"));
    }

    @Test
    void headerNameColonAtStartReturnsNull() {
        assertNull(EmlHeaderParsing.headerName(":value"));
    }

    @Test
    void headerNameWhitespaceBeforeColonReturnsNull() {
        assertNull(EmlHeaderParsing.headerName(" : value"));
    }

    @Test
    void joinValueWithNoContinuations() {
        assertEquals("text/plain", EmlHeaderParsing.joinValue("Content-Type: text/plain", List.of()));
    }

    @Test
    void joinValueAppendsStrippedContinuations() {
        assertEquals(
                "multipart/mixed; boundary=\"abc\"",
                EmlHeaderParsing.joinValue("Content-Type: multipart/mixed;", List.of(" boundary=\"abc\"")));
    }

    @Test
    void mediaTypeStripsParams() {
        assertEquals("multipart/mixed", EmlHeaderParsing.mediaType("multipart/mixed; boundary=abc"));
    }

    @Test
    void mediaTypeLowerCases() {
        assertEquals("text/plain", EmlHeaderParsing.mediaType("Text/Plain"));
    }

    @Test
    void mediaTypeNullForBlank() {
        assertNull(EmlHeaderParsing.mediaType(""));
        assertNull(EmlHeaderParsing.mediaType(null));
    }

    @Test
    void mediaTypeParamUnquoted() {
        assertEquals("abc", EmlHeaderParsing.mediaTypeParam("multipart/mixed; boundary=abc", "boundary"));
    }

    @Test
    void mediaTypeParamQuoted() {
        assertEquals("a b c", EmlHeaderParsing.mediaTypeParam("multipart/mixed; boundary=\"a b c\"", "boundary"));
    }

    @Test
    void mediaTypeParamWithEscape() {
        assertEquals("a\"b", EmlHeaderParsing.mediaTypeParam("multipart/mixed; boundary=\"a\\\"b\"", "boundary"));
    }

    @Test
    void mediaTypeParamCaseInsensitive() {
        assertEquals("abc", EmlHeaderParsing.mediaTypeParam("multipart/mixed; BOUNDARY=abc", "boundary"));
    }

    @Test
    void mediaTypeParamSemicolonInsideQuotes() {
        assertEquals("a;b", EmlHeaderParsing.mediaTypeParam("multipart/mixed; boundary=\"a;b\"; other=z", "boundary"));
    }

    @Test
    void mediaTypeParamSecondParamAfterFirst() {
        assertEquals("utf-8", EmlHeaderParsing.mediaTypeParam("text/plain; boundary=abc; charset=utf-8", "charset"));
    }

    @Test
    void mediaTypeParamMissingReturnsNull() {
        assertNull(EmlHeaderParsing.mediaTypeParam("multipart/mixed", "boundary"));
    }

    @Test
    void isMultipartTrueForMultipart() {
        assertTrue(EmlHeaderParsing.isMultipart("multipart/mixed; boundary=abc"));
        assertTrue(EmlHeaderParsing.isMultipart("multipart/alternative"));
    }

    @Test
    void isMultipartFalseForNonMultipart() {
        assertEquals(false, EmlHeaderParsing.isMultipart("text/plain"));
        assertEquals(false, EmlHeaderParsing.isMultipart(null));
    }

    @Test
    void isMessageRfc822True() {
        assertTrue(EmlHeaderParsing.isMessageRfc822("message/rfc822"));
        assertTrue(EmlHeaderParsing.isMessageRfc822("Message/RFC822"));
    }
}
