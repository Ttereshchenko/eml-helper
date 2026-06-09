package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageTest {

    @Test
    void testExtractHtmlFromRtfDecoding() throws Exception {
        Method method = Message.class.getDeclaredMethod("extractHtmlFromRtf", String.class, String.class);
        method.setAccessible(true);

        // Test \'e9 (é)
        String rtf = "{\\rtf1 \\htmlrtf0 <p>Hello \\'e9</p>}";
        String html = (String) method.invoke(null, rtf, "windows-1252");
        assertEquals("<p>Hello é</p>", html);

        // Test \u8364 (₹/€ depending on font, but we just decode the unicode)
        String rtfUnicode = "{\\rtf1 \\htmlrtf0 <span>\\u8364?</span>}";
        String htmlUnicode = (String) method.invoke(null, rtfUnicode, "windows-1252");
        assertEquals("<span>" + (char) 8364 + "</span>", htmlUnicode);

        // Test negative unicode \\u-1000
        String rtfUnicodeNegative = "{\\rtf1 \\htmlrtf0 <span>\\u-1000?</span>}";
        String htmlUnicodeNegative = (String) method.invoke(null, rtfUnicodeNegative, "windows-1252");
        assertEquals("<span>" + (char) (short) -1000 + "</span>", htmlUnicodeNegative);
    }

    @Test
    void testParseRecipientsLegacyDn() {
        var rows = new ArrayList<Map<Integer, Object>>();
        var row = new HashMap<Integer, Object>();
        row.put(MapiProperties.PR_DISPLAY_NAME_W, "Test User"); // PR_DISPLAY_NAME
        row.put(MapiProperties.PR_ADDRTYPE, "EX"); // PR_ADDRTYPE
        row.put(
                MapiProperties.PR_EMAIL_ADDRESS_W,
                "/O=EXCHANGELABS/OU=... (FYDIBOHF23SPDLT)/CN=..."); // PR_EMAIL_ADDRESS (legacyExchangeDN)
        // PR_SMTP_ADDRESS is missing
        rows.add(row);

        var recipients = Message.parseRecipients(rows);
        assertEquals(1, recipients.size());
        assertEquals("Test User", recipients.get(0).name);
        // EX recipient with no cached SMTP falls back to the legacyDN rather than being dropped.
        assertEquals(
                "IMCEAEX-_O_x003D_EXCHANGELABS_OU_x003D__x002E__x002E__x002E__x0020__x0028_FYDIBOHF23SPDLT_x0029__CN_x003D__x002E__x002E__x002E_@example.com",
                recipients.get(0).email);

        // Native SMTP recipient: PR_EMAIL_ADDRESS already holds a routable address.
        row.put(MapiProperties.PR_ADDRTYPE, "SMTP");
        row.put(MapiProperties.PR_EMAIL_ADDRESS_W, "test@example.com");

        recipients = Message.parseRecipients(rows);
        assertEquals("test@example.com", recipients.get(0).email);

        // Cached PR_SMTP_ADDRESS wins over the EX legacyDN.
        row.put(MapiProperties.PR_ADDRTYPE, "EX");
        row.put(MapiProperties.PR_EMAIL_ADDRESS_W, "/O=EXCHANGELABS...");
        row.put(MapiProperties.PR_SMTP_ADDRESS_W, "smtp@example.com"); // PR_SMTP_ADDRESS

        recipients = Message.parseRecipients(rows);
        assertEquals("smtp@example.com", recipients.get(0).email);

        // With PREFER_LEGACY_DN, the legacy DN should win over PR_SMTP_ADDRESS
        recipients = Message.parseRecipients(rows, Message.AddressPreference.PREFER_LEGACY_DN);
        assertEquals("/O=EXCHANGELABS...", recipients.get(0).email);

        // No address of any kind -> empty string.
        var empty = new ArrayList<Map<Integer, Object>>();
        var emptyRow = new HashMap<Integer, Object>();
        emptyRow.put(MapiProperties.PR_DISPLAY_NAME_W, "Nameless");
        emptyRow.put(MapiProperties.PR_ADDRTYPE, "EX"); // PR_ADDRTYPE only, no PR_EMAIL_ADDRESS / PR_SMTP_ADDRESS
        empty.add(emptyRow);
        assertEquals("", Message.parseRecipients(empty).get(0).email);
    }

    @Test
    void testResolveSenderEmailLegacyDn() {
        var props = new HashMap<Integer, Object>();

        // EX sender with no cached SMTP falls back to the legacyDN in PR_SENDER_EMAIL_ADDRESS.
        props.put(MapiProperties.PR_SENDER_ADDRTYPE_W, "EX"); // PR_SENDER_ADDRTYPE
        props.put(
                MapiProperties.PR_SENDER_EMAIL_ADDRESS_W,
                "/O=EXCHANGELABS/OU=... (FYDIBOHF23SPDLT)/CN=sender"); // PR_SENDER_EMAIL_ADDRESS
        assertEquals(
                "IMCEAEX-_O_x003D_EXCHANGELABS_OU_x003D__x002E__x002E__x002E__x0020__x0028_FYDIBOHF23SPDLT_x0029__CN_x003D_sender@example.com",
                Message.resolveSenderEmail(props::get));

        // Cached PR_SENDER_SMTP_ADDRESS wins over the legacyDN.
        props.put(MapiProperties.PR_SENDER_SMTP_ADDRESS_W, "sender@example.com"); // PR_SENDER_SMTP_ADDRESS
        assertEquals("sender@example.com", Message.resolveSenderEmail(props::get));

        // With PREFER_LEGACY_DN, the legacy DN should win over PR_SENDER_SMTP_ADDRESS
        assertEquals(
                "/O=EXCHANGELABS/OU=... (FYDIBOHF23SPDLT)/CN=sender",
                Message.resolveSenderEmail(props::get, Message.AddressPreference.PREFER_LEGACY_DN));

        // Sent-representing legacyDN is used when no sender address is present.
        var repProps = new HashMap<Integer, Object>();
        repProps.put(MapiProperties.PR_SENT_REPRESENTING_ADDRTYPE_W, "EX");
        repProps.put(
                MapiProperties.PR_SENT_REPRESENTING_EMAIL_ADDRESS_W,
                "/O=EXCHANGELABS/OU=... /CN=onbehalf"); // PR_SENT_REPRESENTING_EMAIL_ADDRESS
        assertEquals(
                "IMCEAEX-_O_x003D_EXCHANGELABS_OU_x003D__x002E__x002E__x002E__x0020__CN_x003D_onbehalf@example.com",
                Message.resolveSenderEmail(repProps::get));

        // Nothing present -> empty string.
        assertEquals("", Message.resolveSenderEmail(new HashMap<Integer, Object>()::get));
    }
}
