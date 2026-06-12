package com.github.ttereshchenko.mailkit.conversion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
