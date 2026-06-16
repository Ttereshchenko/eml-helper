package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** F4 coverage: vCard 3.0 generation for exported contacts, including RFC 2426 TEXT escaping. */
class VCardGeneratorTest {

    @Test
    void fullContactCarriesNameOrgTitleEmailsAndPhones() {
        var card = VCardGenerator.generate(new VCardGenerator.Contact()
                .displayName("Sebastian Wright")
                .givenName("Sebastian")
                .surname("Wright")
                .company("Example Corp")
                .jobTitle("Engineer")
                .email("sebastian@example.com")
                .email("sw@example.org")
                .phone("work", "+1 555 0100")
                .phone("cell", "+1 555 0101"));

        assertTrue(card.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"), card);
        assertTrue(card.contains("FN:Sebastian Wright"), card);
        assertTrue(card.contains("N:Wright;Sebastian;;;"), card);
        assertTrue(card.contains("ORG:Example Corp"), card);
        assertTrue(card.contains("TITLE:Engineer"), card);
        assertTrue(card.contains("EMAIL;TYPE=internet:sebastian@example.com"), card);
        assertTrue(card.contains("EMAIL;TYPE=internet:sw@example.org"), card);
        assertTrue(card.contains("TEL;TYPE=work:+1 555 0100"), card);
        assertTrue(card.contains("TEL;TYPE=cell:+1 555 0101"), card);
        assertTrue(card.endsWith("END:VCARD\r\n"), card);
    }

    @Test
    void formattedNameFallsBackToGivenAndSurname() {
        var card = VCardGenerator.generate(
                new VCardGenerator.Contact().givenName("Ada").surname("Lovelace"));
        assertTrue(card.contains("FN:Ada Lovelace"), card);
    }

    @Test
    void textValuesAreEscapedSoStructureCannotBreak() {
        var card = VCardGenerator.generate(new VCardGenerator.Contact()
                .displayName("Wright; Sebastian, Jr.\nBackslash\\Test")
                .company("A;B,C"));

        assertTrue(card.contains("FN:Wright\\; Sebastian\\, Jr.\\nBackslash\\\\Test"), card);
        assertTrue(card.contains("ORG:A\\;B\\,C"), card);
        assertFalse(card.contains("\nBackslash"), "A raw newline would split the property line");
    }

    @Test
    void longAndNonAsciiLinesAreFoldedAtSeventyFiveOctets() {
        // RFC 6350 §3.2 / RFC 2426 §2.6 fold a content line longer than 75 octets with CRLF + a space,
        // counting octets (not chars) and never splitting a multi-byte UTF-8 sequence.
        var longCompany = "Στρατός ".repeat(20).trim(); // multi-byte: octets >> chars
        var card = VCardGenerator.generate(new VCardGenerator.Contact()
                .displayName("A name long enough that the formatted-name line must be folded across two lines")
                .company(longCompany));

        for (var line : card.split("\r\n")) {
            assertTrue(
                    line.getBytes(StandardCharsets.UTF_8).length <= 75,
                    "folded vCard line exceeds 75 octets: " + line.getBytes(StandardCharsets.UTF_8).length);
            if (!line.isEmpty()) {
                assertFalse(Character.isLowSurrogate(line.charAt(0)), "fold split a code point");
                assertFalse(Character.isHighSurrogate(line.charAt(line.length() - 1)), "fold split a code point");
            }
        }
        // Unfolding (CRLF + leading space removed) must reproduce the ORG value intact.
        var unfolded = card.replace("\r\n ", "");
        assertTrue(
                unfolded.contains("ORG:" + escapeForVcard(longCompany)), "unfolded ORG must round-trip: " + unfolded);
    }

    private static String escapeForVcard(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,");
    }
}
