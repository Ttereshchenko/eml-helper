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

    private static final int FLAG_READABLE_WRITABLE = 0x06;
    private static final int TYPE_UNICODE = 0x001F;
    private static final int TYPE_BINARY = 0x0102;
    private static final int TYPE_LONG = 0x0003;
    private static final int TYPE_SYSTIME = 0x0040;

    private static final int TAG_SUBJECT = (0x0037 << 16) | TYPE_UNICODE;
    private static final int TAG_BODY = (0x1000 << 16) | TYPE_UNICODE;
    private static final int TAG_BODY_HTML_UNICODE = (0x1013 << 16) | TYPE_UNICODE;
    private static final int TAG_SENDER_NAME = (0x0C1A << 16) | TYPE_UNICODE;
    private static final int TAG_SENDER_EMAIL_ADDRESS = (0x0C1F << 16) | TYPE_UNICODE;
    private static final int TAG_INTERNET_MESSAGE_ID = (0x1035 << 16) | TYPE_UNICODE;
    private static final int TAG_MESSAGE_CLASS = (0x001A << 16) | TYPE_UNICODE;
    private static final int TAG_TRANSPORT_HEADERS = (0x007D << 16) | TYPE_UNICODE;
    private static final int TAG_MESSAGE_DELIVERY_TIME = (0x0E06 << 16) | TYPE_SYSTIME;

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

    private final List<VarProperty> varProperties = new ArrayList<>();
    private final List<FixedProperty> fixedProperties = new ArrayList<>();
    private final List<MsgFixtureBuilder> recipientsTo = new ArrayList<>();
    private final List<MsgFixtureBuilder> recipientsCc = new ArrayList<>();
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

    MsgFixtureBuilder recipientCc(String name, String email) {
        recipientsCc.add(buildRecipient(name, email, RECIPIENT_TYPE_CC));
        return this;
    }

    MsgFixtureBuilder messageDate(Date date) {
        fixedProperties.add(new FixedProperty(TAG_MESSAGE_DELIVERY_TIME, fileTime(date)));
        return this;
    }

    MsgFixtureBuilder messageId(String value) {
        return setUnicode(TAG_INTERNET_MESSAGE_ID, value);
    }

    MsgFixtureBuilder messageClass(String value) {
        return setUnicode(TAG_MESSAGE_CLASS, value);
    }

    MsgFixtureBuilder transportHeaders(String value) {
        return setUnicode(TAG_TRANSPORT_HEADERS, value);
    }

    MsgFixtureBuilder attachment(String filename, String mime, byte[] data) {
        attachments.add(new AttachmentSpec(filename, mime, data, null, null));
        return this;
    }

    MsgFixtureBuilder attachment(String filename, String mime, byte[] data, String contentId) {
        attachments.add(new AttachmentSpec(filename, mime, data, null, contentId));
        return this;
    }

    MsgFixtureBuilder embeddedAttachment(String filename, MsgFixtureBuilder embedded) {
        attachments.add(new AttachmentSpec(filename, null, null, embedded, null));
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
        var allRecipients = new ArrayList<MsgFixtureBuilder>(recipientsTo.size() + recipientsCc.size());
        allRecipients.addAll(recipientsTo);
        allRecipients.addAll(recipientsCc);

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
            String filename, String mime, byte[] data, MsgFixtureBuilder embedded, String contentId) {

        void populate(DirectoryEntry directory) throws IOException {
            var stream = new ByteArrayOutputStream();
            var header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            stream.write(header.array());

            var method = embedded == null ? ATTACH_METHOD_BY_VALUE : ATTACH_METHOD_EMBEDDED_MESSAGE;
            writeFixedEntry(stream, new FixedProperty(TAG_ATTACH_METHOD, longBytes(method)));

            var varProps = new ArrayList<VarProperty>();
            if (filename != null) {
                varProps.add(new VarProperty(TAG_ATTACH_LONG_FILENAME, encodeUtf16(filename)));
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
            }
        }
    }
}
