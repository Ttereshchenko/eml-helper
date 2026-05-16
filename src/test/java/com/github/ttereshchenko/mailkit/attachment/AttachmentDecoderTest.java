package com.github.ttereshchenko.mailkit.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AttachmentDecoderTest {

    @Test
    void parseEncodingDefaultsTo7BitOnNullOrEmpty() {
        assertEquals(ContentTransferEncoding.BIT_7, ContentTransferEncoding.parse(null));
        assertEquals(ContentTransferEncoding.BIT_7, ContentTransferEncoding.parse(""));
        assertEquals(ContentTransferEncoding.BIT_7, ContentTransferEncoding.parse("unknown"));
    }

    @Test
    void parseEncodingMatchesKnownValues() {
        assertEquals(ContentTransferEncoding.BASE64, ContentTransferEncoding.parse("BASE64"));
        assertEquals(ContentTransferEncoding.QUOTED_PRINTABLE, ContentTransferEncoding.parse(" quoted-printable "));
        assertEquals(ContentTransferEncoding.BIT_8, ContentTransferEncoding.parse("8bit"));
        assertEquals(ContentTransferEncoding.BINARY, ContentTransferEncoding.parse("binary"));
        assertEquals(ContentTransferEncoding.BIT_7, ContentTransferEncoding.parse("7bit"));
    }

    @Test
    void decodeBase64RoundTripsBinaryPayload() throws DecodingException {
        var payload = new byte[256];
        for (var index = 0; index < payload.length; index++) {
            payload[index] = (byte) index;
        }
        var encoded = Base64.getMimeEncoder().encodeToString(payload);
        var decoded = AttachmentDecoder.decode(encoded, ContentTransferEncoding.BASE64);
        assertArrayEquals(payload, decoded);
    }

    @Test
    void decodeBase64IgnoresInternalWhitespaceAndCrlf() throws DecodingException {
        var input = "SGVsbG8s\r\nIE1haWxL\naXQh"; // "Hello, MailKit!" wrapped with mixed newlines
        var decoded = AttachmentDecoder.decode(input, ContentTransferEncoding.BASE64);
        assertEquals("Hello, MailKit!", new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    void decodeBase64ThrowsForInvalidCharacters() {
        assertThrows(
                DecodingException.class,
                () -> AttachmentDecoder.decode("!!!not_base64!!!*", ContentTransferEncoding.BASE64));
    }

    @Test
    void decodeQuotedPrintableHandlesHexEscapes() throws DecodingException {
        var decoded = AttachmentDecoder.decode("=E2=98=83 snowman", ContentTransferEncoding.QUOTED_PRINTABLE);
        assertEquals("☃ snowman", new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    void decodeQuotedPrintableHandlesSoftLineBreaks() throws DecodingException {
        var input = "Hello =\nworld =\r\nagain";
        var decoded = AttachmentDecoder.decode(input, ContentTransferEncoding.QUOTED_PRINTABLE);
        assertEquals("Hello world again", new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    void decodeQuotedPrintableAcceptsLowercaseHex() throws DecodingException {
        var decoded = AttachmentDecoder.decode("=e2=98=83", ContentTransferEncoding.QUOTED_PRINTABLE);
        assertEquals("☃", new String(decoded, StandardCharsets.UTF_8));
    }

    @Test
    void decodeQuotedPrintableThrowsForBadEscape() {
        assertThrows(
                DecodingException.class,
                () -> AttachmentDecoder.decode("Bad=ZZescape", ContentTransferEncoding.QUOTED_PRINTABLE));
    }

    @Test
    void decodeIdentityPreservesBytesFor7Bit() throws DecodingException {
        var input = "plain text body\nwith newlines";
        var decoded = AttachmentDecoder.decode(input, ContentTransferEncoding.BIT_7);
        assertArrayEquals(input.getBytes(StandardCharsets.ISO_8859_1), decoded);
    }

    @Test
    void decodeIdentityWorksFor8BitAndBinary() throws DecodingException {
        var input = "raw";
        assertArrayEquals(
                input.getBytes(StandardCharsets.ISO_8859_1),
                AttachmentDecoder.decode(input, ContentTransferEncoding.BIT_8));
        assertArrayEquals(
                input.getBytes(StandardCharsets.ISO_8859_1),
                AttachmentDecoder.decode(input, ContentTransferEncoding.BINARY));
    }

    @Test
    void decodeNullBodyReturnsEmpty() throws DecodingException {
        assertEquals(0, AttachmentDecoder.decode(null, ContentTransferEncoding.BASE64).length);
        assertEquals(0, AttachmentDecoder.decode(null, ContentTransferEncoding.QUOTED_PRINTABLE).length);
        assertEquals(0, AttachmentDecoder.decode(null, ContentTransferEncoding.BIT_7).length);
    }
}
