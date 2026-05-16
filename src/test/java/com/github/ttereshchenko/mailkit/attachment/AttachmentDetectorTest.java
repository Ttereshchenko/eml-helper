package com.github.ttereshchenko.mailkit.attachment;

import com.github.ttereshchenko.mailkit.psi.EmlMimePart;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class AttachmentDetectorTest extends BasePlatformTestCase {

    public void testBase64AttachmentQualifiesAndDecodes() throws DecodingException {
        var pdfBytes = "%PDF-1.4 fake content for tests".getBytes(StandardCharsets.UTF_8);
        var encoded = Base64.getMimeEncoder().encodeToString(pdfBytes);
        var content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n"
                + "--sep\n"
                + "Content-Type: application/pdf; name=\"invoice.pdf\"\n"
                + "Content-Disposition: attachment; filename=\"invoice.pdf\"\n"
                + "Content-Transfer-Encoding: base64\n\n"
                + encoded
                + "\n--sep--\n";
        var part = firstPart(content);
        var info = AttachmentDetector.detect(part).orElseThrow();
        assertEquals("invoice.pdf", info.filename());
        assertEquals(ContentTransferEncoding.BASE64, info.encoding());
        var decoded = AttachmentDecoder.decode(info.rawBody(), info.encoding());
        assertEquals(new String(pdfBytes, StandardCharsets.UTF_8), new String(decoded, StandardCharsets.UTF_8));
    }

    public void testQuotedPrintableTextPartWithFilenameQualifies() throws DecodingException {
        var content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n"
                + "--sep\n"
                + "Content-Type: text/plain; name=\"notes.txt\"\n"
                + "Content-Disposition: attachment; filename=\"notes.txt\"\n"
                + "Content-Transfer-Encoding: quoted-printable\n\n"
                + "Hello =E2=98=83 world\n"
                + "--sep--\n";
        var part = firstPart(content);
        var info = AttachmentDetector.detect(part).orElseThrow();
        assertEquals("notes.txt", info.filename());
        assertEquals(ContentTransferEncoding.QUOTED_PRINTABLE, info.encoding());
        var decoded = AttachmentDecoder.decode(info.rawBody(), info.encoding());
        var text = new String(decoded, StandardCharsets.UTF_8);
        assertTrue("Expected snowman in decoded text, got: " + text, text.startsWith("Hello ☃ world"));
    }

    public void testMissingFilenameFallsBackToAttachment() {
        var content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n"
                + "--sep\n"
                + "Content-Type: image/png\n"
                + "Content-Transfer-Encoding: base64\n\n"
                + "iVBORw0KGgo=\n"
                + "--sep--\n";
        var part = firstPart(content);
        var info = AttachmentDetector.detect(part).orElseThrow();
        assertEquals(FilenameSanitizer.FALLBACK, info.filename());
        assertEquals(ContentTransferEncoding.BASE64, info.encoding());
    }

    public void testFilenameWithIllegalCharsIsSanitized() {
        var content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n"
                + "--sep\n"
                + "Content-Type: application/octet-stream\n"
                + "Content-Disposition: attachment; filename=\"a/b\\\\c:d*e?f.bin\"\n"
                + "Content-Transfer-Encoding: base64\n\n"
                + "QUJD\n"
                + "--sep--\n";
        var part = firstPart(content);
        var info = AttachmentDetector.detect(part).orElseThrow();
        assertEquals("abcdef.bin", info.filename());
    }

    public void testPlainTextPartDoesNotQualify() {
        var content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n"
                + "--sep\n"
                + "Content-Type: text/plain; charset=utf-8\n\n"
                + "Just a plain text body line.\n"
                + "--sep--\n";
        var part = firstPart(content);
        assertTrue(AttachmentDetector.detect(part).isEmpty());
    }

    public void testNonTextNonMultipartQualifiesWithoutFilename() {
        var content = "Content-Type: multipart/mixed; boundary=\"sep\"\n\n"
                + "--sep\n"
                + "Content-Type: application/zip\n"
                + "Content-Transfer-Encoding: base64\n\n"
                + "UEsDBA==\n"
                + "--sep--\n";
        var part = firstPart(content);
        assertTrue(AttachmentDetector.detect(part).isPresent());
    }

    private EmlMimePart firstPart(String content) {
        var file = myFixture.configureByText("test.eml", content);
        var parts = PsiTreeUtil.findChildrenOfType(file, EmlMimePart.class);
        assertFalse("Expected at least one MIME part", parts.isEmpty());
        return parts.iterator().next();
    }
}
