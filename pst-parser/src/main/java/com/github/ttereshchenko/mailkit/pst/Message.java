package com.github.ttereshchenko.mailkit.pst;

// TODO: re-visit log
// import com.intellij.openapi.diagnostic.Logger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * A single message within a PST/OST store, wrapping its {@link PropertyContext}.
 *
 * <p>Exposes the MAPI properties most relevant to EML export — subject, sender/recipients, dates,
 * transport headers, plain-text/HTML/RTF bodies and attachments. Address resolution honours an
 * {@link AddressPreference} (routable SMTP vs. Exchange legacy DN). Construct one from a node id (or
 * {@link NodeEntry}) obtained via {@link Folder#getMessages()}.
 */
public class Message {

    // TODO: re-visit log
    // private static final Logger LOG = Logger.getInstance(Message.class);

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

    /**
     * Selects which address a message prefers when both a routable SMTP address and an Exchange
     * legacy distinguished name are available. Value-only by design: human-readable labels are a
     * presentation concern and belong to the consuming UI, not to this parser library.
     */
    public enum AddressPreference {
        PREFER_SMTP,
        PREFER_LEGACY_DN
    }

    public void setAddressPreference(AddressPreference addressPreference) {
        this.addressPreference = addressPreference != null ? addressPreference : AddressPreference.PREFER_SMTP;
    }

    public Message(PstFile pstFile, int nid) {
        this.pstFile = pstFile;
        this.nid = nid;
        this.nodeDatabase = pstFile.nodeDatabase();
        this.node = nodeDatabase.getNode(nid);
        loadProperties();
    }

    public Message(PstFile pstFile, NodeEntry node) {
        this.pstFile = pstFile;
        this.nid = node.nodeId();
        this.nodeDatabase = pstFile.nodeDatabase();
        this.node = node;
        loadProperties();
    }

    private void loadProperties() {
        try {
            if (node == null) return;

            byte[] data = nodeDatabase.readNodeData(node.dataBid());
            this.propertyContext = new PropertyContext(data, nodeDatabase, node);
            Charset charset = getMessageCharset();
            this.propertyContext.decodeString8(charset);
        } catch (Exception exception) {
            // TODO: re-visit log
            // LOG.warn("Failed to load properties for message node " + nid, exception);
        }
    }

    public int getNid() {
        return nid;
    }

    public String getMessageClass() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_MESSAGE_CLASS_W) instanceof String value ? value : "";
    }

    public String getSubject() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_SUBJECT_W) instanceof String value ? value : "";
    }

    public String getBody() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_BODY_W) instanceof String value ? value : "";
    }

    public String getHtmlBody() {
        if (propertyContext == null) return "";
        Object obj = propertyContext.getProperty(MapiProperties.PR_HTML);
        if (obj instanceof String value) return value;
        if (obj instanceof byte[] bytes) {
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
            Object cpidObj = propertyContext.getProperty(MapiProperties.PR_INTERNET_CPID);
            if (cpidObj == null) {
                cpidObj = propertyContext.getProperty(MapiProperties.PR_MESSAGE_CODEPAGE);
            }
            if (cpidObj instanceof Number number) {
                charset = codePageToCharset(number.intValue());
            }
        }
        cachedCharset = charset;
        return charset;
    }

    private Charset codePageToCharset(int cpid) {
        return switch (cpid) {
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
            default -> charsetOrDefault("windows-" + cpid);
        };
    }

    private static Charset charsetOrDefault(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception exception) {
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
            } catch (Exception exception) {
                // TODO: re-visit log
                // LOG.debug("Failed to decompress RTF body for message node " + nid, exception);
            }
        }
        cachedRawRtf = rtf;
        return rtf;
    }

    public String getRtfBody() {
        String rtf = getRawRtfBody();
        if (rtf.contains("\\fromhtml")) {
            return ""; // It's encapsulated HTML, not intended to be shown as RTF
        }
        return rtf;
    }

    public byte[] getRtfCompressed() {
        if (propertyContext == null) return null;
        return propertyContext.getProperty(MapiProperties.PR_RTF_COMPRESSED) instanceof byte[] value ? value : null;
    }

    public String getSender() {
        String nameStr = getSenderName();
        String emailStr = getSenderEmail();
        if (!nameStr.isEmpty() && !emailStr.isEmpty()) return nameStr + " <" + emailStr + ">";
        return !nameStr.isEmpty() ? nameStr : emailStr;
    }

    public String getSenderName() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_SENDER_NAME_W) instanceof String value ? value : "";
    }

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

    public String getTo() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_DISPLAY_TO_W) instanceof String value ? value : "";
    }

    public String getDisplayCc() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_DISPLAY_CC_W) instanceof String value ? value : "";
    }

    public String getDisplayBcc() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_DISPLAY_BCC_W) instanceof String value ? value : "";
    }

    public Date getMessageDate() {
        if (propertyContext == null) return null;
        // RFC 5322 §3.6.1: the Date header is the origination (submit) time, so prefer
        // PR_CLIENT_SUBMIT_TIME and fall back to the delivery time only when it is absent.
        Object obj = propertyContext.getProperty(MapiProperties.PR_CLIENT_SUBMIT_TIME);
        if (obj == null) {
            obj = propertyContext.getProperty(MapiProperties.PR_MESSAGE_DELIVERY_TIME);
        }
        return obj instanceof Date date ? date : null;
    }

    public String getMessageId() {
        if (propertyContext == null) return null;
        return propertyContext.getProperty(MapiProperties.PR_INTERNET_MESSAGE_ID_W) instanceof String value
                ? value
                : null;
    }

    public String getTransportHeaders() {
        if (propertyContext == null) return "";
        return propertyContext.getProperty(MapiProperties.PR_TRANSPORT_MESSAGE_HEADERS_W) instanceof String value
                ? value
                : "";
    }

    public boolean hasAttachments() {
        if (propertyContext == null) return false;
        Object obj = propertyContext.getProperty(MapiProperties.PR_HASATTACH);
        if (obj instanceof Boolean flag) return flag;
        if (obj instanceof Integer flag) return flag != 0;
        return false;
    }

    public List<Attachment> getAttachments() {
        if (node == null || node.subBid() == 0) {
            return Collections.emptyList();
        }

        List<Attachment> attachments = new ArrayList<>();
        try {
            byte[] tableData = nodeDatabase.readSubnodeData(node.subBid(), NID_ATTACHMENT_TABLE);
            if (tableData == null) return attachments;

            TableContext tableContext = new TableContext(tableData, nodeDatabase, node, getMessageCharset());
            for (Map<Integer, Object> row : tableContext.getRows()) {
                Integer attachNid = (Integer) row.get(MapiProperties.PidTagLtpRowId);
                if (attachNid != null) {
                    NodeEntry attachEntry = nodeDatabase.readSubnodeEntry(node.subBid(), attachNid);
                    if (attachEntry != null) {
                        byte[] attachPcData = nodeDatabase.readNodeData(attachEntry.dataBid());
                        if (attachPcData != null) {
                            PropertyContext attachPc = new PropertyContext(attachPcData, nodeDatabase, attachEntry);
                            attachPc.decodeString8(getMessageCharset());
                            attachments.add(new Attachment(attachPc));
                        }
                    }
                }
            }
        } catch (IOException exception) {
            // TODO: re-visit log
            // LOG.warn("Failed to read attachments for message node " + nid, exception);
        }
        return attachments;
    }

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

    public List<Recipient> getRecipients() {
        if (node == null || node.subBid() == 0) return Collections.emptyList();
        List<Recipient> recipients = new ArrayList<>();
        try {
            byte[] tableData = nodeDatabase.readSubnodeData(node.subBid(), NID_RECIPIENT_TABLE);
            if (tableData == null) return recipients;

            TableContext tableContext = new TableContext(tableData, nodeDatabase, node, getMessageCharset());
            recipients = parseRecipients(tableContext.getRows(), addressPreference);
        } catch (IOException exception) {
            // TODO: re-visit log
            // LOG.warn("Failed to read recipients for message node " + nid, exception);
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
            var typeObj = row.get(MapiProperties.PR_RECIPIENT_TYPE);
            var type = typeObj instanceof Number number ? number.intValue() : 1; // default to TO

            var nameObj = row.get(MapiProperties.PR_DISPLAY_NAME_W);
            var name = nameObj instanceof String displayName ? displayName : "";

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
     * The raw MAPI property value for the given tag, or {@code null} if the message has no such
     * property. Low-level access for tags the typed getters do not cover (e.g. the spam-confidence
     * level or appointment start/end named properties).
     */
    public Object getProperty(int propertyTag) {
        return propertyContext == null ? null : propertyContext.getProperty(propertyTag);
    }

    /**
     * The raw MAPI property value for the given tag as a {@code String}, or {@code null} if the
     * property is absent or not a string.
     */
    public String getStringProperty(int propertyTag) {
        return propertyContext == null ? null : propertyContext.getString(propertyTag);
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
