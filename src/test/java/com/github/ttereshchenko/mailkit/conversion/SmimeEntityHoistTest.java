package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SmimeEntityHoistTest {

    // -----------------------------------------------------------------------
    // Full MIME entity: parsed from headers
    // -----------------------------------------------------------------------

    /**
     * A clear-signed S/MIME attachment whose first bytes are a valid RFC 5322 header block with a
     * Content-Type is hoisted verbatim: fromMimeHeaders==true, contentType/transferEncoding parsed
     * from the stored headers, disposition absent.
     */
    @Test
    void fullMimeEntityParsedFromHeaders() {
        var mimeBytes = ("Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\";"
                        + " micalg=sha-256; boundary=\"sig\"\r\n"
                        + "Content-Transfer-Encoding: 7bit\r\n"
                        + "\r\n"
                        + "<body>\r\n"
                        + "--sig--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);

        var entity = SmimeEntityHoist.hoist(mimeBytes, "smime.p7m", "");

        assertTrue(entity.fromMimeHeaders(), "must be recognised as a MIME entity");
        assertTrue(
                entity.contentType().startsWith("multipart/signed"),
                "contentType must be the stored Content-Type value: " + entity.contentType());
        assertTrue(
                entity.contentType().contains("boundary=\"sig\""),
                "boundary must be preserved: " + entity.contentType());
        assertEquals("7bit", entity.transferEncoding(), "transferEncoding must be parsed from headers");
        assertNull(entity.disposition(), "disposition must be absent for a MIME-parsed entity");
        assertTrue(entity.body().contains("<body>"), "body must survive: " + entity.body());
    }

    // -----------------------------------------------------------------------
    // Opaque blob: base64 pkcs7-mime envelope
    // -----------------------------------------------------------------------

    /**
     * When the stored bytes have no parseable RFC 5322 header block (first line has no colon) they
     * are treated as an opaque PKCS#7 blob: fromMimeHeaders==false, contentType derives from the
     * fallback MIME tag with the filename embedded, transferEncoding==base64, disposition names the
     * file, body is the wrapped base64 of the original bytes.
     */
    @Test
    void opaqueBlobBecomesBase64PkcsAttachment() {
        var blobBytes = new byte[] {0x30, 0x45, 0x02, 0x01, 0x00};
        var entity = SmimeEntityHoist.hoist(blobBytes, "smime.p7m", "application/pkcs7-mime");

        assertFalse(entity.fromMimeHeaders(), "opaque blob must not claim MIME-parsed");
        assertTrue(
                entity.contentType().startsWith("application/pkcs7-mime"),
                "contentType must carry the supplied MIME tag: " + entity.contentType());
        assertTrue(
                entity.contentType().contains("name=\"smime.p7m\""),
                "contentType must embed the filename: " + entity.contentType());
        assertEquals("base64", entity.transferEncoding(), "opaque blob must be base64");
        assertEquals("attachment; filename=\"smime.p7m\"", entity.disposition(), "disposition must name the file");
        var decoded = Base64.getMimeDecoder().decode(entity.body());
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                blobBytes, decoded, "base64 body must round-trip the original bytes");
    }

    // -----------------------------------------------------------------------
    // Blank fallbackFilename defaults to smime.p7m
    // -----------------------------------------------------------------------

    /**
     * When fallbackFilename is blank (or null) the opaque-blob branch substitutes the RFC 8551
     * default filename "smime.p7m".
     */
    @Test
    void blankFallbackFilenameDefaultsToSmimep7m() {
        var blobBytes = new byte[] {0x01, 0x02, 0x03};

        var entityBlank = SmimeEntityHoist.hoist(blobBytes, "", "application/pkcs7-mime");
        assertTrue(
                entityBlank.contentType().contains("name=\"smime.p7m\""),
                "blank filename must default to smime.p7m: " + entityBlank.contentType());
        assertTrue(
                entityBlank.disposition().contains("filename=\"smime.p7m\""),
                "disposition must use the default: " + entityBlank.disposition());

        var entityNull = SmimeEntityHoist.hoist(blobBytes, null, "application/pkcs7-mime");
        assertTrue(
                entityNull.contentType().contains("name=\"smime.p7m\""),
                "null filename must default to smime.p7m: " + entityNull.contentType());
    }

    // -----------------------------------------------------------------------
    // Bare-LF body is normalized to CRLF
    // -----------------------------------------------------------------------

    /**
     * A MIME entity whose body uses bare LF line endings (not CRLF) must have its line endings
     * normalized to CRLF in the hoisted body.
     */
    @Test
    void bareLfBodyIsNormalizedToCrlf() {
        var lfMimeBytes = ("Content-Type: multipart/signed; boundary=\"b\"\n"
                        + "\n"
                        + "line1\n"
                        + "line2\n"
                        + "--b--\n")
                .getBytes(StandardCharsets.ISO_8859_1);

        var entity = SmimeEntityHoist.hoist(lfMimeBytes, "smime.p7m", "");

        assertTrue(entity.fromMimeHeaders(), "bare-LF entity must still be parsed from headers");
        assertTrue(
                entity.body().contains("line1\r\nline2"),
                "body LF endings must be normalized to CRLF: " + entity.body());
        assertFalse(entity.body().replace("\r\n", "").contains("\n"), "no bare LF must remain after normalization");
    }

    // -----------------------------------------------------------------------
    // Folded Content-Type header is unfolded into one value
    // -----------------------------------------------------------------------

    /**
     * An RFC 5322 folded Content-Type header (continuation line starts with whitespace) must be
     * unfolded into a single value without the folding whitespace injected literally.
     */
    @Test
    void foldedContentTypeIsUnfolded() {
        var foldedMimeBytes = ("Content-Type: multipart/signed;\r\n"
                        + " protocol=\"application/pkcs7-signature\";\r\n"
                        + " micalg=sha-256;\r\n"
                        + " boundary=\"fold\"\r\n"
                        + "\r\n"
                        + "body\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);

        var entity = SmimeEntityHoist.hoist(foldedMimeBytes, "smime.p7m", "");

        assertTrue(entity.fromMimeHeaders(), "folded entity must be parsed from headers");
        assertTrue(
                entity.contentType().contains("multipart/signed"),
                "unfolded contentType must contain the type: " + entity.contentType());
        assertTrue(
                entity.contentType().contains("boundary=\"fold\""),
                "unfolded contentType must contain the boundary: " + entity.contentType());
        assertFalse(
                entity.contentType().contains("\r\n"),
                "contentType must not contain folding sequences: " + entity.contentType());
    }
}
