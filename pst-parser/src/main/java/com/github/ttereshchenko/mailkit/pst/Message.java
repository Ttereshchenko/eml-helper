package com.github.ttereshchenko.mailkit.pst;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

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

    private static final int NID_ATTACHMENT_TABLE = 0x0671;
    private static final int NID_RECIPIENT_TABLE = 0x0692;

    private final PstFile pstFile;
    private final int nid;
    private final NodeDatabase nodeDatabase;
    private final NodeEntry node;
    private PropertyContext propertyContext;
    private AddressPreference addressPreference = AddressPreference.PREFER_SMTP;
    private Charset cachedCharset;
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
            this.propertyContext.decodeString8(getMessageCharset());
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

    /** The subject, or an empty string if absent. */
    public String getSubject() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_SUBJECT_W) instanceof String value ? value : "";
    }

    /** The plain-text body, or an empty string if absent. */
    public String getBody() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_BODY_W) instanceof String value ? value : "";
    }

    /**
     * The HTML body, or an empty string if the message has none. The returned markup is
     * <em>normalized</em> for re-serialization, not the raw stored bytes: PR_HTML byte content is
     * decoded with the message's code page and any {@code <meta charset=...>} is rewritten to UTF-8,
     * and when only an RTF body exists its encapsulated HTML (\fromhtml) is extracted. Use
     * {@link #getProperty} with {@link MapiProperties#PR_HTML} for the raw bytes.
     */
    public String getHtmlBody() {
        if (propertyContext == null) return "";
        Object value = propertyContext.getProperty(MapiProperties.PR_HTML);
        if (value instanceof String text) return text;
        if (value instanceof byte[] bytes) {
            Charset charset = getMessageCharset();
            String decoded = new String(bytes, charset).trim();
            decoded = decoded.replaceAll("(?i)(<meta[^>]*charset=[\"']?)[^\"'>]+([\"']?[^>]*>)", "$1utf-8$2");
            return decoded;
        }

        String rtf = getRawRtfBody();
        if (rtf.contains("\\fromhtml")) {
            return extractHtmlFromRtf(rtf, getMessageCharset().name());
        }
        return "";
    }

    private Charset getMessageCharset() {
        if (cachedCharset != null) {
            return cachedCharset;
        }
        Charset charset = Charset.forName("windows-1252");
        if (propertyContext != null) {
            Object codePage = propertyContext.getProperty(MapiProperties.PR_INTERNET_CPID);
            if (codePage == null) {
                codePage = propertyContext.getProperty(MapiProperties.PR_MESSAGE_CODEPAGE);
            }
            if (codePage instanceof Number number) {
                charset = codePageToCharset(number.intValue());
            }
        }
        cachedCharset = charset;
        return charset;
    }

    private Charset codePageToCharset(int codePageId) {
        return switch (codePageId) {
            case 1200 -> StandardCharsets.UTF_16LE;
            case 1201 -> StandardCharsets.UTF_16BE;
            case 20127 -> StandardCharsets.US_ASCII;
            case 65001 -> StandardCharsets.UTF_8;
            case 28591 -> StandardCharsets.ISO_8859_1;
            case 28592 -> charsetOrDefault("ISO-8859-2");
            case 932 -> charsetOrDefault("Shift_JIS");
            case 936 -> charsetOrDefault("GBK");
            case 949 -> charsetOrDefault("EUC-KR");
            case 950 -> charsetOrDefault("Big5");
            case 50220, 50221, 50222 -> charsetOrDefault("ISO-2022-JP");
            case 50225 -> charsetOrDefault("ISO-2022-KR");
            case 51932 -> charsetOrDefault("EUC-JP");
            case 51949 -> charsetOrDefault("EUC-KR");
            default -> charsetOrDefault("windows-" + codePageId);
        };
    }

    private static Charset charsetOrDefault(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception ignored) {
            return Charset.forName("windows-1252");
        }
    }

    static String extractHtmlFromRtf(String rtf, String charsetName) {
        StringBuilder html = new StringBuilder();
        int index = 0;
        boolean inHtmlRtf = false;
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

            if (rtf.startsWith("{\\*\\htmltag", index)) {
                int end = rtf.indexOf("}", index);
                if (end != -1) {
                    String tag = rtf.substring(index + 11, end).trim();
                    tag = tag.replaceFirst("^\\d+\\s*", "");
                    if (!tag.equals("\\par") && !tag.matches("\\d+")) {
                        html.append(tag);
                    } else if (tag.equals("\\par")) {
                        html.append("\r\n");
                    }
                    index = end + 1;
                    continue;
                }
            }
            if (inHtmlRtf) {
                index++;
                continue;
            }
            if (rtf.charAt(index) == '{' || rtf.charAt(index) == '}') {
                index++;
                continue;
            }
            if (rtf.charAt(index) == '\\') {
                if (index + 3 < rtf.length() && rtf.charAt(index + 1) == '\'') {
                    ByteArrayOutputStream hexBuffer = new ByteArrayOutputStream();
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
                if (index + 2 < rtf.length()
                        && rtf.charAt(index + 1) == 'u'
                        && (Character.isDigit(rtf.charAt(index + 2)) || rtf.charAt(index + 2) == '-')) {
                    int endNum = index + 2;
                    if (rtf.charAt(endNum) == '-') endNum++;
                    while (endNum < rtf.length() && Character.isDigit(rtf.charAt(endNum))) endNum++;
                    try {
                        short codePoint = Short.parseShort(rtf.substring(index + 2, endNum));
                        html.append((char) codePoint);
                    } catch (Exception ignored) {
                        // malformed \\uN escape — skip this code point
                    }
                    index = endNum;
                    // skip substitute char
                    if (index < rtf.length() && rtf.charAt(index) == ' ') index++;
                    else if (index + 3 < rtf.length() && rtf.charAt(index) == '\\' && rtf.charAt(index + 1) == '\'')
                        index += 4;
                    else if (index < rtf.length() && rtf.charAt(index) == '?') index++;
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
            html.append(rtf.charAt(index));
            index++;
        }
        return html.toString().trim();
    }

    private String getRawRtfBody() {
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
     * The RTF body, or an empty string if the message has none (or its RTF only encapsulates HTML,
     * which {@link #getHtmlBody()} surfaces instead).
     */
    public String getRtfBody() {
        String rtf = getRawRtfBody();
        if (rtf.contains("\\fromhtml")) {
            return ""; // It's encapsulated HTML, not intended to be shown as RTF
        }
        return rtf;
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
        Object value = propertyContext.getProperty(MapiProperties.PR_CLIENT_SUBMIT_TIME);
        if (value == null) {
            value = propertyContext.getProperty(MapiProperties.PR_MESSAGE_DELIVERY_TIME);
        }
        return value instanceof Instant instant ? instant : null;
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
            byte[] tableData = nodeDatabase.readSubnodeData(node.subBid(), NID_ATTACHMENT_TABLE);
            if (tableData == null) return attachments;

            var tableContext = new TableContext(tableData, nodeDatabase, node, getMessageCharset());
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
                    attachPc.decodeString8(getMessageCharset());
                    attachments.add(new Attachment(attachPc));
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, () -> "Failed to read attachments for message node " + nid, exception);
        }
        return attachments;
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
            byte[] tableData = nodeDatabase.readSubnodeData(node.subBid(), NID_RECIPIENT_TABLE);
            if (tableData == null) return recipients;

            var tableContext = new TableContext(tableData, nodeDatabase, node, getMessageCharset());
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

    private static String imceaEncapsulate(String addrType, String address) {
        if (address == null || address.isBlank()) return address;
        if (addrType == null || addrType.equalsIgnoreCase("SMTP") || address.contains("@")) return address;

        StringBuilder builder = new StringBuilder("IMCEA");
        builder.append(addrType.toUpperCase()).append("-");

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
        builder.append("@example.com");
        return builder.toString();
    }
}
