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
        card.append("BEGIN:VCARD\r\n");
        card.append("VERSION:3.0\r\n");
        var formattedName = contact.displayName != null && !contact.displayName.isBlank()
                ? contact.displayName.trim()
                : joinNonBlank(contact.givenName, contact.surname);
        card.append("FN:").append(escape(formattedName)).append("\r\n");
        card.append("N:")
                .append(escape(blankToEmpty(contact.surname)))
                .append(';')
                .append(escape(blankToEmpty(contact.givenName)))
                .append(";;;\r\n");
        if (contact.company != null && !contact.company.isBlank()) {
            card.append("ORG:").append(escape(contact.company.trim())).append("\r\n");
        }
        if (contact.jobTitle != null && !contact.jobTitle.isBlank()) {
            card.append("TITLE:").append(escape(contact.jobTitle.trim())).append("\r\n");
        }
        for (var email : contact.emails) {
            card.append("EMAIL;TYPE=internet:").append(escape(email)).append("\r\n");
        }
        for (var phone : contact.phonesByType.entrySet()) {
            card.append("TEL;TYPE=")
                    .append(phone.getKey())
                    .append(':')
                    .append(escape(phone.getValue()))
                    .append("\r\n");
        }
        card.append("END:VCARD\r\n");
        return card.toString();
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
