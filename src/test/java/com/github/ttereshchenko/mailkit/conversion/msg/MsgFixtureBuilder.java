package com.github.ttereshchenko.mailkit.conversion.msg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Test-only builder that emits minimal but valid MSG (OLE2 compound document) byte arrays consumable
 * by {@link org.apache.poi.hsmf.MAPIMessage}. Used in place of pre-built binary fixtures so the
 * test corpus stays deterministic and reproducible without shipping opaque blobs.
 */
final class MsgFixtureBuilder {

    private static final int RECIPIENT_TYPE_TO = 1;
    private static final int RECIPIENT_TYPE_CC = 2;
    private static final int RECIPIENT_TYPE_BCC = 3;

    private static final int FLAG_READABLE_WRITABLE = 0x06;
    private static final int TYPE_UNICODE = 0x001F;
    private static final int TYPE_BINARY = 0x0102;
    private static final int TYPE_LONG = 0x0003;
    private static final int TYPE_BOOLEAN = 0x000B;
    private static final int TYPE_SYSTIME = 0x0040;

    private static final int TAG_SUBJECT = (0x0037 << 16) | TYPE_UNICODE;
    private static final int TAG_SENDER_ADDRTYPE = (0x0C1E << 16) | TYPE_UNICODE;
    private static final int TAG_SENDER_SMTP_ADDRESS = (0x5D01 << 16) | TYPE_UNICODE;
    private static final int TAG_DISPLAY_TO = (0x0E04 << 16) | TYPE_UNICODE;
    private static final int TAG_REPORT_TEXT = (0x1001 << 16) | TYPE_UNICODE;
    private static final int TAG_SPAM_CONFIDENCE_LEVEL = (0x4076 << 16) | TYPE_LONG;
    private static final int TAG_RTF_COMPRESSED = (0x1009 << 16) | TYPE_BINARY;
    private static final int TAG_RECIPIENT_ADDRTYPE = (0x3002 << 16) | TYPE_UNICODE;
    private static final int TAG_BODY = (0x1000 << 16) | TYPE_UNICODE;
    private static final int TAG_BODY_HTML_UNICODE = (0x1013 << 16) | TYPE_UNICODE;
    private static final int TAG_SENDER_NAME = (0x0C1A << 16) | TYPE_UNICODE;
    private static final int TAG_SENDER_EMAIL_ADDRESS = (0x0C1F << 16) | TYPE_UNICODE;
    private static final int TAG_INTERNET_MESSAGE_ID = (0x1035 << 16) | TYPE_UNICODE;
    private static final int TAG_INTERNET_REFERENCES = (0x1039 << 16) | TYPE_UNICODE;
    private static final int TAG_IN_REPLY_TO_ID = (0x1042 << 16) | TYPE_UNICODE;
    private static final int TAG_MESSAGE_CLASS = (0x001A << 16) | TYPE_UNICODE;
    private static final int TAG_TRANSPORT_HEADERS = (0x007D << 16) | TYPE_UNICODE;
    private static final int TAG_MESSAGE_DELIVERY_TIME = (0x0E06 << 16) | TYPE_SYSTIME;
    private static final int TAG_SENT_REPRESENTING_NAME = (0x0042 << 16) | TYPE_UNICODE;
    private static final int TAG_SENT_REPRESENTING_SMTP_ADDRESS = (0x5D02 << 16) | TYPE_UNICODE;
    private static final int TAG_IMPORTANCE = (0x0017 << 16) | TYPE_LONG;
    private static final int TAG_SENSITIVITY = (0x0036 << 16) | TYPE_LONG;
    private static final int TAG_CONVERSATION_TOPIC = (0x0070 << 16) | TYPE_UNICODE;
    private static final int TAG_CONVERSATION_INDEX = (0x0071 << 16) | TYPE_BINARY;
    private static final int TAG_READ_RECEIPT_REQUESTED = (0x0029 << 16) | TYPE_BOOLEAN;
    private static final int TAG_REPLY_RECIPIENT_ENTRIES = (0x004F << 16) | TYPE_BINARY;
    private static final int TAG_REPLY_RECIPIENT_NAMES = (0x0050 << 16) | TYPE_UNICODE;
    private static final int TAG_ATTACH_DISPLAY_NAME = (0x3001 << 16) | TYPE_UNICODE;

