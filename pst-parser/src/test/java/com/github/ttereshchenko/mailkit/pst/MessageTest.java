package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    // \binN carries N raw bytes that are not RTF: the generic control-word scan ate only "\bin3" and
    // leaked the payload, and a raw '}' byte in it popped the group stack and desynced later output.
    // The count must be consumed wholesale so "}X}" vanishes and "B" survives. Mirrors the sibling
    // RtfStripper.deEncapsulateHtml fork.
    @Test
    void binPayloadInBodyIsConsumedNotLeaked() {
        String rtf = "{\\rtf1\\fromhtml1 {\\*\\htmltag64 <p>}A\\bin3 }X}B{\\*\\htmltag72 </p>}}";
        assertEquals("<p>AB</p>", Message.extractHtmlFromRtf(rtf, "windows-1252"));
    }

    // A {\pict ...} picture group is RTF infrastructure, not de-encapsulated HTML. Before the fix its
    // literal payload ("DEADBEEF") leaked into the body; skipping it also requires consuming the \bin
    // payload, whose single unbalanced '{' byte would otherwise be miscounted as a nested group and
    // swallow the trailing "Bye". Mirrors the sibling RtfStripper.deEncapsulateHtml fork.
    @Test
    void pictureGroupWithBinaryPayloadIsSkipped() {
        String rtf = "{\\rtf1\\fromhtml1 {\\*\\htmltag64 <p>}Hi{\\pict\\wmetafile8\\bin1 {DEADBEEF}Bye"
                + "{\\*\\htmltag72 </p>}}";
        assertEquals("<p>HiBye</p>", Message.extractHtmlFromRtf(rtf, "windows-1252"));
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
     * Outlook hard-wraps long encapsulated lines; a wrap landing inside a {@code {\*\htmltag…}}
     * attribute leaves a physical CR/LF in the tag content. Genuine breaks arrive as {@code \par}/
     * {@code \line}, so the stray CR/LF must be dropped — matching the MSG fork (RtfStripper) — instead
     * of leaking into the attribute value (e.g. splitting a long href URL).
     */
    @Test
    void htmlTagContentDropsPhysicalLineWrapCrLf() {
        assertEquals(
                "<a href=\"http://example.com/very/long/path\">",
                Message.decodeHtmlTagContent("<a href=\"http://example.com/very/long/\r\npath\">", "windows-1252"));
        // a lone LF (Unix-wrapped RTF) is dropped too
        assertEquals("abcd", Message.decodeHtmlTagContent("ab\ncd", "windows-1252"));
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

    // Review finding #2: a String-typed PR_HTML (the PR_BODY_HTML variant, same id 0x1013) used to
    // skip the <meta charset> rewrite, leaving a stale declaration contradicting the UTF-8 output.
    @Test
    void normalizeStoredHtmlRewritesMetaCharsetInStringTypedHtml() {
        var html = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1251\">"
                + "</head><body>Привет</body></html>";
        var normalized = Message.normalizeStoredHtml(html, StandardCharsets.UTF_8);
        assertTrue(normalized.contains("charset=utf-8"), "meta charset must be rewritten: " + normalized);
        assertFalse(normalized.contains("windows-1251"), "stale charset must be gone: " + normalized);
        assertTrue(normalized.contains("Привет"), "body text must be untouched: " + normalized);
    }

    @Test
    void normalizeStoredHtmlDecodesBytesAndRewritesShortFormMeta() {
        var charset = Charset.forName("windows-1251");
        var bytes = "<html><head><meta charset='windows-1251'></head><body>Привет</body></html>".getBytes(charset);
        var normalized = Message.normalizeStoredHtml(bytes, charset);
        assertTrue(normalized.contains("charset='utf-8'"), "short-form meta must be rewritten: " + normalized);
        assertTrue(normalized.contains("Привет"), "bytes must decode with the internet charset: " + normalized);
    }

    @Test
    void normalizeStoredHtmlReturnsNullForNonHtmlValues() {
        assertNull(Message.normalizeStoredHtml(null, StandardCharsets.UTF_8));
        assertNull(Message.normalizeStoredHtml(42, StandardCharsets.UTF_8));
    }

    // Review nit: \fromtext encapsulation ([MS-OXRTFEX]) wraps the plain-text body the same way
    // \fromhtml wraps the HTML body; neither is a genuine RTF body worth exporting as body.rtf.
    @Test
    void encapsulationRtfCoversFromHtmlAndFromText() {
        assertTrue(Message.isEncapsulationRtf("{\\rtf1\\ansi\\ansicpg1252\\fromhtml1 {\\*\\htmltag <p>hi</p>}}"));
        assertTrue(Message.isEncapsulationRtf("{\\rtf1\\ansi\\fromtext \\uc1 plain body}"));
        assertFalse(Message.isEncapsulationRtf("{\\rtf1\\ansi a genuine rtf document}"));
    }

    // --- PR_REPLY_RECIPIENT_ENTRIES (Reply-To) ---

    private static final byte[] ONE_OFF_UID = {
        (byte) 0x81,
        0x2B,
        0x1F,
        (byte) 0xA4,
        (byte) 0xBE,
        (byte) 0xA3,
        0x10,
        0x19,
        (byte) 0x9D,
        0x6E,
        0x00,
        (byte) 0xDD,
        0x01,
        0x0F,
        0x54,
        0x02
    };
    private static final byte[] ADDRESS_BOOK_UID = {
        (byte) 0xDC,
        (byte) 0xA7,
        0x40,
        (byte) 0xC8,
        (byte) 0xC0,
        0x42,
        0x10,
        0x1A,
        (byte) 0xB4,
        (byte) 0xB9,
        0x08,
        0x00,
        0x2B,
        0x2F,
        (byte) 0xE1,
        (byte) 0x82
    };

    private static byte[] oneOffEntryId(
            String name, String addressType, String email, boolean unicode, Charset ansiCharset) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[4]); // abFlags
        out.writeBytes(ONE_OFF_UID);
        out.writeBytes(new byte[] {0, 0}); // wVersion
        int flags = unicode ? 0x8000 : 0;
        out.write(flags & 0xFF); // wFlags, little-endian
        out.write((flags >>> 8) & 0xFF);
        var charset = unicode ? StandardCharsets.UTF_16LE : ansiCharset;
        for (var value : new String[] {name, addressType, email}) {
            out.writeBytes(value.getBytes(charset));
            out.writeBytes(unicode ? new byte[] {0, 0} : new byte[] {0});
        }
        return out.toByteArray();
    }

    private static byte[] addressBookEntryId(String legacyDn) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[4]); // abFlags
        out.writeBytes(ADDRESS_BOOK_UID);
        out.writeBytes(new byte[] {1, 0, 0, 0}); // version
        out.writeBytes(new byte[4]); // type: local mail user
        out.writeBytes(legacyDn.getBytes(StandardCharsets.US_ASCII));
        out.write(0);
        return out.toByteArray();
    }

    private static byte[] flatEntryList(byte[]... entryIds) {
        var body = new ByteArrayOutputStream();
        for (var entryId : entryIds) {
            var size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(entryId.length);
            body.writeBytes(size.array());
            body.writeBytes(entryId);
            body.writeBytes(new byte[(4 - (entryId.length & 3)) & 3]); // 4-byte alignment
        }
        var out = ByteBuffer.allocate(8 + body.size()).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(entryIds.length);
        out.putInt(body.size());
        out.put(body.toByteArray());
        return out.array();
    }

    @Test
    void parsesUnicodeAndAnsiOneOffReplyRecipients() {
        var windows1251 = Charset.forName("windows-1251");
        var blob = flatEntryList(
                oneOffEntryId("Replies Mailbox", "SMTP", "replies@example.com", true, windows1251),
                oneOffEntryId("Отдел", "SMTP", "otdel@example.com", false, windows1251));

        var recipients = Message.parseReplyRecipients(blob, "", windows1251, Message.AddressPreference.PREFER_SMTP);

        assertEquals(2, recipients.size());
        assertEquals("Replies Mailbox", recipients.get(0).name);
        assertEquals("replies@example.com", recipients.get(0).email);
        assertEquals("Отдел", recipients.get(1).name, "ANSI strings must decode with the message's String8 charset");
        assertEquals("otdel@example.com", recipients.get(1).email);
    }

    @Test
    void replyRecipientExchangeAddressesHonourAddressPreference() {
        var legacyDn = "/O=ORG/CN=RECIPIENTS/CN=replies";
        var blob = flatEntryList(
                oneOffEntryId("Exchange Replies", "EX", legacyDn, true, StandardCharsets.US_ASCII),
                addressBookEntryId(legacyDn));

        var smtp = Message.parseReplyRecipients(
                blob, "ignored;Address Book Name", StandardCharsets.US_ASCII, Message.AddressPreference.PREFER_SMTP);
        assertEquals(2, smtp.size());
        assertTrue(smtp.get(0).email.startsWith("IMCEAEX-"), "EX one-off must be IMCEA-encapsulated: " + smtp);
        assertTrue(smtp.get(0).email.endsWith("@invalid"), smtp.get(0).email);
        assertTrue(smtp.get(1).email.startsWith("IMCEAEX-"), "AB entry must be IMCEA-encapsulated: " + smtp);
        assertEquals("Address Book Name", smtp.get(1).name, "AB entries take their name from PR_REPLY_RECIPIENT_NAMES");

        var legacy = Message.parseReplyRecipients(
                blob, "", StandardCharsets.US_ASCII, Message.AddressPreference.PREFER_LEGACY_DN);
        assertEquals(legacyDn, legacy.get(0).email, "PREFER_LEGACY_DN keeps the raw DN");
        assertEquals(legacyDn, legacy.get(1).email);
    }

    @Test
    void malformedReplyRecipientEntriesDegradeToEmpty() {
        assertEquals(List.of(), Message.parseReplyRecipients(null, "x", StandardCharsets.UTF_8, null));
        assertEquals(List.of(), Message.parseReplyRecipients(new byte[3], "", StandardCharsets.UTF_8, null));
        // Declared count larger than the available bytes: stop without reading past the blob.
        var truncated = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        truncated.putInt(5).putInt(100).putInt(200); // one entry claiming 200 bytes
        assertEquals(
                List.of(),
                Message.parseReplyRecipients(
                        truncated.array(), "", StandardCharsets.UTF_8, Message.AddressPreference.PREFER_SMTP));
        // An unknown provider UID is skipped, not misparsed.
        var unknownProvider = new byte[24];
        assertEquals(
                List.of(),
                Message.parseReplyRecipients(
                        flatEntryList(unknownProvider),
                        "",
                        StandardCharsets.UTF_8,
                        Message.AddressPreference.PREFER_SMTP));
    }

    // Round-4 regression: \htmlrtf is group-scoped RTF state ([MS-OXRTFEX] section 2.1.3.1.3). An
    // \htmlrtf turned on inside a group and ended by the closing brace (not an explicit \htmlrtf0)
    // used to stay on forever because the brace did not restore the saved state, so every <p> after
    // it was dropped. Both paragraphs must survive.
    @Test
    void htmlRtfToggleIsRestoredAtGroupClose() {
        var rtf = "{\\rtf1\\ansi\\fromhtml1 \\htmlrtf0 <p>before</p>{\\htmlrtf hidden}<p>after</p>}";
        assertEquals("<p>before</p><p>after</p>", Message.extractHtmlFromRtf(rtf, "windows-1252"));
    }

    // Round-14: an RTF control symbol (\~ \_ \- …) in a non-htmlrtf text run is two chars with no
    // delimiter. The generic control-word scan used to run forward to the next space/brace/backslash,
    // over-running the symbol and deleting the literal body text after it; and the \\ \{ \} escapes must
    // be emitted as literal backslash/brace characters. Kept in lockstep with RtfStripper.deEncapsulateHtml.
    @Test
    void controlSymbolDoesNotSwallowFollowingText() {
        assertEquals("ab c", Message.extractHtmlFromRtf("{\\rtf1\\fromhtml1 a\\~b c}", "windows-1252"));
    }

    @Test
    void escapedBackslashAndBraceAreEmittedAsLiterals() {
        assertEquals("a\\b{c}d", Message.extractHtmlFromRtf("{\\rtf1\\fromhtml1 a\\\\b\\{c\\}d}", "windows-1252"));
    }

    // C1 regression: a FILETIME-0 origination date (value 0, decoding to 1601-01-01T00:00:00Z) must
    // be treated as "no date" so the converter falls through to the delivery time and does not emit
    // a bogus "Date: 1 Jan 1601" header. nonSentinelDate returns null for the sentinel instant, the
    // instant itself for any real date, and null for non-Instant/null inputs.

    @Test
    void nonSentinelDateReturnsSentinelInstantAsNull() {
        // The sentinel is 1601-01-01T00:00:00Z (FILETIME value 0).
        var sentinel = Instant.ofEpochSecond(-11_644_473_600L);
        assertNull(Message.nonSentinelDate(sentinel), "FILETIME-0 sentinel must map to null");
    }

    @Test
    void nonSentinelDatePassesThroughRealInstant() {
        var real = Instant.parse("2020-06-15T10:00:00Z");
        assertEquals(real, Message.nonSentinelDate(real), "A real origination time must be returned unchanged");
    }

    @Test
    void nonSentinelDateReturnsNullForNullAndNonInstant() {
        assertNull(Message.nonSentinelDate(null), "null input must yield null");
        assertNull(Message.nonSentinelDate("not an instant"), "non-Instant input must yield null");
    }

    @Test
    void fileTimeToInstantZeroEqualsFiletimeSentinel() {
        // fileTimeToInstant(0L) must produce exactly the FILETIME_ZERO sentinel constant so that
        // nonSentinelDate gates on the right Instant value.
        var sentinel = Instant.ofEpochSecond(-11_644_473_600L);
        assertEquals(
                sentinel,
                PropertyContext.fileTimeToInstant(0L),
                "fileTimeToInstant(0) must equal the FILETIME_ZERO sentinel 1601-01-01T00:00:00Z");
    }

    // Round-4 regression: \\uc is group-scoped too. A {\\uc3 ...} group used to leak uc=3 past its
    // closing brace, so a later \\uNNNN over-skipped three "fallback" characters and ate real markup.
    // After the group closes, uc must revert to 1, leaving the post-group markup intact.
    @Test
    void unicodeFallbackCountIsRestoredAtGroupClose() {
        var rtf = "{\\rtf1\\ansi\\fromhtml1 \\htmlrtf0 {\\uc3 \\u8364 abc}<x>\\u233 ?yz</x>}";
        assertEquals((char) 8364 + "<x>" + (char) 233 + "yz</x>", Message.extractHtmlFromRtf(rtf, "windows-1252"));
    }

    // Round-4 regression: a \\uNNNN escape inside a {\*\htmltag…} destination used to fall through the
    // tag-content control-word switch (which handled only par/line/tab), so a non-ASCII character in
    // an attribute value was lost and its ANSI fallback ('?') leaked. The é (U+00E9) must be kept and
    // the one fallback character skipped.
    @Test
    void htmlTagContentDecodesUnicodeEscape() {
        var rtf = "{\\*\\htmltag84 <a title=\"\\u233 ?x\">}";
        assertEquals("<a title=\"" + (char) 233 + "x\">", Message.extractHtmlFromRtf(rtf, "windows-1252"));
    }
}
