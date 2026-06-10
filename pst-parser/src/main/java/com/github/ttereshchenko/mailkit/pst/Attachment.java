package com.github.ttereshchenko.mailkit.pst;

import java.io.IOException;
import java.io.InputStream;

/**
 * An attachment of a {@link Message}, backed by its sub-node Property Context.
 *
 * <p>Exposes the attachment filename, MIME type, embedded-message flag and binary content. The
 * content can be {@linkplain #getData() materialized} or {@linkplain #openDataStream() streamed};
 * prefer streaming for large attachments.
 *
 * <p>Instances are not thread-safe; confine each to a single thread.
 */
public class Attachment {
    private final PropertyContext propertyContext;

    Attachment(PropertyContext propertyContext) {
        this.propertyContext = propertyContext;
    }

    /**
     * Creates an attachment with no backing property context, for subclasses (e.g. test doubles) that
     * override the public accessors directly. Any accessor that reads the property context must be
     * overridden by such a subclass.
     */
    protected Attachment() {
        this.propertyContext = null;
    }

    /** The long filename (PR_ATTACH_LONG_FILENAME), or an empty string if absent. */
    public String getLongFilename() {
        return propertyContext.getProperty(MapiProperties.PR_ATTACH_LONG_FILENAME_W) instanceof String value
                ? value
                : "";
    }

    /** The 8.3 filename (PR_ATTACH_FILENAME), or an empty string if absent. */
    public String getFilename() {
        return propertyContext.getProperty(MapiProperties.PR_ATTACH_FILENAME_W) instanceof String value ? value : "";
    }

    /** The MIME type (PR_ATTACH_MIME_TAG), or an empty string if absent. */
    public String getMimeTag() {
        return propertyContext.getProperty(MapiProperties.PR_ATTACH_MIME_TAG_W) instanceof String value ? value : "";
    }

    /**
     * The attachment content fully materialized in memory, or {@code null} if absent. Reads larger
     * than the store's configured {@code maxNodeSize} fail; for large attachments prefer
     * {@link #openDataStream()}.
     */
    public byte[] getData() {
        return propertyContext.getProperty(MapiProperties.PR_ATTACH_DATA_BIN) instanceof byte[] value ? value : null;
    }

    /**
     * Opens a stream over the attachment content without materializing it, reading the underlying
     * store block by block; or {@code null} if the attachment has no binary content (e.g. an
     * embedded message). The stream is independent of this attachment and need not be exhausted.
     *
     * @throws IOException if the underlying store cannot be read
     */
    public InputStream openDataStream() throws IOException {
        return propertyContext == null ? null : propertyContext.openBinaryStream(MapiProperties.PR_ATTACH_DATA_BIN);
    }

    /**
     * The node id of the embedded message when this attachment embeds one (PR_ATTACH_DATA_OBJ —
     * same id as PR_ATTACH_DATA_BIN), or {@code null}. Resolve it against {@link #getNode()}'s
     * sub-node tree via {@link PstFile#readSubnodeEntry}.
     */
    public Integer getEmbeddedMessageNodeId() {
        Object value = propertyContext.getProperty(
                MapiProperties.PR_ATTACH_DATA_BIN); // PR_ATTACH_DATA_OBJ has the same ID 0x3701
        return value instanceof Integer nodeId ? nodeId : null;
    }

    /** The attach method (PR_ATTACH_METHOD), or {@code 0} if absent. */
    public int getAttachMethod() {
        return propertyContext.getProperty(MapiProperties.PR_ATTACH_METHOD) instanceof Integer value ? value : 0;
    }

    PropertyContext getPropertyContext() {
        return propertyContext;
    }

    /**
     * The MAPI node backing this attachment — for example to resolve the sub-node of an embedded
     * message — or {@code null} if this attachment has no backing property context.
     */
    public NodeEntry getNode() {
        return propertyContext == null ? null : propertyContext.getNode();
    }

    /** The Content-ID for inline (cid:) references (PR_ATTACH_CONTENT_ID), or {@code null}. */
    public String getContentId() {
        return propertyContext.getProperty(MapiProperties.PR_ATTACH_CONTENT_ID_W) instanceof String value
                ? value
                : null;
    }

    /** Whether the attachment is meant to render inline, per its disposition or attach flags. */
    public boolean isInline() {
        if (propertyContext.getProperty(MapiProperties.PR_ATTACH_DISPOSITION) instanceof String disposition) {
            return "inline".equalsIgnoreCase(disposition);
        }
        if (propertyContext.getProperty(MapiProperties.PR_ATTACH_FLAGS) instanceof Integer flags) {
            // attRenderedInBody (0x4) or ATT_INVISIBLE_IN_HTML (0x1) or ATT_HIDDEN (0x7FFE)
            return (flags & 0x00000004) != 0 || (flags & 0x00000001) != 0 || flags == 0x7FFE;
        }
        return false;
    }
}