    private static final int TAG_RECIPIENT_DISPLAY_NAME = (0x3001 << 16) | TYPE_UNICODE;
    private static final int TAG_RECIPIENT_EMAIL_ADDRESS = (0x3003 << 16) | TYPE_UNICODE;
    private static final int TAG_RECIPIENT_SMTP_ADDRESS = (0x39FE << 16) | TYPE_UNICODE;
    private static final int TAG_RECIPIENT_TYPE = (0x0C15 << 16) | TYPE_LONG;

    private static final int TAG_ATTACH_LONG_FILENAME = (0x3707 << 16) | TYPE_UNICODE;
    private static final int TAG_ATTACH_MIME_TAG = (0x370E << 16) | TYPE_UNICODE;
    private static final int TAG_ATTACH_DATA_BINARY = (0x3701 << 16) | TYPE_BINARY;
    private static final int TAG_ATTACH_METHOD = (0x3705 << 16) | TYPE_LONG;
    private static final int TAG_ATTACH_CONTENT_ID = (0x3712 << 16) | TYPE_UNICODE;

    private static final int ATTACH_METHOD_BY_VALUE = 1;
    private static final int ATTACH_METHOD_EMBEDDED_MESSAGE = 5;
    private static final int ATTACH_METHOD_OLE = 6;

    private final List<VarProperty> varProperties = new ArrayList<>();
    private final List<FixedProperty> fixedProperties = new ArrayList<>();
    private final List<MsgFixtureBuilder> recipientsTo = new ArrayList<>();
    private final List<MsgFixtureBuilder> recipientsCc = new ArrayList<>();
    private final List<MsgFixtureBuilder> recipientsBcc = new ArrayList<>();
    private final List<AttachmentSpec> attachments = new ArrayList<>();

    private MsgFixtureBuilder() {}

    static MsgFixtureBuilder topLevel() {
        return new MsgFixtureBuilder();
    }

    MsgFixtureBuilder subject(String value) {
        return setUnicode(TAG_SUBJECT, value);
    }

    MsgFixtureBuilder textBody(String value) {
        return setUnicode(TAG_BODY, value);
    }

    MsgFixtureBuilder htmlBody(String value) {
        return setUnicode(TAG_BODY_HTML_UNICODE, value);
    }

    MsgFixtureBuilder sender(String name, String email) {
        if (name != null) {
            setUnicode(TAG_SENDER_NAME, name);
        }
        if (email != null) {
            setUnicode(TAG_SENDER_EMAIL_ADDRESS, email);
        }
        return this;
    }

    MsgFixtureBuilder recipientTo(String name, String email) {
        recipientsTo.add(buildRecipient(name, email, RECIPIENT_TYPE_TO));
        return this;
    }

    /** A recipient that carries no PR_SMTP_ADDRESS chunk — only the raw email-address chunk and address type. */
    MsgFixtureBuilder recipientToWithoutSmtp(String name, String email, String addrType) {
        var recipient = new MsgFixtureBuilder();
        if (name != null) {
            recipient.setUnicode(TAG_RECIPIENT_DISPLAY_NAME, name);
        }
        if (email != null) {
            recipient.setUnicode(TAG_RECIPIENT_EMAIL_ADDRESS, email);
        }
        if (addrType != null) {
            recipient.setUnicode(TAG_RECIPIENT_ADDRTYPE, addrType);
        }
        recipient.fixedProperties.add(new FixedProperty(TAG_RECIPIENT_TYPE, longBytes(RECIPIENT_TYPE_TO)));
        recipientsTo.add(recipient);
        return this;
    }

    /** PidTagSenderAddressType (PR_SENDER_ADDRTYPE), e.g. {@code "EX"} for an Exchange X.500 sender. */
    MsgFixtureBuilder senderAddrType(String value) {
        return setUnicode(TAG_SENDER_ADDRTYPE, value);
    }

    /** PidTagSenderSmtpAddress (0x5D01) — the SMTP form Exchange stores beside an X.500 sender DN. */
    MsgFixtureBuilder senderSmtpAddress(String value) {
        return setUnicode(TAG_SENDER_SMTP_ADDRESS, value);
    }

    /** PidTagDisplayTo (PR_DISPLAY_TO) — the semicolon-joined display string used as a recipient fallback. */
    MsgFixtureBuilder displayTo(String value) {
        return setUnicode(TAG_DISPLAY_TO, value);
    }

    /** PidTagContentFilterSpamConfidenceLevel (0x4076). */
    MsgFixtureBuilder spamConfidenceLevel(int value) {
        fixedProperties.add(new FixedProperty(TAG_SPAM_CONFIDENCE_LEVEL, longBytes(value)));
        return this;
    }

