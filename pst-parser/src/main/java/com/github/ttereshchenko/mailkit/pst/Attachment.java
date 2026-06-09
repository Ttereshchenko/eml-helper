package com.github.ttereshchenko.mailkit.pst;

/**
 * An attachment of a {@link Message}, backed by its sub-node {@link PropertyContext}.
 *
 * <p>Exposes the attachment filename, MIME type, embedded-message flag and decoded binary content.
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

    public String getLongFilename() {
        Object obj = propertyContext.getProperty(MapiProperties.PR_ATTACH_LONG_FILENAME_W);
        return obj instanceof String ? (String) obj : "";
    }

    public String getFilename() {
        Object obj = propertyContext.getProperty(MapiProperties.PR_ATTACH_FILENAME_W);
        return obj instanceof String ? (String) obj : "";
    }

    public String getMimeTag() {
        Object obj = propertyContext.getProperty(MapiProperties.PR_ATTACH_MIME_TAG_W);
        return obj instanceof String ? (String) obj : "";
    }

    public byte[] getData() {
        Object obj = propertyContext.getProperty(MapiProperties.PR_ATTACH_DATA_BIN);
        return obj instanceof byte[] ? (byte[]) obj : null;
    }

    public Integer getEmbeddedMessageNodeId() {
        Object obj = propertyContext.getProperty(
                MapiProperties.PR_ATTACH_DATA_BIN); // PR_ATTACH_DATA_OBJ has the same ID 0x3701
        return obj instanceof Integer ? (Integer) obj : null;
    }

    public int getAttachMethod() {
        Object obj = propertyContext.getProperty(MapiProperties.PR_ATTACH_METHOD);
        return obj instanceof Integer ? (Integer) obj : 0;
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

    public String getContentId() {
        Object obj = propertyContext.getProperty(MapiProperties.PR_ATTACH_CONTENT_ID_W);
        return obj instanceof String ? (String) obj : null;
    }

    public boolean isInline() {
        Object obj = propertyContext.getProperty(MapiProperties.PR_ATTACH_DISPOSITION);
        if (obj instanceof String) {
            return "inline".equalsIgnoreCase((String) obj);
        }
        Object flags = propertyContext.getProperty(MapiProperties.PR_ATTACH_FLAGS);
        if (flags instanceof Integer) {
            int flagsInt = (Integer) flags;
            // attRenderedInBody (0x4) or ATT_INVISIBLE_IN_HTML (0x1) or ATT_HIDDEN (0x7FFE)
            return (flagsInt & 0x00000004) != 0 || (flagsInt & 0x00000001) != 0 || flagsInt == 0x7FFE;
        }
        return false;
    }
}
