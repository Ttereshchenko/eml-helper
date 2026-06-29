package com.github.ttereshchenko.mailkit.pst;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/**
 * A single message within a PST/OST store, wrapping its Property Context.
 *
 * <p>Exposes the MAPI properties most relevant to EML export — subject, sender/recipients, dates,
 * transport headers, plain-text/HTML/RTF bodies and attachments. Address resolution honours an
 * {@link AddressPreference} (routable SMTP vs. Exchange legacy DN). Construct one from a node id (or
 * {@link NodeEntry}) obtained via {@link Folder#getMessages()}.
 *
 * <p>Construction reads the message's properties; a corrupt or missing node degrades to an empty
 * message rather than failing, so bulk exports can keep going — {@link #isLoaded()} /
 * {@link #getLoadError()} report whether that happened.
 *
 * <p>Instances are not thread-safe; confine each to a single thread.
 */
public class Message {

    private static final System.Logger LOG = System.getLogger(Message.class.getName());

    // {\*\htmltag<N> …} groups: a leading numeric token (the de-encapsulation index) is stripped, and a
    // group that is only that number carries no content. Compiled once rather than per RTF token.
    private static final Pattern HTMLTAG_LEADING_INDEX = Pattern.compile("^\\d+\\s*");
    private static final Pattern HTMLTAG_DIGITS_ONLY = Pattern.compile("\\d+");

    // Keys on the charset= attribute/parameter of a <meta> tag. Group 1 is everything up to it, group 2
    // is "charset=" plus the optional opening quote, group 3 (replaced with utf-8) is the charset token
    // itself. The lookbehind requires charset to follow a real separator so an attribute merely ending in
    // "charset" (e.g. data-charset=) is not matched, and the value class stops at ';' / '/' / whitespace /
    // quote / '>' so trailing MIME parameters and later tag attributes are preserved. Mirrors the plugin
    // module's HtmlMetaCharset.META_CHARSET (which this standalone library cannot reference).
    private static final Pattern META_CHARSET =
            Pattern.compile("(?i)(<meta\\s[^>]*?)(?<=[\\s;\"'])(charset\\s*=\\s*[\"']?)([^\"'\\s;/>]+)");

    private static final int NID_ATTACHMENT_TABLE = 0x0671;
    private static final int NID_RECIPIENT_TABLE = 0x0692;

    /**
     * 1601-01-01T00:00:00Z — the instant a FILETIME of 0 decodes to. A PT_SYSTIME stored as 0 is the
     * conventional "time not set" sentinel, so an origination time equal to it is treated as absent.
     */
    private static final Instant FILETIME_ZERO = Instant.ofEpochSecond(-11_644_473_600L);

    /** The {@code \ansicpgN} control word naming the code page of an RTF body's {@code \'hh} escapes. */
    private static final Pattern RTF_ANSI_CODE_PAGE = Pattern.compile("\\\\ansicpg(\\d{1,9})");

    /** PSETID_Address ([MS-OXPROPS] §1.3.2): the property set of the contact/dist-list named properties. */
    private static final UUID PSETID_ADDRESS = UUID.fromString("00062004-0000-0000-C000-000000000046");

    // EntryID provider UIDs ([MS-OXCDATA] §2.2.5): one-off recipients and address-book objects.
    private static final byte[] ONE_OFF_PROVIDER_UID = {
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
    private static final byte[] ADDRESS_BOOK_PROVIDER_UID = {
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

    private final PstFile pstFile;
    private final int nid;
    private final NodeDatabase nodeDatabase;
    private final NodeEntry node;
    private PropertyContext propertyContext;
    private AddressPreference addressPreference = AddressPreference.PREFER_SMTP;
    private Charset cachedString8Charset;
    private Charset cachedInternetCharset;
    private String cachedRawRtf;
    private Exception loadError;

    /**
     * Selects which address a message prefers when both a routable SMTP address and an Exchange
     * legacy distinguished name are available. Value-only by design: human-readable labels are a
     * presentation concern and belong to the consuming UI, not to this parser library.
     */
    public enum AddressPreference {
        PREFER_SMTP,
        PREFER_LEGACY_DN
    }

    /** Sets the address preference for sender/recipient resolution; {@code null} resets to SMTP. */
    public void setAddressPreference(AddressPreference addressPreference) {
        this.addressPreference = addressPreference != null ? addressPreference : AddressPreference.PREFER_SMTP;
    }

    /**
     * Wraps the message with the given node id.
     *
     * @param pstFile the open store; must not be {@code null}
     * @param nid the message's node id, e.g. from {@link Folder#getMessages()}
     */
    public Message(PstFile pstFile, int nid) {
        this.pstFile = Objects.requireNonNull(pstFile, "pstFile");
        this.nid = nid;
        this.nodeDatabase = pstFile.nodeDatabase();
        NodeEntry resolved = null;
        try {
            resolved = nodeDatabase.getNode(nid);
        } catch (IOException exception) {
            loadError = exception;
            LOG.log(System.Logger.Level.DEBUG, () -> "Failed to resolve message node " + nid, exception);
        }
        this.node = resolved;
        loadProperties();
    }

    /**
     * Wraps the message backed by an already-resolved node entry, e.g. an embedded message resolved
     * via {@link PstFile#readSubnodeEntry}.
     *
     * @param pstFile the open store; must not be {@code null}
     * @param node the message's node entry; must not be {@code null}
     */
    public Message(PstFile pstFile, NodeEntry node) {
        this.pstFile = Objects.requireNonNull(pstFile, "pstFile");
        this.node = Objects.requireNonNull(node, "node");
        this.nid = node.nodeId();
        this.nodeDatabase = pstFile.nodeDatabase();
        loadProperties();
    }

    private void loadProperties() {
        try {
            if (node == null) {
                if (loadError == null) {
                    loadError = new PstException("Message node not found in NBT: " + nid);
                }
                return;
            }

            byte[] data = nodeDatabase.readNodeData(node.dataBid());
            this.propertyContext = new PropertyContext(data, nodeDatabase, node);
            // Most String8 properties follow the message-store code page, but a String8-typed PR_HTML
            // (the legacy PidTagBodyHtml variant) carries the HTML body, whose bytes are governed by
            // PR_INTERNET_CPID ([MS-OXCMAIL] §2.1.3.5.2) — getHtmlBody() requires the internet charset
            // for it, exactly like the byte[]-typed variant, so override its decode here.
            this.propertyContext.decodeString8(
                    getString8Charset(), Map.of(MapiProperties.PR_HTML, getInternetCharset()));
        } catch (Exception exception) {
            // Degrades gracefully (all getters return their empty defaults), but record and log so
            // genuine corruption is not hidden.
            loadError = exception;
            LOG.log(System.Logger.Level.WARNING, () -> "Failed to load properties for message node " + nid, exception);
        }
    }

    /** This message's node id. */
    public int getNid() {
        return nid;
    }

    /** Whether the message's properties were read successfully; see {@link #getLoadError()}. */
    public boolean isLoaded() {
        return loadError == null;
    }

    /**
     * The failure that prevented the message's properties from loading, or {@code null} if loading
     * succeeded. A non-null value usually means the node id does not exist or the store is damaged.
     */
    public Exception getLoadError() {
        return loadError;
    }

    /** The MAPI message class (e.g. {@code IPM.Note}), or an empty string if absent. */
    public String getMessageClass() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_MESSAGE_CLASS_W) instanceof String value ? value : "";
    }