    /** PR_RTF_COMPRESSED carrying the given RTF wrapped in a valid uncompressed ("MELA") LZFu envelope. */
    MsgFixtureBuilder rtfBody(String rtf) {
        return setBinary(TAG_RTF_COMPRESSED, wrapUncompressedRtf(rtf.getBytes(StandardCharsets.US_ASCII)));
    }

    /** PR_RTF_COMPRESSED whose LZFu envelope has a bogus compression signature — POI fails decompression. */
    MsgFixtureBuilder corruptRtfBody() {
        var envelope = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        envelope.putInt(16);
        envelope.putInt(4);
        envelope.putInt(0x0BAD0BAD); // neither "LZFu" nor "MELA"
        envelope.putInt(0);
        envelope.putInt(0x52545B7B);
        return setBinary(TAG_RTF_COMPRESSED, envelope.array());
    }

    private static byte[] wrapUncompressedRtf(byte[] rtfBytes) {
        var envelope = ByteBuffer.allocate(16 + rtfBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        envelope.putInt(rtfBytes.length + 12); // COMPSIZE: bytes that follow this field
        envelope.putInt(rtfBytes.length); // RAWSIZE
        envelope.put((byte) 'M').put((byte) 'E').put((byte) 'L').put((byte) 'A'); // uncompressed magic
        envelope.putInt(0); // CRC is ignored for uncompressed payloads
        envelope.put(rtfBytes);
        return envelope.array();
    }

    MsgFixtureBuilder recipientCc(String name, String email) {
        recipientsCc.add(buildRecipient(name, email, RECIPIENT_TYPE_CC));
        return this;
    }

    MsgFixtureBuilder recipientBcc(String name, String email) {
        recipientsBcc.add(buildRecipient(name, email, RECIPIENT_TYPE_BCC));
        return this;
    }

    /** PR_SENT_REPRESENTING_NAME + PidTagSentRepresentingSmtpAddress (0x5D02) — the author identity. */
    MsgFixtureBuilder sentRepresenting(String name, String smtpAddress) {
        if (name != null) {
            setUnicode(TAG_SENT_REPRESENTING_NAME, name);
        }
        if (smtpAddress != null) {
            setUnicode(TAG_SENT_REPRESENTING_SMTP_ADDRESS, smtpAddress);
        }
        return this;
    }

    /** PR_IMPORTANCE: 0 = low, 1 = normal, 2 = high. */
    MsgFixtureBuilder importance(int value) {
        fixedProperties.add(new FixedProperty(TAG_IMPORTANCE, longBytes(value)));
        return this;
    }

    /** PR_SENSITIVITY: 0 = none, 1 = personal, 2 = private, 3 = company-confidential. */
    MsgFixtureBuilder sensitivity(int value) {
        fixedProperties.add(new FixedProperty(TAG_SENSITIVITY, longBytes(value)));
        return this;
    }

    /** PR_CONVERSATION_TOPIC. */
    MsgFixtureBuilder conversationTopic(String value) {
        return setUnicode(TAG_CONVERSATION_TOPIC, value);
    }

    /** PR_CONVERSATION_INDEX. */
    MsgFixtureBuilder conversationIndex(byte[] value) {
        return setBinary(TAG_CONVERSATION_INDEX, value);
    }

    /** PR_READ_RECEIPT_REQUESTED = true. */
    MsgFixtureBuilder readReceiptRequested() {
        fixedProperties.add(new FixedProperty(TAG_READ_RECEIPT_REQUESTED, longBytes(1)));
        return this;
    }

    /**
     * Adds one Reply-To recipient: PR_REPLY_RECIPIENT_ENTRIES carries a one-entry [MS-OXCDATA]
     * §2.3.3 FLATENTRYLIST with a Unicode one-off ENTRYID, PR_REPLY_RECIPIENT_NAMES the display name.
     */
    MsgFixtureBuilder replyTo(String name, String email) {
        var entryId = oneOffEntryId(name, email);
        var padding = (4 - ((4 + entryId.length) % 4)) % 4;
        var list = ByteBuffer.allocate(8 + 4 + entryId.length + padding).order(ByteOrder.LITTLE_ENDIAN);
        list.putInt(1); // cEntries
        list.putInt(4 + entryId.length + padding); // cbEntries
        list.putInt(entryId.length);
        list.put(entryId);
        setBinary(TAG_REPLY_RECIPIENT_ENTRIES, list.array());
        return setUnicode(TAG_REPLY_RECIPIENT_NAMES, name);
    }

    /** A Unicode one-off ENTRYID ([MS-OXCDATA] §2.2.5.1): flags + provider UID + version/flags + strings. */
    private static byte[] oneOffEntryId(String name, String email) {
        var strings = (name + "\0SMTP\0" + email + "\0").getBytes(StandardCharsets.UTF_16LE);
        var entry = ByteBuffer.allocate(24 + strings.length).order(ByteOrder.LITTLE_ENDIAN);
        entry.putInt(0); // abFlags
        entry.put(new byte[] {
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
        }); // one-off provider UID
        entry.putShort((short) 0); // version
        entry.putShort((short) 0x8000); // flags: Unicode strings
        entry.put(strings);
        return entry.array();
    }

    MsgFixtureBuilder messageDate(Date date) {
        fixedProperties.add(new FixedProperty(TAG_MESSAGE_DELIVERY_TIME, fileTime(date)));
        return this;
    }

    MsgFixtureBuilder messageId(String value) {
        return setUnicode(TAG_INTERNET_MESSAGE_ID, value);
    }

    MsgFixtureBuilder inReplyTo(String value) {
        return setUnicode(TAG_IN_REPLY_TO_ID, value);
    }

    MsgFixtureBuilder references(String value) {
        return setUnicode(TAG_INTERNET_REFERENCES, value);
    }

    MsgFixtureBuilder messageClass(String value) {
        return setUnicode(TAG_MESSAGE_CLASS, value);
    }

    MsgFixtureBuilder transportHeaders(String value) {
        return setUnicode(TAG_TRANSPORT_HEADERS, value);
    }

    /** PidTagReportText (0x1001) — the human-readable explanation text in an NDR or read receipt. */
    MsgFixtureBuilder reportText(String value) {
        return setUnicode(TAG_REPORT_TEXT, value);
    }

    MsgFixtureBuilder attachment(String filename, String mime, byte[] data) {
        attachments.add(new AttachmentSpec(filename, mime, data, null, null, null, null));
        return this;
    }

    MsgFixtureBuilder attachment(String filename, String mime, byte[] data, String contentId) {
        attachments.add(new AttachmentSpec(filename, mime, data, null, contentId, null, null));
        return this;
    }

    MsgFixtureBuilder embeddedAttachment(String filename, MsgFixtureBuilder embedded) {
        attachments.add(new AttachmentSpec(filename, null, null, embedded, null, null, null));
        return this;
    }

    /**
     * An ATTACH_OLE (PR_ATTACH_METHOD 6) attachment: a sub-storage holding an OLE object's CONTENTS
     * stream — the storage shape POI's {@code isEmbeddedMessage()} cannot tell apart from a real
     * embedded message.
     */
    MsgFixtureBuilder oleAttachment(String displayName, byte[] contents) {
        attachments.add(new AttachmentSpec(null, null, null, null, null, displayName, contents));
        return this;
    }

    byte[] toBytes() throws IOException {
        try (var fs = new POIFSFileSystem();
                var output = new ByteArrayOutputStream()) {
            populateMessage(fs.getRoot(), 32);
            fs.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private MsgFixtureBuilder setUnicode(int tag, String value) {
        varProperties.removeIf(prop -> prop.tag == tag);
        varProperties.add(new VarProperty(tag, encodeUtf16(value)));
        return this;
    }

    private MsgFixtureBuilder setBinary(int tag, byte[] data) {
        varProperties.removeIf(prop -> prop.tag == tag);
        varProperties.add(new VarProperty(tag, data));
        return this;
    }

    private MsgFixtureBuilder buildRecipient(String name, String email, int type) {
        var recipient = new MsgFixtureBuilder();
        if (name != null) {
            recipient.setUnicode(TAG_RECIPIENT_DISPLAY_NAME, name);
        }
        if (email != null) {
            recipient.setUnicode(TAG_RECIPIENT_EMAIL_ADDRESS, email);
            recipient.setUnicode(TAG_RECIPIENT_SMTP_ADDRESS, email);
        }
        recipient.fixedProperties.add(new FixedProperty(TAG_RECIPIENT_TYPE, longBytes(type)));
        return recipient;
    }

    private void populateMessage(DirectoryEntry root, int headerSize) throws IOException {
        var allRecipients =
                new ArrayList<MsgFixtureBuilder>(recipientsTo.size() + recipientsCc.size() + recipientsBcc.size());
        allRecipients.addAll(recipientsTo);
        allRecipients.addAll(recipientsCc);
        allRecipients.addAll(recipientsBcc);

        var stream = new ByteArrayOutputStream();
        var header = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN);
        if (headerSize >= 24) {
            header.position(8);
            header.putInt(allRecipients.size() + 1);
            header.putInt(attachments.size() + 1);
            header.putInt(allRecipients.size());
            header.putInt(attachments.size());
        }
        stream.write(header.array());

        for (var fixed : fixedProperties) {
            writeFixedEntry(stream, fixed);
        }
        for (var varProp : varProperties) {
            writeVarEntry(stream, varProp);
        }
        root.createDocument("__properties_version1.0", new ByteArrayInputStream(stream.toByteArray()));

        for (var varProp : varProperties) {
            root.createDocument(substgName(varProp.tag), new ByteArrayInputStream(varProp.data));
        }

        for (var index = 0; index < allRecipients.size(); index++) {
            var directory = root.createDirectory(String.format("__recip_version1.0_#%08X", index));
            allRecipients.get(index).populateMessage(directory, 8);
        }

        for (var index = 0; index < attachments.size(); index++) {
            var directory = root.createDirectory(String.format("__attach_version1.0_#%08X", index));
            attachments.get(index).populate(directory);
        }
    }

    private static void writeFixedEntry(ByteArrayOutputStream stream, FixedProperty property) throws IOException {
        var entry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        entry.putInt(property.tag);
        entry.putInt(FLAG_READABLE_WRITABLE);
        entry.put(property.data);
        if (property.data.length < 8) {
            entry.position(8 + property.data.length);
            for (var pad = property.data.length; pad < 8; pad++) {
                entry.put((byte) 0);
            }
        }
        stream.write(entry.array());
    }

    private static void writeVarEntry(ByteArrayOutputStream stream, VarProperty property) throws IOException {
        var entry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        entry.putInt(property.tag);
        entry.putInt(FLAG_READABLE_WRITABLE);
        entry.putInt(property.data.length);
        entry.putInt(0);
        stream.write(entry.array());
    }

    private static String substgName(int tag) {
        return String.format("__substg1.0_%08X", tag);
    }

    private static byte[] encodeUtf16(String value) {
        var safe = value == null ? "" : value;
        return safe.getBytes(StandardCharsets.UTF_16LE);
    }

    private static byte[] longBytes(int value) {
        return ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array();
    }

    private static byte[] fileTime(Date date) {
        var fileTime = (date.getTime() + 11644473600000L) * 10000L;
        return ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(fileTime)
                .array();
    }

    private record VarProperty(int tag, byte[] data) {}

    private record FixedProperty(int tag, byte[] data) {}

    private record AttachmentSpec(
            String filename,
            String mime,
            byte[] data,
            MsgFixtureBuilder embedded,
            String contentId,
            String displayName,
            byte[] oleContents) {

        void populate(DirectoryEntry directory) throws IOException {
            var stream = new ByteArrayOutputStream();
            var header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            stream.write(header.array());

            var method = embedded != null
                    ? ATTACH_METHOD_EMBEDDED_MESSAGE
                    : oleContents != null ? ATTACH_METHOD_OLE : ATTACH_METHOD_BY_VALUE;
            writeFixedEntry(stream, new FixedProperty(TAG_ATTACH_METHOD, longBytes(method)));

            var varProps = new ArrayList<VarProperty>();
            if (filename != null) {
                varProps.add(new VarProperty(TAG_ATTACH_LONG_FILENAME, encodeUtf16(filename)));
            }
            if (displayName != null) {
                varProps.add(new VarProperty(TAG_ATTACH_DISPLAY_NAME, encodeUtf16(displayName)));
            }
            if (mime != null) {
                varProps.add(new VarProperty(TAG_ATTACH_MIME_TAG, encodeUtf16(mime)));
            }
            if (contentId != null) {
                varProps.add(new VarProperty(TAG_ATTACH_CONTENT_ID, encodeUtf16(contentId)));
            }
            if (data != null) {
                varProps.add(new VarProperty(TAG_ATTACH_DATA_BINARY, data));
            }
            for (var prop : varProps) {
                writeVarEntry(stream, prop);
            }

            directory.createDocument("__properties_version1.0", new ByteArrayInputStream(stream.toByteArray()));

            for (var prop : varProps) {
                directory.createDocument(substgName(prop.tag), new ByteArrayInputStream(prop.data));
            }

            if (embedded != null) {
                var embeddedDir = directory.createDirectory("__substg1.0_3701000D");
                embedded.populateMessage(embeddedDir, 24);
            } else if (oleContents != null) {
                var oleDir = directory.createDirectory("__substg1.0_3701000D");
                oleDir.createDocument("CONTENTS", new ByteArrayInputStream(oleContents));
            }
        }
    }
}
