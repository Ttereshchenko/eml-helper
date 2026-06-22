package com.github.ttereshchenko.mailkit.comparison;

import com.github.ttereshchenko.mailkit.psi.EmlPsiFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Verifies that {@link EmlNormalizer} renders a parsed EML message into the canonical, decoded form
 * used for semantic comparison. Each test consumes a sample under
 * {@code src/test/resources/samples/eml/compare/} so the same fixtures can be opened in the IDE to
 * exercise the Compare EML actions manually.
 */
public class EmlNormalizerTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/resources/samples";
    }

    public void testEncodedWordSubjectIsDecoded() {
        var normalized = normalize("eml/compare/compare_encoded_word_subject.eml");
        assertTrue(normalized, normalized.contains("Subject: Réservation confirmée"));
    }

    public void testBase64AndQuotedPrintableBodiesNormalizeEqual() {
        var base64 = normalize("eml/compare/compare_base64_body.eml");
        var quotedPrintable = normalize("eml/compare/compare_quoted_printable_body.eml");
        // Same message, two transfer encodings: the decoded, normalized form must be identical.
        assertEquals(quotedPrintable, base64);
        assertTrue(base64, base64.contains("Hello, MailKit comparison!"));
    }

    public void testReorderedHeadersAndBoundaryNormalizeEqual() {
        var first = normalize("eml/compare/compare_headers_reordered_a.eml");
        var second = normalize("eml/compare/compare_headers_reordered_b.eml");
        // Same message with headers reordered and a different random boundary token: still equal.
        assertEquals(first, second);
    }

    public void testAttachmentManifestIncludesTypeSizeAndSha() {
        var normalized = normalize("eml/compare/compare_multipart_attachment.eml");
        assertTrue(normalized, normalized.contains("═══ ATTACHMENTS ═══"));
        assertTrue(normalized, normalized.contains("report.csv | text/csv | 16 bytes | sha256="));
        // The inline text body is shown decoded, not listed as an attachment.
        assertTrue(normalized, normalized.contains("See attached report."));
    }

    public void testNestedMessageAppearsInStructure() {
        var normalized = normalize("eml/compare/compare_nested_rfc822.eml");
        assertTrue(normalized, normalized.contains("═══ STRUCTURE ═══"));
        assertTrue(normalized, normalized.contains("message/rfc822"));
    }

    public void testNestedMessageHeadersAndBodyAreCompared() {
        var normalized = normalize("eml/compare/compare_nested_rfc822.eml");
        // The embedded message (journaled / forwarded) must contribute its own headers and body, not
        // just appear as a line in the structure tree.
        assertTrue(normalized, normalized.contains("═══ NESTED MESSAGE 1 ═══"));
        assertTrue(normalized, normalized.contains("Subject: Original"));
        assertTrue(normalized, normalized.contains("Original inner body."));
    }

    private String normalize(String samplePath) {
        PsiFile file = myFixture.configureByFile(samplePath);
        return ApplicationManager.getApplication()
                .runReadAction((Computable<String>) () -> EmlNormalizer.normalize((EmlPsiFile) file));
    }
}
