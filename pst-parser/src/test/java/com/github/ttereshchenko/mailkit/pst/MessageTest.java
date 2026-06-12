package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class MessageTest {

    // F1 regression: the RTF header groups (fonttbl/colortbl and non-htmltag {\*\…} destinations)
    // precede the first \htmlrtf toggle in real Outlook encapsulation; their plain text used to be
    // appended to the extracted HTML ("Arial;", "Microsoft Exchange Server;", …).
    @Test
    void rtfHeaderGroupsDoNotLeakIntoExtractedHtml() {
        String rtf = "{\\rtf1\\ansi\\ansicpg1252\\fromhtml1 \\deff0{\\fonttbl\n"
                + "{\\f0\\fswiss\\fcharset0 Arial;}\n"
                + "{\\f1\\fmodern Courier New;}}\n"
                + "{\\colortbl\\red0\\green0\\blue0;\\red0\\green0\\blue255;}\n"
                + "{\\*\\generator Microsoft Exchange Server;}\n"
                + "{\\*\\formatConverter converted from html;}\n"
                + "\\uc1\\pard\\plain\\deftab360\n"
                + "{\\*\\htmltag19 <html>}\n"
                + "{\\*\\htmltag50 <body>}\n"
                + "{\\*\\htmltag96 <p>}\n"
                + "Hello World\\htmlrtf \\par \\htmlrtf0\n"
                + "{\\*\\htmltag104 </p>}\n"
                + "{\\*\\htmltag58 </body>}\n"
                + "{\\*\\htmltag27 </html>}}";

        String html = Message.extractHtmlFromRtf(rtf, "windows-1252");

        assertTrue(html.startsWith("<html>"), "header text must not precede the markup: " + html);
        assertFalse(html.contains("Arial"), "fonttbl content leaked: " + html);
        assertFalse(html.contains("Courier New"), "fonttbl content leaked: " + html);
        assertFalse(html.contains("Microsoft Exchange Server"), "{\\*\\generator} content leaked: " + html);
        assertFalse(html.contains("converted from html"), "{\\*\\formatConverter} content leaked: " + html);
        assertTrue(html.contains("<p>Hello World"), "renderable text must survive: " + html);
    }

    // The "uc" control word declares how many fallback characters follow each unicode escape; they
    // are alternate representations of the same character and must be skipped, not emitted.
    @Test
    void ucControlWordSkipsFallbackCharacters() {
        String rtf = "{\\rtf1 \\uc2 \\htmlrtf0 <span>\\u8364AB</span>}";
        assertEquals("<span>" + (char) 8364 + "</span>", Message.extractHtmlFromRtf(rtf, "windows-1252"));
    }

    // The RTF's own "ansicpg" control word governs its \'hh escapes and beats the message-level
    // charset: 0xCF is П in windows-1251 but Ï under the (wrong) windows-1252 the caller passes.
    @Test
    void ansiCpgControlWordOverridesCallerCharset() {
        String rtf = "{\\rtf1\\ansi\\ansicpg1251 \\htmlrtf0 <p>\\'cf</p>}";
        assertEquals("<p>П</p>", Message.extractHtmlFromRtf(rtf, "windows-1252"));
    }

    // F13: PidTagSubject's 0x01 prefix marker ([MS-PST] §2.5.3.1.1) is stripped explicitly rather
    // than as a side effect of trimming every string property.
    @Test
    void stripsSubjectPrefixMarker() {
        assertEquals("RE: hello", Message.stripSubjectPrefixMarker("\u0001\u0005RE: hello"));
        assertEquals("plain subject", Message.stripSubjectPrefixMarker("plain subject"));
        assertEquals("", Message.stripSubjectPrefixMarker("\u0001"));
        assertEquals("", Message.stripSubjectPrefixMarker(""));
    }

    // F9: the on-behalf-of (sent-representing) address resolves like the sender — cached SMTP
    // first, then the legacy DN encapsulated as a recognizable IMCEA address at ".invalid".
    @Test
    void resolvesSentRepresentingEmail() {
        var props = new HashMap<Integer, Object>();
        assertEquals("", Message.resolveSentRepresentingEmail(props::get, Message.AddressPreference.PREFER_SMTP));

        props.put(MapiProperties.PR_SENT_REPRESENTING_ADDRTYPE_W, "EX");
        props.put(MapiProperties.PR_SENT_REPRESENTING_EMAIL_ADDRESS_W, "/O=ORG/CN=author");
        assertEquals(
                "IMCEAEX-_O_x003D_ORG_CN_x003D_author@invalid",
                Message.resolveSentRepresentingEmail(props::get, Message.AddressPreference.PREFER_SMTP));

        props.put(MapiProperties.PR_SENT_REPRESENTING_SMTP_ADDRESS_W, "author@example.com");
        assertEquals(
                "author@example.com",
                Message.resolveSentRepresentingEmail(props::get, Message.AddressPreference.PREFER_SMTP));
        assertEquals(
                "/O=ORG/CN=author",
                Message.resolveSentRepresentingEmail(props::get, Message.AddressPreference.PREFER_LEGACY_DN));
    }

    @Test
    void testExtractHtmlFromRtfDecoding() {
        // Test \'e9 (é)
        String rtf = "{\\rtf1 \\htmlrtf0 <p>Hello \\'e9</p>}";
        assertEquals("<p>Hello é</p>", Message.extractHtmlFromRtf(rtf, "windows-1252"));

        // Test \\u8364 (₹/€ depending on font, but we just decode the unicode)
        String rtfUnicode = "{\\rtf1 \\htmlrtf0 <span>\\u8364?</span>}";
        assertEquals("<span>" + (char) 8364 + "</span>", Message.extractHtmlFromRtf(rtfUnicode, "windows-1252"));

        // Test negative unicode \\u-1000
        String rtfUnicodeNegative = "{\\rtf1 \\htmlrtf0 <span>\\u-1000?</span>}";
        assertEquals(
                "<span>" + (char) (short) -1000 + "</span>",
                Message.extractHtmlFromRtf(rtfUnicodeNegative, "windows-1252"));
    }

    /**
     * G2 regression: writers in the wild emit the unsigned form of the RTF unicode escape (e.g.
     * 65533 instead of -3); the signed-only parse used to throw and silently drop the character.
     */
    @Test
    void unicodeEscapeAcceptsUnsignedValues() {
        var rtf = "{\\rtf1 \\htmlrtf0 <span>\\u65533?</span>}";
        assertEquals("<span>" + (char) 0xFFFD + "</span>", Message.extractHtmlFromRtf(rtf, "windows-1252"));
    }

    /**
     * G1 regression: RTF escapes inside a {@code {\*\htmltag…}} destination used to be appended
     * raw, leaking literal RTF syntax ({@code \'e9}, {@code \{}) into the extracted HTML — and an
     * escaped {@code \}} inside the tag content truncated the group at the wrong brace.
     */
    @Test
    void htmlTagGroupContentDecodesRtfEscapes() {
        var rtf = "{\\rtf1 {\\*\\htmltag84 <a href=\"caf\\'e9 \\{x\\}.html\">}}";
        assertEquals("<a href=\"café {x}.html\">", Message.extractHtmlFromRtf(rtf, "windows-1252"));
    }

    /** Multi-byte {@code \'hh} runs inside an htmltag destination must decode as one sequence. */
    @Test
    void htmlTagContentDecodesMultiByteEscapeRuns() {
        assertEquals("日", Message.decodeHtmlTagContent("\\'93\\'fa", "windows-31j"));
        assertEquals("a\tb", Message.decodeHtmlTagContent("a\\tab b", "windows-1252"));
        assertEquals("x\r\ny", Message.decodeHtmlTagContent("x\\par y", "windows-1252"));
    }

    /**
     * C3: a message that names no code page of its own picks up the store-wide default before
     * degrading to windows-1252; a message-level code page always wins over the store's.
     */
    @Test
    void charsetResolutionFallsBackToStoreCodePage() {
        IntFunction<Object> noProperties = ignored -> null;
        assertEquals(
                Charset.forName("windows-31j"),
                Message.resolveCharset(
                        noProperties, 932, MapiProperties.PR_MESSAGE_CODEPAGE, MapiProperties.PR_INTERNET_CPID));
        assertEquals(
                Charset.forName("windows-1252"),
                Message.resolveCharset(
                        noProperties, null, MapiProperties.PR_MESSAGE_CODEPAGE, MapiProperties.PR_INTERNET_CPID));

        IntFunction<Object> messageLevel = tag -> tag == MapiProperties.PR_MESSAGE_CODEPAGE ? 1251 : null;
        assertEquals(
                Charset.forName("windows-1251"),
                Message.resolveCharset(
                        messageLevel, 932, MapiProperties.PR_MESSAGE_CODEPAGE, MapiProperties.PR_INTERNET_CPID));
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
                "IMCEAEX-_O_x003D_EXCHANGELABS_OU_x003D__x002E__x002E__x002E__x0020__x0028_FYDIBOHF23SPDLT_x0029__CN_x003D__x002E__x002E__x002E_@invalid",
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
                "IMCEAEX-_O_x003D_EXCHANGELABS_OU_x003D__x002E__x002E__x002E__x0020__x0028_FYDIBOHF23SPDLT_x0029__CN_x003D_sender@invalid",
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
                "IMCEAEX-_O_x003D_EXCHANGELABS_OU_x003D__x002E__x002E__x002E__x0020__CN_x003D_onbehalf@invalid",
                Message.resolveSenderEmail(repProps::get));

        // Nothing present -> empty string.
        assertEquals("", Message.resolveSenderEmail(new HashMap<Integer, Object>()::get));
    }
}
