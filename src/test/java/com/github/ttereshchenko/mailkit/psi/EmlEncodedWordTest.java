package com.github.ttereshchenko.mailkit.psi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmlEncodedWordTest {

    @Test
    void plainAsciiIsReturnedUnchanged() {
        assertEquals("Hello world", EmlEncodedWord.decode("Hello world"));
    }

    @Test
    void inputWithoutEncodedWordsFastPath() {
        assertEquals("no encoded words here", EmlEncodedWord.decode("no encoded words here"));
    }

    @Test
    void quotedPrintableEncodingDecodes() {
        assertEquals("Hello world", EmlEncodedWord.decode("=?UTF-8?Q?Hello_world?="));
    }

    @Test
    void base64EncodingDecodes() {
        assertEquals("Hello world", EmlEncodedWord.decode("=?UTF-8?B?SGVsbG8gd29ybGQ=?="));
    }

    @Test
    void quotedPrintableHexBytesDecode() {
        assertEquals("ä", EmlEncodedWord.decode("=?ISO-8859-1?Q?=E4?="));
    }

    @Test
    void underscoreDecodesToSpaceInQEncoding() {
        assertEquals("a b c", EmlEncodedWord.decode("=?UTF-8?Q?a_b_c?="));
    }

    @Test
    void adjacentEncodedWordsConsumeIntermediateWhitespace() {
        // Per RFC 2047, whitespace between two encoded words is consumed.
        assertEquals("HelloWorld", EmlEncodedWord.decode("=?UTF-8?Q?Hello?= =?UTF-8?Q?World?="));
    }

    @Test
    void encodedWordSurroundedByPlainTextPreservesSpaces() {
        assertEquals("Re: Hello world", EmlEncodedWord.decode("Re: =?UTF-8?Q?Hello_world?="));
    }

    @Test
    void multipleEncodedWordsWithDifferentCharsets() {
        assertEquals("AB", EmlEncodedWord.decode("=?US-ASCII?Q?A?= =?US-ASCII?Q?B?="));
    }

    @Test
    void malformedEncodedWordIsKeptAsLiteral() {
        // Invalid hex inside Q-encoding: decoder falls back to original text.
        var original = "=?UTF-8?Q?=ZZ?=";
        assertEquals(original, EmlEncodedWord.decode(original));
    }

    @Test
    void unknownCharsetIsKeptAsLiteral() {
        var original = "=?BOGUS-CHARSET-XYZ?Q?test?=";
        assertEquals(original, EmlEncodedWord.decode(original));
    }

    @Test
    void caseInsensitiveEncodingFlag() {
        assertEquals("Hello", EmlEncodedWord.decode("=?UTF-8?q?Hello?="));
        assertEquals("Hello", EmlEncodedWord.decode("=?UTF-8?b?SGVsbG8=?="));
    }

    @Test
    void nullInputThrows() {
        assertThrows(NullPointerException.class, () -> EmlEncodedWord.decode(null));
    }
}
