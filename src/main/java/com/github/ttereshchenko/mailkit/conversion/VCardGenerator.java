package com.github.ttereshchenko.mailkit.conversion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a vCard 3.0 (RFC 2426) for a contact exported from an Outlook store. Property values
 * are TEXT-escaped per RFC 2426 §2.4.2 (backslash, semicolon, comma, newline) so a crafted contact
 * field cannot break the card structure or inject properties.
 */
public final class VCardGenerator {

    /** The contact fields a PST/MSG contact maps onto; any field may be {@code null} or blank. */
    public static final class Contact {
        private String displayName;
        private String givenName;
        private String surname;
        private String middleName;
        private String namePrefix;
        private String nameSuffix;
        private String company;
        private String jobTitle;
        private final List<String> emails = new ArrayList<>();
        private final Map<String, String> phonesByType = new LinkedHashMap<>();

        public Contact displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Contact givenName(String value) {
            this.givenName = value;
            return this;
        }

        public Contact surname(String value) {
            this.surname = value;
            return this;
        }

        /** Sets the middle name(s) — the "Additional Names" component of the vCard N property. */
        public Contact middleName(String value) {
            this.middleName = value;
            return this;
        }

        /** Sets the honorific prefix such as "Dr." — the "Honorific Prefixes" component of vCard N. */
        public Contact namePrefix(String value) {
            this.namePrefix = value;
            return this;
        }

        /** Sets the honorific suffix such as "Jr." — the "Honorific Suffixes" component of vCard N. */
        public Contact nameSuffix(String value) {
            this.nameSuffix = value;
            return this;
        }

        public Contact company(String value) {
            this.company = value;
            return this;
        }

        public Contact jobTitle(String value) {
            this.jobTitle = value;
            return this;
        }

        public Contact email(String value) {
            if (value != null && !value.isBlank()) {
                emails.add(value.trim());
            }
            return this;
        }

        /** Adds a phone number with a vCard TYPE parameter value such as {@code work} or {@code cell}. */
        public Contact phone(String type, String value) {
            if (value != null && !value.isBlank()) {
                phonesByType.put(type, value.trim());
            }
            return this;
        }
    }

    private VCardGenerator() {}

    /** Builds the vCard; a contact with no usable name still yields a structurally valid card. */
    public static String generate(Contact contact) {
        var card = new StringBuilder();
        appendFolded(card, "BEGIN:VCARD");
        appendFolded(card, "VERSION:3.0");
        var formattedName = contact.displayName != null && !contact.displayName.isBlank()
                ? contact.displayName.trim()
                : joinNonBlank(contact.givenName, contact.surname);
        appendFolded(card, "FN:" + escape(formattedName));
        appendFolded(
                card,
                "N:" + escape(blankToEmpty(contact.surname)) + ';' + escape(blankToEmpty(contact.givenName)) + ';'
                        + escape(blankToEmpty(contact.middleName)) + ';' + escape(blankToEmpty(contact.namePrefix))
                        + ';' + escape(blankToEmpty(contact.nameSuffix)));
        if (contact.company != null && !contact.company.isBlank()) {
            appendFolded(card, "ORG:" + escape(contact.company.trim()));
        }
        if (contact.jobTitle != null && !contact.jobTitle.isBlank()) {
            appendFolded(card, "TITLE:" + escape(contact.jobTitle.trim()));
        }
        for (var email : contact.emails) {
            appendFolded(card, "EMAIL;TYPE=internet:" + escape(email));
        }
        for (var phone : contact.phonesByType.entrySet()) {
            appendFolded(card, "TEL;TYPE=" + phone.getKey() + ':' + escape(phone.getValue()));
        }
        appendFolded(card, "END:VCARD");
        return card.toString();
    }

    /**
     * Appends one content line, folded at 75 octets of UTF-8 (RFC 2426 §2.6 / RFC 2425 §5.8.1 for the
     * 3.0 output emitted here — the RFC 6350 §3.2 rule is identical; all measure octets, not chars) with
     * a continuation {@code CRLF + SPACE}, never splitting inside a code point (a fold between the halves
     * of a surrogate pair would corrupt the character).
     */
    private static void appendFolded(StringBuilder card, String line) {
        var octets = 0;
        var index = 0;
        while (index < line.length()) {
            var codePoint = line.codePointAt(index);
            var width = utf8Width(codePoint);
            if (octets + width > 75) {
                card.append("\r\n ");
                octets = 1; // the folding space counts toward the continuation line's 75 octets
            }
            card.appendCodePoint(codePoint);
            octets += width;
            index += Character.charCount(codePoint);
        }
        card.append("\r\n");
    }

    private static int utf8Width(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    private static String joinNonBlank(String first, String second) {
        var firstPart = blankToEmpty(first);
        var secondPart = blankToEmpty(second);
        return (firstPart + " " + secondPart).trim();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }
}