    /** The subject (with any [MS-PST] prefix marker stripped), or an empty string if absent. */
    public String getSubject() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_SUBJECT_W) instanceof String value
                ? stripSubjectPrefixMarker(value)
                : "";
    }

    /**
     * Strips the PidTagSubject prefix marker ([MS-PST] §2.5.3.1.1): when the stored subject begins
     * with {@code 0x01}, the second character encodes the prefix length and the full subject text
     * (prefix included) follows after those two marker characters.
     */
    static String stripSubjectPrefixMarker(String subject) {
        if (!subject.isEmpty() && subject.charAt(0) == 0x01) {
            return subject.length() >= 2 ? subject.substring(2) : "";
        }
        return subject;
    }

    /** The plain-text body, or an empty string if absent. */
    public String getBody() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_BODY_W) instanceof String value ? value : "";
    }

    /**
     * The HTML body, or an empty string if the message has none. The returned markup is
     * <em>normalized</em> for re-serialization, not the raw stored bytes: PR_HTML byte content is
     * decoded with the message's code page, any {@code <meta charset=...>} (in the string-typed
     * PR_BODY_HTML variant too) is rewritten to UTF-8, and when only an RTF body exists its
     * encapsulated HTML (\fromhtml) is extracted. Use {@link #getProperty} with
     * {@link MapiProperties#PR_HTML} for the raw bytes.
     */
    public String getHtmlBody() {
        if (propertyContext == null) return "";
        String stored = normalizeStoredHtml(propertyContext.getProperty(MapiProperties.PR_HTML), getInternetCharset());
        if (stored != null) return stored;

        String rtf = getRawRtfBody();
        if (rtf.contains("\\fromhtml")) {
            return extractHtmlFromRtf(rtf, getInternetCharset().name());
        }
        return "";
    }

    /**
     * Normalizes a stored PR_HTML value for re-serialization as UTF-8: byte content is decoded with
     * the message's internet code page, and any {@code <meta ... charset=...>} declaration — in the
     * string-typed PR_BODY_HTML variant as well — is rewritten to UTF-8 so it cannot contradict the
     * re-encoded output. Returns {@code null} when the value is neither a string nor bytes.
     * Package-private for testing.
     */
    static String normalizeStoredHtml(Object value, Charset internetCharset) {
        if (value instanceof String text) {
            return rewriteMetaCharsetToUtf8(text);
        }
        if (value instanceof byte[] bytes) {
            return rewriteMetaCharsetToUtf8(new String(bytes, internetCharset).trim());
        }
        return null;
    }

    private static String rewriteMetaCharsetToUtf8(String html) {
        // Replace only the charset *value* and leave the rest of the <meta> tag intact. The value class
        // stops at whitespace, ';' (the rfc2045 §5.1 MIME parameter separator), '/', a quote or '>', so a
        // legacy <meta http-equiv="Content-Type" content="text/html; charset=windows-1251; format=flowed">
        // keeps its trailing "; format=flowed" and a short-form <meta charset=windows-1251 id="x"> keeps
        // its later attributes — the previous [^"'>]+ value class swallowed everything up to the closing
        // quote, dropping those tokens. Kept in lockstep with the plugin module's HtmlMetaCharset (which
        // this standalone library cannot reference); change both together.
        return META_CHARSET.matcher(html).replaceAll("$1$2utf-8");
    }

    /**
     * The charset governing this message's PT_STRING8 properties: PR_MESSAGE_CODEPAGE per
     * [MS-OXCMAIL], with PR_INTERNET_CPID as fallback. Distinct from {@link #getInternetCharset()}
     * — the two can legitimately differ (e.g. GBK String8 properties with a UTF-8 HTML body).
     */
    private Charset getString8Charset() {
        if (cachedString8Charset == null) {
            cachedString8Charset = resolveCharset(MapiProperties.PR_MESSAGE_CODEPAGE, MapiProperties.PR_INTERNET_CPID);
        }
        return cachedString8Charset;
    }

    /** The charset governing the PR_HTML body bytes: PR_INTERNET_CPID, falling back to PR_MESSAGE_CODEPAGE. */
    private Charset getInternetCharset() {
        if (cachedInternetCharset == null) {
            cachedInternetCharset = resolveCharset(MapiProperties.PR_INTERNET_CPID, MapiProperties.PR_MESSAGE_CODEPAGE);
        }
        return cachedInternetCharset;
    }

    private Charset resolveCharset(int preferredTag, int fallbackTag) {
        return resolveCharset(
                propertyContext != null ? propertyContext::getProperty : ignored -> null,
                pstFile != null ? pstFile.storeCodePage() : null,
                preferredTag,
                fallbackTag);
    }

    /**
     * Pure code-page resolution: the preferred tag, then the fallback tag, then the store-wide
     * default, then windows-1252. Package-private for testing.
     */
    static Charset resolveCharset(
            IntFunction<Object> properties, Integer storeCodePage, int preferredTag, int fallbackTag) {
        Object codePage = properties.apply(preferredTag);
        if (codePage == null) {
            codePage = properties.apply(fallbackTag);
        }
        if (codePage instanceof Number number) {
            return CodePages.charsetFor(number.intValue());
        }
        // The message names no code page of its own; fall back to the store-wide default before
        // assuming windows-1252 (common for items written by a non-Outlook MAPI client).
        if (storeCodePage != null) {
            return CodePages.charsetFor(storeCodePage);
        }
        return CodePages.defaultCharset();
    }

    static String extractHtmlFromRtf(String rtf, String charsetName) {
        // \ansicpgN in the RTF header names the code page its \'hh escapes were written in and is
        // more authoritative than the message-level charset the caller resolved.
        var codePageMatcher = RTF_ANSI_CODE_PAGE.matcher(rtf);
        if (codePageMatcher.find()) {
            try {
                charsetName = CodePages.charsetFor(Integer.parseInt(codePageMatcher.group(1)))
                        .name();
            } catch (NumberFormatException ignored) {
                // implausibly long code-page number — keep the caller's charset
            }
        }

        var html = new StringBuilder();
        var index = 0;
        var inHtmlRtf = false;
        // current "uc" control-word value; one fallback char per unicode escape by default
        var unicodeFallbackCount = 1;
        // \htmlrtf and \\uc are group-scoped RTF state ([MS-OXRTFEX] section 2.1.3.1.3): a value set
        // inside a group is restored when its closing brace is reached. Save the current
        // {inHtmlRtf, unicodeFallbackCount} on each '{' and restore it on the matching '}' so an
        // \htmlrtf turned on inside a group (and ended by the brace rather than \htmlrtf0) does not
        // stay on forever, and a {\\ucN ...} group does not leak its fallback count into later
        // \\uN escapes.
        var groupState = new ArrayDeque<int[]>();
        while (index < rtf.length()) {
            if (rtf.startsWith("\\htmlrtf0", index)) {
                inHtmlRtf = false;
                index += 9;
                if (index < rtf.length() && rtf.charAt(index) == ' ') index++;
                continue;
            } else if (rtf.startsWith("\\htmlrtf", index)) {
                inHtmlRtf = true;
                index += 8;
                if (index < rtf.length() && rtf.charAt(index) == ' ') index++;
                continue;
            }

            // \binN is followed by N bytes of raw binary data (RTF spec / [MS-OXRTFCP] §2.1.3.1.5):
            // the payload is not RTF and must be consumed wholesale, or it leaks into the recovered
            // HTML and any stray brace byte in it desyncs the group-state stack. Handled before the
            // brace/suppression branches below so it also applies inside \htmlrtf runs. Mirrors the
            // sibling RtfStripper.deEncapsulateHtml fork.
            int afterBin = skipBin(rtf, index);
            if (afterBin != index) {
                index = afterBin;
                continue;
            }

            if (rtf.startsWith("{\\*\\htmltag", index)) {
                // The group end must honor RTF escapes: an escaped \} inside the tag content would
                // otherwise truncate the tag at the wrong brace.
                int end = findGroupEnd(rtf, index);
                if (end != -1) {
                    String tag = rtf.substring(index + 11, end).trim();
                    tag = HTMLTAG_LEADING_INDEX.matcher(tag).replaceFirst("");
                    if (tag.equals("\\par")) {
                        html.append("\r\n");
                    } else if (!HTMLTAG_DIGITS_ONLY.matcher(tag).matches()) {
                        html.append(decodeHtmlTagContent(tag, charsetName));
                    }
                    index = end + 1;
                    continue;
                }
            }
            // RTF header/metadata groups are not renderable content ([MS-OXRTFEX]): plain text inside
            // {\fonttbl…} & co. and inside non-htmltag {\*\…} destinations ({\*\generator …},
            // {\*\formatConverter …}) must not leak into the extracted HTML as body text. skipGroup
            // consumes the whole group including its closing brace, so it stays balanced on its own.
            if (isNonRenderableGroupStart(rtf, index)) {
                index = skipGroup(rtf, index);
                continue;
            }
            var character = rtf.charAt(index);
            // Maintain the group-state stack even inside an \htmlrtf-suppressed run so it stays
            // balanced: a '{' saves the current state and the matching '}' restores it.
            if (character == '{') {
                groupState.push(new int[] {inHtmlRtf ? 1 : 0, unicodeFallbackCount});
                index++;
                continue;
            }
            if (character == '}') {
                if (!groupState.isEmpty()) {
                    var restored = groupState.pop();
                    inHtmlRtf = restored[0] != 0;
                    unicodeFallbackCount = restored[1];
                }
                index++;
                continue;
            }
            if (inHtmlRtf) {
                index++;
                continue;
            }
            if (character == '\r' || character == '\n') {
                // Raw CR/LF in RTF source is wrapping, not content — line breaks are \par / \line.
                index++;
                continue;
            }
            if (character == '\\') {
                if (index + 3 < rtf.length() && rtf.charAt(index + 1) == '\'') {
                    var hexBuffer = new ByteArrayOutputStream();
                    while (index + 3 < rtf.length() && rtf.charAt(index) == '\\' && rtf.charAt(index + 1) == '\'') {
                        String hex = rtf.substring(index + 2, index + 4);
                        try {
                            hexBuffer.write(Integer.parseInt(hex, 16));
                        } catch (Exception ignored) {
                            // malformed \'hh escape
                        }
                        index += 4;
                    }
                    try {
                        html.append(new String(hexBuffer.toByteArray(), charsetName));
                    } catch (Exception ignored) {
                        // unsupported charset name — skip the escaped bytes
                    }
                    continue;
                }
                if (rtf.startsWith("\\uc", index)
                        && index + 3 < rtf.length()
                        && Character.isDigit(rtf.charAt(index + 3))) {
                    int endNum = index + 3;
                    while (endNum < rtf.length() && Character.isDigit(rtf.charAt(endNum))) endNum++;
                    try {
                        unicodeFallbackCount = Integer.parseInt(rtf.substring(index + 3, endNum));
                    } catch (NumberFormatException ignored) {
                        // implausibly long "uc" parameter — keep the current fallback count
                    }
                    index = endNum;
                    if (index < rtf.length() && rtf.charAt(index) == ' ') index++;
                    continue;
                }
                if (index + 2 < rtf.length()
                        && rtf.charAt(index + 1) == 'u'
                        && (Character.isDigit(rtf.charAt(index + 2)) || rtf.charAt(index + 2) == '-')) {
                    int endNum = index + 2;
                    if (rtf.charAt(endNum) == '-') endNum++;
                    while (endNum < rtf.length() && Character.isDigit(rtf.charAt(endNum))) endNum++;
                    try {
                        // A unicode escape parameter is nominally a signed 16-bit value, but real
                        // writers also emit the unsigned form (e.g. 65533); accept both and
                        // truncate to a char.
                        int codePoint = Integer.parseInt(rtf.substring(index + 2, endNum));
                        html.append((char) codePoint);
                    } catch (Exception ignored) {
                        // malformed \\uN escape — skip this code point
                    }
                    index = endNum;
                    if (index < rtf.length() && rtf.charAt(index) == ' ') index++; // control-word delimiter
                    // Skip the "uc"-counted ANSI fallback characters that trail the \\uN escape; the
                    // shared helper consumes a \\-escaped literal or control-symbol fallback (\\ \{ \})
                    // rather than leaving it for the outer loop to re-emit as duplicate body text.
                    index = skipUnicodeFallback(rtf, index, unicodeFallbackCount);
                    continue;
                }
                if (index + 1 < rtf.length() && !Character.isLetter(rtf.charAt(index + 1))) {
                    // A backslash followed by a non-letter is an RTF control symbol (\~ \_ \- \| …) or an
                    // escaped literal (\\ \{ \}), exactly two characters wide with no trailing delimiter.
                    // The generic control-word scan below runs forward to the next space/brace/backslash
                    // and would over-run a control symbol into the following literal text, deleting it.
                    // Emit the escaped literal for \\ \{ \} (real HTML body characters); drop the others.
                    // Keeps this PST fork in lockstep with RtfStripper.deEncapsulateHtml.
                    char symbol = rtf.charAt(index + 1);
                    if (symbol == '\\' || symbol == '{' || symbol == '}') {
                        html.append(symbol);
                    }
                    index += 2;
                    continue;
                }
                int nextSpace = rtf.indexOf(' ', index);
                int nextSlash = rtf.indexOf('\\', index + 1);
                int nextBrace = rtf.indexOf('{', index + 1);
                int nextClose = rtf.indexOf('}', index + 1);

                int end = rtf.length();
                if (nextSpace != -1) end = Math.min(end, nextSpace);
                if (nextSlash != -1) end = Math.min(end, nextSlash);
                if (nextBrace != -1) end = Math.min(end, nextBrace);
                if (nextClose != -1) end = Math.min(end, nextClose);

                index = end;
                if (index < rtf.length() && rtf.charAt(index) == ' ') index++; // skip the trailing space
                continue;
            }
            html.append(character);
            index++;
        }
        return html.toString().trim();
    }

    /**
     * Skips the {@code count} ANSI fallback "characters" that trail a {@code \\uN} escape, honouring the
     * current {@code \\uc} value. Each fallback may be a literal char, a {@code \\'hh} hex byte, or a
     * control word/symbol; a group brace ends the skip. A {@code \\}-escaped literal or control symbol is
     * consumed (it is the ANSI degradation of the Unicode char already emitted by {@code \\uN}) rather
     * than left for the outer loop to re-emit, mirroring the plugin module's
     * {@code RtfStripper.skipUnicodeFallback} (which this standalone library cannot reference — keep both
     * in lockstep). Shared by {@code extractHtmlFromRtf} and {@code decodeHtmlTagContent}.
     */
    private static int skipUnicodeFallback(String source, int index, int count) {
        int skipped = 0;
        while (skipped < count && index < source.length()) {
            char character = source.charAt(index);
            if (character == '{' || character == '}') {
                break;
            }
            if (character == '\\' && index + 1 < source.length()) {
                char next = source.charAt(index + 1);
                if (next == '\'') {
                    index = Math.min(source.length(), index + 4);
                } else if (Character.isLetter(next)) {
                    index += 2;
                    while (index < source.length() && Character.isLetter(source.charAt(index))) {
                        index++;
                    }
                    if (index < source.length()
                            && (source.charAt(index) == '-' || Character.isDigit(source.charAt(index)))) {
                        if (source.charAt(index) == '-') {
                            index++;
                        }
                        while (index < source.length() && Character.isDigit(source.charAt(index))) {
                            index++;
                        }
                    }
                    if (index < source.length() && source.charAt(index) == ' ') {
                        index++;
                    }
                } else {
                    index += 2;
                }
            } else {
                index++;
            }
            skipped++;
        }
        return index;
    }

    /**
     * Whether {@code index} starts an RTF group whose contents are metadata rather than renderable
     * text: the font/color/stylesheet/info header tables, the {@code {\pict}}/{@code {\object}}
     * picture and embedded-object groups (whose hex/{@code \bin} payload is not body text), and every
     * {@code {\*\…}} destination other than {@code {\*\htmltag…}} (which the caller handles as HTML
     * content).
     */
    private static boolean isNonRenderableGroupStart(String rtf, int index) {
        if (rtf.charAt(index) != '{') {
            return false;
        }
        if (rtf.startsWith("{\\*\\htmltag", index)) {
            return false;
        }
        return rtf.startsWith("{\\fonttbl", index)
                || rtf.startsWith("{\\colortbl", index)
                || rtf.startsWith("{\\stylesheet", index)
                || rtf.startsWith("{\\info", index)
                || rtf.startsWith("{\\pict", index)
                || rtf.startsWith("{\\object", index)
                || rtf.startsWith("{\\*\\", index);
    }

    /**
     * Returns the index just past the {@code }} closing the group that opens at {@code index},
     * honouring {@code \{ \} \\} escapes so an escaped brace cannot unbalance the count.
     */
    private static int skipGroup(String rtf, int index) {
        int depth = 0;
        while (index < rtf.length()) {
            char current = rtf.charAt(index);
            if (current == '\\') {
                // Consume a \binN binary payload before the escape skip so raw brace bytes inside it
                // (common in {\pict}/{\object} groups) cannot unbalance the depth count.
                int afterBin = skipBin(rtf, index);
                if (afterBin != index) {
                    index = afterBin;
                    continue;
                }
                if (index + 1 < rtf.length()) {
                    index += 2;
                    continue;
                }
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
            }
            index++;
        }
        return index;
    }

    /**
     * If a {@code \binN} control word starts at {@code index}, returns the index just past its N raw
     * binary bytes (RTF spec / [MS-OXRTFCP] §2.1.3.1.5) — the payload is not RTF and must be consumed
     * wholesale so its bytes (which can include stray braces) neither leak into the body nor desync the
     * group stack. Returns {@code index} unchanged when no {@code \bin} starts here; a missing or
     * unparsable count consumes no payload. Mirrors RtfStripper#skipBin.
     */
    private static int skipBin(String rtf, int index) {
        if (!rtf.startsWith("\\bin", index)) {
            return index;
        }
        int cursor = index + 4;
        // \bin is this control word only when its letter run ends here; otherwise it is a longer word.
        if (cursor < rtf.length() && Character.isLetter(rtf.charAt(cursor))) {
            return index;
        }
        int digitsStart = cursor;
        while (cursor < rtf.length() && Character.isDigit(rtf.charAt(cursor))) {
            cursor++;
        }
        int next = cursor;
        if (next < rtf.length() && rtf.charAt(next) == ' ') {
            next++; // a control word's numeric argument may be followed by one delimiting space
        }
        if (cursor > digitsStart) {
            try {
                int binaryLength = Integer.parseInt(rtf.substring(digitsStart, cursor));
                if (binaryLength > 0) {
                    next = Math.min(rtf.length(), next + binaryLength);
                }
            } catch (NumberFormatException ignored) {
                // unparsable count — consume no payload
            }
        }
        return next;
    }

    /**
     * Returns the index of the {@code }} closing the group that opens at {@code index} (not past
     * it), honouring {@code \{ \} \\} escapes, or {@code -1} when the group never closes.
     */
    private static int findGroupEnd(String rtf, int index) {
        int depth = 0;
        while (index < rtf.length()) {
            char current = rtf.charAt(index);
            if (current == '\\' && index + 1 < rtf.length()) {
                index += 2;
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
            index++;
        }
        return -1;
    }

    /**
     * Decodes the RTF escapes inside a {@code {\*\htmltag…}} destination's content ([MS-OXRTFEX]
     * §2.1.3.1.2): {@code \'hh} runs are decoded with the message's code page (runs decoded
     * together so multi-byte encodings survive), {@code \{ \} \\} unescape to the literal
     * character, {@code \\uN} becomes its code point with the trailing {@code \\uc}-counted ANSI
     * fallback skipped, {@code \par}/{@code \line} become CRLF, {@code \tab} a tab; any other
     * control word is dropped rather than leaked into the HTML as literal RTF syntax. Without the
     * {@code \\uN} branch, a non-ASCII character in a tag attribute (title/alt/href) is lost and its
     * fallback leaks.
     */
    static String decodeHtmlTagContent(String content, String charsetName) {
        var html = new StringBuilder();
        int index = 0;
        // \\uc is group-scoped state; a tag's content is its own group, so it starts at the RTF
        // default of one fallback char per \\uN escape and may be overridden by an inline \\ucN.
        int unicodeFallbackCount = 1;
        while (index < content.length()) {
            char current = content.charAt(index);
            if (current != '\\') {
                // Drop physical RTF line-wrap CR/LF: Outlook hard-wraps long encapsulated lines and a
                // wrap can fall inside a {\*\htmltag…} attribute, where genuine breaks would instead
                // arrive as \par/\line. Mirrors RtfStripper.appendHtmlTag so the MSG and PST
                // de-encapsulation forks recover byte-identical HTML.
                if (current != '\r' && current != '\n') {
                    html.append(current);
                }
                index++;
                continue;
            }
            if (index + 1 >= content.length()) {
                break; // dangling backslash at the end of the tag content
            }
            char escaped = content.charAt(index + 1);
            if (escaped == '\'') {
                if (index + 4 > content.length()) {
                    break; // truncated \'hh escape at the end of the tag content
                }
                var hexBuffer = new ByteArrayOutputStream();
                while (index + 4 <= content.length()
                        && content.charAt(index) == '\\'
                        && content.charAt(index + 1) == '\'') {
                    try {
                        hexBuffer.write(Integer.parseInt(content.substring(index + 2, index + 4), 16));
                    } catch (Exception ignored) {
                        // malformed \'hh escape
                    }
                    index += 4;
                }
                try {
                    html.append(new String(hexBuffer.toByteArray(), charsetName));
                } catch (Exception ignored) {
                    // unsupported charset name — skip the escaped bytes
                }
                continue;
            }
            if (escaped == '{' || escaped == '}' || escaped == '\\') {
                html.append(escaped);
                index += 2;
                continue;
            }
            // Control word: consume the word (letters) then an optional numeric parameter and its
            // single delimiting space, translating the line-break words and \\uN.
            int wordEnd = index + 1;
            while (wordEnd < content.length() && Character.isLetter(content.charAt(wordEnd))) {
                wordEnd++;
            }
            String word = content.substring(index + 1, wordEnd);
            int paramStart = wordEnd;
            if (wordEnd < content.length() && content.charAt(wordEnd) == '-') {
                wordEnd++;
            }
            while (wordEnd < content.length() && Character.isDigit(content.charAt(wordEnd))) {
                wordEnd++;
            }
            int paramEnd = wordEnd;
            if (wordEnd < content.length() && content.charAt(wordEnd) == ' ') {
                wordEnd++;
            }
            index = wordEnd;
            switch (word) {
                case "par", "line" -> html.append("\r\n");
                case "tab" -> html.append('\t');
                case "uc" -> {
                    try {
                        unicodeFallbackCount = Integer.parseInt(content.substring(paramStart, paramEnd));
                    } catch (NumberFormatException ignored) {
                        // implausibly long "uc" parameter — keep the current fallback count
                    }
                }
                case "u" -> {
                    try {
                        // Match extractHtmlFromRtf: a \\uN parameter is nominally signed 16-bit but is
                        // also seen unsigned in the wild; accept both and truncate to a char.
                        int codePoint = Integer.parseInt(content.substring(paramStart, paramEnd));
                        html.append((char) codePoint);
                    } catch (NumberFormatException ignored) {
                        // malformed \\uN escape — skip this code point
                    }
                    // Skip the \\uc-counted ANSI fallback characters that trail the escape so they do
                    // not leak into the attribute value as duplicate text (shared with extractHtmlFromRtf).
                    index = skipUnicodeFallback(content, index, unicodeFallbackCount);
                }
                default -> {
                    // other control words (formatting noise) are dropped
                }
            }
        }
        return html.toString();
    }

    /**
     * The decompressed PR_RTF_COMPRESSED text exactly as stored (no encapsulation filtering), or an
     * empty string. The LzFu decode reads the bytes as windows-1252, which round-trips all 256 byte
     * values — encoding the result back as windows-1252 recovers the original RTF bytes.
     */
    public String getRawRtfBody() {
        if (cachedRawRtf != null) {
            return cachedRawRtf;
        }
        String rtf = "";
        if (propertyContext != null
                && propertyContext.getProperty(MapiProperties.PR_RTF_COMPRESSED) instanceof byte[] compressed) {
            try {
                rtf = LzFu.decode(compressed).trim();
            } catch (RuntimeException exception) {
                LOG.log(
                        System.Logger.Level.DEBUG,
                        () -> "Failed to decompress RTF body for message node " + nid,
                        exception);
            }
        }
        cachedRawRtf = rtf;
        return rtf;
    }

    /**
     * The decompressed PR_RTF_COMPRESSED body as its exact bytes (leading/trailing ASCII whitespace
     * trimmed, matching {@link #getRawRtfBody()}'s {@code trim()}), or an empty array. Unlike
     * {@code getRawRtfBody}, this does not round-trip through windows-1252 — which leaves five byte
     * values undefined and maps them to {@code '?'} — so it is byte-faithful, and is what a
     * {@code body.rtf} attachment should carry.
     */
    public byte[] getRawRtfBytes() {
        if (propertyContext != null
                && propertyContext.getProperty(MapiProperties.PR_RTF_COMPRESSED) instanceof byte[] compressed) {
            try {
                return CompressedRtf.decompressToBytes(compressed);
            } catch (RuntimeException exception) {
                LOG.log(
                        System.Logger.Level.DEBUG,
                        () -> "Failed to decompress RTF body for message node " + nid,
                        exception);
            }
        }
        return new byte[0];
    }

    /**
     * The RTF body, or an empty string if the message has none (or its RTF only encapsulates
     * another format — see {@link #isEncapsulationRtf}).
     */
    public String getRtfBody() {
        String rtf = getRawRtfBody();
        if (isEncapsulationRtf(rtf)) {
            return ""; // Encapsulated HTML/plain text, not intended to be shown as RTF
        }
        return rtf;
    }

    /**
     * Whether the RTF merely encapsulates another body format ([MS-OXRTFEX] §2.1.3.1): {@code
     * \fromhtml} wraps the HTML body (surfaced by {@link #getHtmlBody()}) and {@code \fromtext}
     * wraps the plain-text body (already stored in PR_BODY), so neither is a genuine RTF body.
     * Package-private for testing.
     */
    static boolean isEncapsulationRtf(String rtf) {
        return rtf.contains("\\fromhtml") || rtf.contains("\\fromtext");
    }

    /** The raw PR_RTF_COMPRESSED bytes, or {@code null} if absent. */
    public byte[] getRtfCompressed() {
        if (propertyContext == null) return null;
        return propertyContext.getProperty(MapiProperties.PR_RTF_COMPRESSED) instanceof byte[] value ? value : null;
    }

    /** The sender as {@code Name <address>}, name-only or address-only, or an empty string. */
    public String getSender() {
        String nameStr = getSenderName();
        String emailStr = getSenderEmail();
        if (!nameStr.isEmpty() && !emailStr.isEmpty()) return nameStr + " <" + emailStr + ">";
        return !nameStr.isEmpty() ? nameStr : emailStr;
    }

    /** The sender's display name, or an empty string if absent. */
    public String getSenderName() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_SENDER_NAME_W) instanceof String value ? value : "";
    }

    /** The sender's resolved address per the configured {@link AddressPreference}, or an empty string. */
    public String getSenderEmail() {
        if (propertyContext == null) return "";
        return resolveSenderEmail(propertyContext::getProperty, addressPreference);
    }

    /**
     * Resolves a usable sender address. Prefers the cached SMTP addresses
     * (PR_SENDER_SMTP_ADDRESS / PR_SENT_REPRESENTING_SMTP_ADDRESS); otherwise falls back to
     * PR_SENDER_EMAIL_ADDRESS / PR_SENT_REPRESENTING_EMAIL_ADDRESS, which hold a real SMTP
     * address when the addrtype is "SMTP" and the Exchange legacyExchangeDN when it is "EX".
     * The legacyDN is kept rather than dropped so Exchange-only senders are not lost.
     *
     * <p>Package-private for testing.
     */
    static String resolveSenderEmail(IntFunction<Object> properties) {
        return resolveSenderEmail(properties, AddressPreference.PREFER_SMTP);
    }

    static String resolveSenderEmail(IntFunction<Object> properties, AddressPreference addressPreference) {
        if (addressPreference == AddressPreference.PREFER_LEGACY_DN) {
            if (properties.apply(MapiProperties.PR_SENDER_EMAIL_ADDRESS_W) instanceof String email
                    && !email.isEmpty()) {
                return email;
            }
            if (properties.apply(MapiProperties.PR_SENT_REPRESENTING_EMAIL_ADDRESS_W) instanceof String repEmail
                    && !repEmail.isEmpty()) {
                return repEmail;
            }
            if (properties.apply(MapiProperties.PR_SENDER_SMTP_ADDRESS_W) instanceof String smtp && !smtp.isEmpty()) {
                return smtp;
            }
            if (properties.apply(MapiProperties.PR_SENT_REPRESENTING_SMTP_ADDRESS_W) instanceof String repSmtp
                    && !repSmtp.isEmpty()) {
                return repSmtp;
            }
        } else {
            if (properties.apply(MapiProperties.PR_SENDER_SMTP_ADDRESS_W) instanceof String smtp && !smtp.isEmpty()) {
                return smtp;
            }
            if (properties.apply(MapiProperties.PR_SENT_REPRESENTING_SMTP_ADDRESS_W) instanceof String repSmtp
                    && !repSmtp.isEmpty()) {
                return repSmtp;
            }
            if (properties.apply(MapiProperties.PR_SENDER_EMAIL_ADDRESS_W) instanceof String email
                    && !email.isEmpty()) {
                String addrType =
                        properties.apply(MapiProperties.PR_SENDER_ADDRTYPE_W) instanceof String type ? type : "";
                return imceaEncapsulate(addrType, email);
            }
            if (properties.apply(MapiProperties.PR_SENT_REPRESENTING_EMAIL_ADDRESS_W) instanceof String repEmail
                    && !repEmail.isEmpty()) {
                String addrType =
                        properties.apply(MapiProperties.PR_SENT_REPRESENTING_ADDRTYPE_W) instanceof String type
                                ? type
                                : "";
                return imceaEncapsulate(addrType, repEmail);
            }
        }
        return "";
    }

    /**
     * The display name of the author the message was sent on behalf of
     * (PR_SENT_REPRESENTING_NAME), or an empty string if absent.
     */
    public String getSentRepresentingName() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_SENT_REPRESENTING_NAME_W) instanceof String value
                ? value
                : "";
    }

    /**
     * The resolved address of the author the message was sent on behalf of, honouring the
     * configured {@link AddressPreference}, or an empty string. When this differs from
     * {@link #getSenderEmail()}, the message was sent on behalf of someone else — RFC 5322 maps
     * the pair to {@code From:} (this address) plus {@code Sender:} (the actual sender).
     */
    public String getSentRepresentingEmail() {
        if (propertyContext == null) return "";
        return resolveSentRepresentingEmail(propertyContext::getProperty, addressPreference);
    }

    // Package-private for testing
    static String resolveSentRepresentingEmail(IntFunction<Object> properties, AddressPreference addressPreference) {
        if (addressPreference == AddressPreference.PREFER_LEGACY_DN) {
            if (properties.apply(MapiProperties.PR_SENT_REPRESENTING_EMAIL_ADDRESS_W) instanceof String email
                    && !email.isEmpty()) {
                return email;
            }
            if (properties.apply(MapiProperties.PR_SENT_REPRESENTING_SMTP_ADDRESS_W) instanceof String smtp
                    && !smtp.isEmpty()) {
                return smtp;
            }
        } else {
            if (properties.apply(MapiProperties.PR_SENT_REPRESENTING_SMTP_ADDRESS_W) instanceof String smtp
                    && !smtp.isEmpty()) {
                return smtp;
            }
            if (properties.apply(MapiProperties.PR_SENT_REPRESENTING_EMAIL_ADDRESS_W) instanceof String email
                    && !email.isEmpty()) {
                String addrType =
                        properties.apply(MapiProperties.PR_SENT_REPRESENTING_ADDRTYPE_W) instanceof String type
                                ? type
                                : "";
                return imceaEncapsulate(addrType, email);
            }
        }
        return "";
    }

    /** The display string of the To: recipients (PR_DISPLAY_TO), or an empty string. */
    public String getTo() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_DISPLAY_TO_W) instanceof String value ? value : "";
    }

    /** The display string of the Cc: recipients (PR_DISPLAY_CC), or an empty string. */
    public String getDisplayCc() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_DISPLAY_CC_W) instanceof String value ? value : "";
    }

    /** The display string of the Bcc: recipients (PR_DISPLAY_BCC), or an empty string. */
    public String getDisplayBcc() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_DISPLAY_BCC_W) instanceof String value ? value : "";
    }

    /**
     * The message's origination time, or {@code null} if absent. Prefers PR_CLIENT_SUBMIT_TIME (the
     * RFC 5322 §3.6.1 Date semantics) and falls back to the delivery time.
     */
    public Instant getMessageDate() {
        if (propertyContext == null) return null;
        Instant submit = nonSentinelDate(propertyContext.getProperty(MapiProperties.PR_CLIENT_SUBMIT_TIME));
        return submit != null
                ? submit
                : nonSentinelDate(propertyContext.getProperty(MapiProperties.PR_MESSAGE_DELIVERY_TIME));
    }

    /**
     * Coerces a property value to an origination {@link Instant}, treating the FILETIME-0 sentinel
     * (1601-01-01T00:00:00Z) as "no date". Some non-Outlook writers store an unsent item's
     * PR_CLIENT_SUBMIT_TIME as 0 instead of omitting it, and a literal 1601 origination time is never a
     * real Date (RFC 5322 §3.6.1); returning {@code null} lets {@link #getMessageDate} fall through to
     * the delivery time instead of emitting {@code Date: ... 1 Jan 1601}.
     */
    static Instant nonSentinelDate(Object value) {
        return value instanceof Instant instant && instant.isAfter(FILETIME_ZERO) ? instant : null;
    }

    /** The internet Message-ID, or {@code null} if absent. */
    public String getMessageId() {
        if (propertyContext == null) return null;
        return propertyContext.getProperty(MapiProperties.PR_INTERNET_MESSAGE_ID_W) instanceof String value
                ? value
                : null;
    }

    /** The original transport headers (PR_TRANSPORT_MESSAGE_HEADERS), or an empty string. */
    public String getTransportHeaders() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_TRANSPORT_MESSAGE_HEADERS_W) instanceof String value
                ? value
                : "";
    }

    /** Whether the message claims attachments (PR_HASATTACH). */
    public boolean hasAttachments() {
        if (propertyContext == null) return false;
        return switch (propertyContext.getProperty(MapiProperties.PR_HASATTACH)) {
            case Boolean flag -> flag;
            case Integer flag -> flag != 0;
            case null, default -> false;
        };
    }

    /**
     * The message's attachments, parsed from its attachment table; empty if it has none. Attachment
     * <em>content</em> is not materialized here — use {@link Attachment#getData()} or
     * {@link Attachment#openDataStream()} per attachment. Failures while reading the table degrade
     * to the attachments parsed so far (and are logged) so one bad attachment does not lose a
     * message.
     */
    public List<Attachment> getAttachments() {
        if (node == null || node.subBid() == 0) {
            return Collections.emptyList();
        }

        List<Attachment> attachments = new ArrayList<>();
        try {
            // The TC's subnode HNIDs (its row matrix once the table outgrows the heap, and any large
            // cell values) resolve in the attachment-table subnode's OWN tree ([MS-PST] §2.3.3.2), so
            // the table's entry — not the message node — must host the TableContext; the message's
            // tree stays available as a compatibility fallback.
            NodeEntry tableEntry = nodeDatabase.readSubnodeEntry(node.subBid(), NID_ATTACHMENT_TABLE);
            if (tableEntry == null || tableEntry.dataBid() == 0) return attachments;
            byte[] tableData = nodeDatabase.readNodeData(tableEntry.dataBid());
            if (tableData == null) return attachments;

            var tableContext = new TableContext(tableData, nodeDatabase, tableEntry, node, getString8Charset());
            for (Map<Integer, Object> row : tableContext.getRows()) {
                if (!(row.get(MapiProperties.PidTagLtpRowId) instanceof Integer attachNid)) {
                    continue;
                }
                NodeEntry attachEntry = nodeDatabase.readSubnodeEntry(node.subBid(), attachNid);
                if (attachEntry == null) {
                    continue;
                }
                byte[] attachPcData = nodeDatabase.readNodeData(attachEntry.dataBid());
                if (attachPcData != null) {
                    var attachPc = new PropertyContext(attachPcData, nodeDatabase, attachEntry);
                    attachPc.decodeString8(getString8Charset());
                    attachments.add(new Attachment(attachPc));
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, () -> "Failed to read attachments for message node " + nid, exception);
        }
        return attachments;
    }

    /**
     * Resolves the message embedded in {@code attachment} (attach method {@code afEmbeddedMessage}),
     * looking the embedded node up in the attachment's own sub-node tree. The returned message
     * inherits this message's {@link AddressPreference}. Returns {@code null} when the attachment
     * does not embed a message or the embedded node cannot be resolved (corrupted store).
     *
     * @throws IOException if the store cannot be read while resolving the embedded node
     */
    public Message readEmbeddedMessage(Attachment attachment) throws IOException {
        Objects.requireNonNull(attachment, "attachment");
        Integer embeddedNid = attachment.getEmbeddedMessageNodeId();
        NodeEntry attachmentNode = attachment.getNode();
        if (embeddedNid == null || attachmentNode == null || pstFile == null) {
            return null;
        }
        NodeEntry embeddedEntry = pstFile.readSubnodeEntry(attachmentNode.subBid(), embeddedNid);
        if (embeddedEntry == null) {
            return null;
        }
        var embeddedMessage = new Message(pstFile, embeddedEntry);
        embeddedMessage.setAddressPreference(addressPreference);
        return embeddedMessage;
    }

    /**
     * One row of the recipient table. {@link #type} is the MAPI recipient type: {@code 1} = To,
     * {@code 2} = Cc, {@code 3} = Bcc (PR_RECIPIENT_TYPE).
     */
    public static class Recipient {
        public final int type;
        public final String name;
        public final String email;

        public Recipient(int type, String name, String email) {
            this.type = type;
            this.name = name;
            this.email = email;
        }
    }

    /**
     * The message's recipients, parsed from its recipient table; empty if it has none. Failures
     * while reading the table degrade to an empty list (and are logged).
     */
    public List<Recipient> getRecipients() {
        if (node == null || node.subBid() == 0) return Collections.emptyList();
        List<Recipient> recipients = new ArrayList<>();
        try {
            // See getAttachments: the recipient table's subnode HNIDs resolve in the table entry's
            // own subnode tree, with the message's tree as the compatibility fallback.
            NodeEntry tableEntry = nodeDatabase.readSubnodeEntry(node.subBid(), NID_RECIPIENT_TABLE);
            if (tableEntry == null || tableEntry.dataBid() == 0) return recipients;
            byte[] tableData = nodeDatabase.readNodeData(tableEntry.dataBid());
            if (tableData == null) return recipients;

            var tableContext = new TableContext(tableData, nodeDatabase, tableEntry, node, getString8Charset());
            recipients = parseRecipients(tableContext.getRows(), addressPreference);
        } catch (IOException | RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, () -> "Failed to read recipients for message node " + nid, exception);
        }
        return recipients;
    }

    // Package-private for testing
    static List<Recipient> parseRecipients(List<Map<Integer, Object>> rows) {
        return parseRecipients(rows, AddressPreference.PREFER_SMTP);
    }

    static List<Recipient> parseRecipients(List<Map<Integer, Object>> rows, AddressPreference addressPreference) {
        var recipients = new ArrayList<Recipient>();
        for (var row : rows) {
            var typeValue = row.get(MapiProperties.PR_RECIPIENT_TYPE);
            var type = typeValue instanceof Number number ? number.intValue() : 1; // default to TO

            var nameValue = row.get(MapiProperties.PR_DISPLAY_NAME_W);
            var name = nameValue instanceof String displayName ? displayName : "";

            recipients.add(new Recipient(type, name, resolveRecipientEmail(row, addressPreference)));
        }
        return recipients;
    }

    /**
     * Resolves a usable address for a recipient row. Prefers the cached SMTP address
     * (PR_SMTP_ADDRESS); otherwise falls back to PR_EMAIL_ADDRESS, which is a real SMTP
     * address when PR_ADDRTYPE is "SMTP" and the Exchange legacyExchangeDN when it is "EX".
     * The legacyDN is kept rather than dropped so Exchange-only recipients are not lost.
     */
    private static String resolveRecipientEmail(Map<Integer, Object> row, AddressPreference addressPreference) {
        if (addressPreference == AddressPreference.PREFER_LEGACY_DN) {
            if (row.get(MapiProperties.PR_EMAIL_ADDRESS_W) instanceof String email && !email.isEmpty()) {
                return email;
            }
            if (row.get(MapiProperties.PR_SMTP_ADDRESS_W) instanceof String smtp && !smtp.isEmpty()) {
                return smtp;
            }
        } else {
            if (row.get(MapiProperties.PR_SMTP_ADDRESS_W) instanceof String smtp && !smtp.isEmpty()) {
                return smtp;
            }
            if (row.get(MapiProperties.PR_EMAIL_ADDRESS_W) instanceof String email && !email.isEmpty()) {
                String addrType = row.get(MapiProperties.PR_ADDRTYPE) instanceof String type ? type : "";
                return imceaEncapsulate(addrType, email);
            }
        }
        return "";
    }

    /**
     * The reply recipients (PR_REPLY_RECIPIENT_ENTRIES, with PR_REPLY_RECIPIENT_NAMES as the
     * display-name fallback), or an empty list when the message names none or the entry list is
     * unparseable. Addresses honour this message's {@link AddressPreference} like every other
     * recipient. The {@link Recipient#type} of each entry is {@code 1} (To) — RFC 5322's Reply-To
     * has no type distinction.
     */
    public List<Recipient> getReplyTo() {
        if (propertyContext == null) return List.of();
        byte[] entries = propertyContext.getProperty(MapiProperties.PR_REPLY_RECIPIENT_ENTRIES) instanceof byte[] value
                ? value
                : null;
        String names = propertyContext.getProperty(MapiProperties.PR_REPLY_RECIPIENT_NAMES_W) instanceof String value
                ? value
                : "";
        return parseReplyRecipients(entries, names, getString8Charset(), addressPreference);
    }

    /**
     * The members of a distribution list ({@code IPM.DistList}), resolved from the named
     * PidLidDistributionListOneOffMembers property (preferred — every member is stored there as a
     * self-contained one-off ENTRYID) with PidLidDistributionListMembers as the fallback. Entries
     * that are neither one-off nor address-book ENTRYIDs are skipped; addresses honour this
     * message's {@link AddressPreference}. Empty for messages that are not distribution lists.
     */
    public List<Recipient> getDistributionListMembers() {
        if (propertyContext == null || pstFile == null) {
            return List.of();
        }
        var members = readEntryIdListMembers(0x8054); // PidLidDistributionListOneOffMembers
        if (members.isEmpty()) {
            members = readEntryIdListMembers(0x8055); // PidLidDistributionListMembers
        }
        return members;
    }

    private List<Recipient> readEntryIdListMembers(int namedPropertyId) {
        Integer propertyId = pstFile.namedPropertyId(PSETID_ADDRESS, namedPropertyId);
        if (propertyId == null || !(propertyContext.getProperty(propertyId) instanceof List<?> values)) {
            return List.of();
        }
        var members = new ArrayList<Recipient>();
        for (Object value : values) {
            if (value instanceof byte[] entryId && entryId.length >= 20) {
                var member = parseEntryIdRecipient(entryId, "", getString8Charset(), addressPreference);
                if (member != null) {
                    members.add(member);
                }
            }
        }
        return Collections.unmodifiableList(members);
    }

    /**
     * Parses a PR_REPLY_RECIPIENT_ENTRIES FLATENTRYLIST ([MS-OXCDATA] §2.3.3): cEntries and cbEntries
     * (4 bytes each), then per entry a 4-byte size and the ENTRYID bytes, padded to 4-byte alignment.
     * One-off and address-book ENTRYIDs are resolved to addresses; other providers and malformed
     * entries are skipped. Public because the same [MS-OXCDATA] structures appear in Outlook MSG
     * files: the MSG converter feeds the blob it reads through Apache POI into this parser.
     */
    public static List<Recipient> parseReplyRecipients(
            byte[] flatEntryList, String displayNames, Charset string8Charset, AddressPreference addressPreference) {
        if (flatEntryList == null || flatEntryList.length < 8) {
            return List.of();
        }
        var names = displayNames == null || displayNames.isBlank() ? new String[0] : displayNames.split(";");
        var buffer = ByteBuffer.wrap(flatEntryList).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(buffer.getInt(0));
        var recipients = new ArrayList<Recipient>();
        int offset = 8; // past cEntries + cbEntries
        for (int index = 0; index < count && offset + 4 <= flatEntryList.length; index++) {
            long entrySize = Integer.toUnsignedLong(buffer.getInt(offset));
            long entryEnd = offset + 4 + entrySize;
            if (entrySize == 0 || entryEnd > flatEntryList.length) {
                break; // malformed entry: stop rather than read past the blob
            }
            var entryId = Arrays.copyOfRange(flatEntryList, offset + 4, (int) entryEnd);
            String fallbackName = index < names.length ? names[index].trim() : "";
            var recipient = parseEntryIdRecipient(entryId, fallbackName, string8Charset, addressPreference);
            if (recipient != null) {
                recipients.add(recipient);
            }
            offset = (int) (entryEnd + ((4 - (entryEnd & 3)) & 3)); // next FLATENTRY is 4-byte aligned
        }
        return recipients;
    }

    private static Recipient parseEntryIdRecipient(
            byte[] entryId, String fallbackName, Charset string8Charset, AddressPreference addressPreference) {
        if (entryId.length < 20) {
            return null;
        }
        var providerUid = Arrays.copyOfRange(entryId, 4, 20);
        if (Arrays.equals(providerUid, ONE_OFF_PROVIDER_UID)) {
            return parseOneOffRecipient(entryId, fallbackName, string8Charset, addressPreference);
        }
        if (Arrays.equals(providerUid, ADDRESS_BOOK_PROVIDER_UID)) {
            // [MS-OXCDATA] §2.2.5.2: version (4) + type (4) + X500 DN (null-terminated, ASCII).
            if (entryId.length <= 28) {
                return null;
            }
            String legacyDn = readNulTerminated(entryId, 28, StandardCharsets.US_ASCII);
            if (legacyDn.isBlank()) {
                return null;
            }
            String email = addressPreference == AddressPreference.PREFER_LEGACY_DN
                    ? legacyDn
                    : imceaEncapsulate("EX", legacyDn);
            return new Recipient(1, fallbackName, email);
        }
        return null; // unknown provider — nothing address-like to extract
    }

    private static Recipient parseOneOffRecipient(
            byte[] entryId, String fallbackName, Charset string8Charset, AddressPreference addressPreference) {
        // [MS-OXCDATA] §2.2.5.1: version (2) + flags (2, bit 0x8000 = Unicode strings), then the
        // display name, address type and email address, each null-terminated.
        if (entryId.length < 24) {
            return null;
        }
        int flags = Short.toUnsignedInt(
                ByteBuffer.wrap(entryId).order(ByteOrder.LITTLE_ENDIAN).getShort(22));
        boolean unicode = (flags & 0x8000) != 0;
        var strings =
                readNulTerminatedStrings(entryId, 24, unicode ? StandardCharsets.UTF_16LE : string8Charset, unicode, 3);
        if (strings.size() < 3) {
            return null;
        }
        String name = strings.get(0);
        String addressType = strings.get(1);
        String email = strings.get(2);
        // Mirror the MSG one-off parser (DistributionListMembers.parseOneOffEntry): drop the member only
        // when it carries neither a display name nor an address. A name-only one-off (blank email) is kept
        // so its name still appears in the exported list / Reply-To as "Name <undisclosed@invalid>", rather
        // than being silently discarded as it was when a blank email alone disqualified it.
        boolean nameBlank = name.isBlank() && (fallbackName == null || fallbackName.isBlank());
        if (nameBlank && email.isBlank()) {
            return null;
        }
        if (addressPreference != AddressPreference.PREFER_LEGACY_DN) {
            email = imceaEncapsulate(addressType, email);
        }
        return new Recipient(1, !name.isBlank() ? name : fallbackName, email);
    }

    private static String readNulTerminated(byte[] data, int offset, Charset charset) {
        int end = offset;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, charset);
    }

    private static List<String> readNulTerminatedStrings(
            byte[] data, int offset, Charset charset, boolean unicode, int maxCount) {
        int step = unicode ? 2 : 1;
        var values = new ArrayList<String>(maxCount);
        int position = offset;
        while (values.size() < maxCount && position + step <= data.length) {
            int end = position;
            while (end + step <= data.length && !(unicode ? data[end] == 0 && data[end + 1] == 0 : data[end] == 0)) {
                end += step;
            }
            values.add(new String(data, position, end - position, charset));
            position = end + step;
        }
        return values;
    }

    PropertyContext getPropertyContext() {
        return propertyContext;
    }

    /**
     * The raw MAPI property value for the given 16-bit property id (the upper half of a full MAPI
     * property tag — pass {@code 0x0037}, not {@code 0x0037001F}), or {@code null} if the message
     * has no such property. Low-level access for ids the typed getters do not cover (e.g. the
     * spam-confidence level or appointment named properties resolved via
     * {@link PstFile#namedPropertyId}). PT_SYSTIME values surface as {@link Instant}, multi-valued
     * properties as immutable {@link List}s.
     */
    public Object getProperty(int propertyId) {
        return propertyContext == null ? null : propertyContext.getProperty(propertyId);
    }

    /**
     * The raw MAPI property value for the given 16-bit property id as a {@code String}, or
     * {@code null} if the property is absent or not a string.
     */
    public String getStringProperty(int propertyId) {
        return propertyContext == null ? null : propertyContext.getString(propertyId);
    }

    // Kept in sync with EmlSerializer.imceaEncapsulate in the plugin module (which cannot be
    // referenced from this standalone library); change both together.
    private static String imceaEncapsulate(String addrType, String address) {
        if (address == null || address.isBlank()) return address;
        // Only a value that actually parses as local@domain may pass through unencapsulated: an
        // Exchange X.500 DN such as /O=ORG/CN=USER@HOST contains "@" yet is not an addr-spec, and
        // emitting it raw produces an unparseable From/To header.
        if (looksLikeSmtpAddress(address)) return address;
        var resolvedType = addrType;
        if (resolvedType == null || resolvedType.isBlank()) {
            if (!address.startsWith("/")) {
                return address;
            }
            // An X.500 DN with no recorded address type is still an Exchange address; encapsulate it
            // the way Exchange itself would (IMCEAEX-...).
            resolvedType = "EX";
        }
        if (resolvedType.equalsIgnoreCase("SMTP")) return address;

        StringBuilder builder = new StringBuilder("IMCEA");
        builder.append(resolvedType.toUpperCase(Locale.ROOT)).append("-");

        for (int i = 0; i < address.length(); i++) {
            char chr = address.charAt(i);
            if ((chr >= 'a' && chr <= 'z') || (chr >= 'A' && chr <= 'Z') || (chr >= '0' && chr <= '9') || chr == '-') {
                builder.append(chr);
            } else if (chr == '/') {
                builder.append('_');
            } else {
                builder.append(String.format("_x%04X_", (int) chr));
            }
        }
        // ".invalid" (RFC 2606) marks the encapsulated address as synthesized — a real Exchange
        // deployment would use its own accepted domain, which a PST does not record.
        builder.append("@invalid");
        return builder.toString();
    }

    /**
     * True when the value plausibly parses as an SMTP addr-spec: a single {@code @} separating
     * non-empty halves, with no whitespace/control characters, X.500 DN separators, or angle
     * brackets. Kept deliberately loose otherwise — the goal is to reject values that would render
     * an address header unparseable, not to validate RFC 5321 syntax.
     */
    private static boolean looksLikeSmtpAddress(String address) {
        var atIndex = address.indexOf('@');
        if (atIndex <= 0 || atIndex != address.lastIndexOf('@') || atIndex == address.length() - 1) {
            return false;
        }
        for (var index = 0; index < address.length(); index++) {
            var character = address.charAt(index);
            if (character <= ' ' || character == '/' || character == '<' || character == '>') {
                return false;
            }
        }
        return true;
    }
}
