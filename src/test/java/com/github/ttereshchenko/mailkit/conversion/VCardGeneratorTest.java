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
    void nameComponentsCarryMiddlePrefixAndSuffix() {
        // RFC 6350 section 6.2.2: N = Family;Given;Additional;Prefixes;Suffixes. The middle name
        // (PR_MIDDLE_NAME), honorific prefix (PR_DISPLAY_NAME_PREFIX) and generational suffix
        // (PR_GENERATION) MAPI sources must fill components 3-5 instead of being dropped to empty.
        var card = VCardGenerator.generate(new VCardGenerator.Contact()
                .givenName("John")
                .surname("Smith")
                .middleName("Quincy")
                .namePrefix("Dr.")
                .nameSuffix("Jr."));
        assertTrue(card.contains("N:Smith;John;Quincy;Dr.;Jr."), card);
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

    // -----------------------------------------------------------------------
    // Round-22 audit tests
    // -----------------------------------------------------------------------

    // Fix VCARD-1 — TEL;TYPE= supports compound types (e.g. "work,fax").

    @Test
    void phonesWithCompoundTypeCarryAllTypeParts() {
        var card = VCardGenerator.generate(
                new VCardGenerator.Contact().phone("work,fax", "+1 555 0100").phone("pager", "+1 555 0200"));

        assertTrue(card.contains("TEL;TYPE=work,fax:+1 555 0100"), card);
        assertTrue(card.contains("TEL;TYPE=pager:+1 555 0200"), card);
    }

    // Fix VCARD-2 — ORG second component from PR_DEPARTMENT_NAME (RFC 2426 §3.5.5).

    @Test
    void orgWithDepartmentProducesStructuredOrgLine() {
        var card = VCardGenerator.generate(
                new VCardGenerator.Contact().company("Acme").department("Eng"));

        assertTrue(card.contains("ORG:Acme;Eng"), "company;department must form ORG structured value: " + card);
    }

    @Test
    void orgWithoutDepartmentHasNoTrailingSemicolon() {
        var card = VCardGenerator.generate(new VCardGenerator.Contact().company("Acme"));

        assertTrue(card.contains("ORG:Acme"), card);
        assertFalse(card.contains("ORG:Acme;"), "company-only ORG must not have a trailing semicolon: " + card);
    }

    @Test
    void orgWithDepartmentOnlyHasEmptyFirstComponent() {
        var card = VCardGenerator.generate(new VCardGenerator.Contact().department("Eng"));

        assertTrue(card.contains("ORG:;Eng"), "department-only ORG must have empty first component: " + card);
    }

    // Fix VCARD-3 — IMPP property (RFC 4770) from PidLidInstantMessagingAddress.

    @Test
    void imAddressEmitsImppProperty() {
        var card = VCardGenerator.generate(new VCardGenerator.Contact().imAddress("user@im"));

        assertTrue(card.contains("IMPP:user@im"), "imAddress must emit IMPP property: " + card);
    }

    @Test
    void absentImAddressOmitsImppProperty() {
        var card = VCardGenerator.generate(new VCardGenerator.Contact());

        assertFalse(card.contains("IMPP:"), "no IM address must not emit IMPP: " + card);
    }

    // -----------------------------------------------------------------------
    // Round-23 audit tests
    // -----------------------------------------------------------------------

    // Fix A-VCARD-2 — IMPP is URI-valued (RFC 4770); a URI's own ';'/',' must not be TEXT-escaped.

    @Test
    void imppUriKeepsSemicolonParametersUnescaped() {
        var card =
                VCardGenerator.generate(new VCardGenerator.Contact().imAddress("sip:jane@corp.example;transport=tls"));

        assertTrue(
                card.contains("IMPP:sip:jane@corp.example;transport=tls"),
                "IMPP URI parameters must survive unescaped: " + card);
        assertFalse(card.contains("\\;"), "an IMPP URI's ';' must not be TEXT-escaped: " + card);
    }

    @Test
    void imppUriKeepsCommaUnescaped() {
        var card = VCardGenerator.generate(
                new VCardGenerator.Contact().imAddress("xmpp:jane@corp.example?message;subject=one,two"));

        assertTrue(
                card.contains("IMPP:xmpp:jane@corp.example?message;subject=one,two"),
                "IMPP URI ',' must survive unescaped: " + card);
        assertFalse(card.contains("\\,"), "an IMPP URI's ',' must not be TEXT-escaped: " + card);
    }

    @Test
    void imppUriStripsControlCharactersToPreventInjection() {
        var card = VCardGenerator.generate(
                new VCardGenerator.Contact().imAddress("xmpp:jane@corp\r\nEND:VCARD\r\nBEGIN:VCARD"));

        assertTrue(
                card.contains("IMPP:xmpp:jane@corpEND:VCARDBEGIN:VCARD"),
                "CR/LF inside an IMPP URI must be stripped: " + card);
        assertFalse(
                card.contains("IMPP:xmpp:jane@corp\r\n"),
                "a stripped IMPP URI must not forge a folded/new content line: " + card);
    }

    @Test
    void imppUriDoublesBackslashForSafety() {
        var card = VCardGenerator.generate(new VCardGenerator.Contact().imAddress("aim:goim?screenname=jane\\smith"));

        assertTrue(
                card.contains("IMPP:aim:goim?screenname=jane\\\\smith"),
                "an IMPP URI's backslash must be doubled to keep vCard escaping unambiguous: " + card);
    }
}
