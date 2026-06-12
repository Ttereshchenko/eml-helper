package com.github.ttereshchenko.mailkit.pst;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Entry point for reading an Outlook PST/OST personal-folders file ([MS-PST]).
 *
 * <p>Opening a {@code PstFile} takes ownership of a read-only {@link java.nio.channels.FileChannel}
 * and validates the header eagerly (magic number, format version, encryption type, b-tree roots).
 * Node and block lookups then descend the on-disk b-trees lazily, so opening a large store does not
 * materialize its index. Navigate the store by constructing a {@link Folder} from a node id — the
 * root folder is {@code 0x122} — and reading {@link Message}s from it.
 *
 * <p>This is an {@link AutoCloseable} resource: always use it in a try-with-resources block so the
 * underlying channel is released.
 *
 * <p><strong>Outlook "password protection"</strong> is only a CRC of the password stored in the
 * message store ({@link #isPasswordProtected()}); the content is not actually encrypted with it, so
 * password-protected stores are read normally.
 *
 * <p><strong>Thread-safety:</strong> a {@code PstFile} is safe for concurrent use by multiple
 * threads — its lazily built caches are synchronized and the underlying channel uses positional
 * reads. {@link Folder}, {@link Message} and {@link Attachment} instances are <em>not</em>
 * thread-safe; confine each instance to a single thread.
 */
public final class PstFile implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PstFile.class.getName());

    private static final int MAGIC_NUMBER = 0x4E444221; // "!BDN"
    private static final int HEADER_VERSION_OFFSET = 10;
    private static final int NID_MESSAGE_STORE = 0x21;

    /** Default cap on the expanded size of a single node's data tree (64 MiB). */
    public static final long DEFAULT_MAX_NODE_SIZE = 64L * 1024 * 1024;

    /** The lowest accepted {@code maxNodeSize}: one full NDB block. */
    public static final long MIN_MAX_NODE_SIZE = 64L * 1024;

    /** The store's wire format, derived from the header version ([MS-PST] §2.2.2.6). */
    public enum Format {
        /** Legacy 32-bit format (Outlook ≤ 2002), 512-byte pages, 2 GB limit. */
        ANSI,
        /** 64-bit format (Outlook 2003+), 512-byte pages. */
        UNICODE,
        /** 64-bit format with 4 KiB pages and optional per-block compression (Outlook 2013+ OST). */
        UNICODE_2013;

        static Format fromVersion(short version) throws PstException {
            if (version >= 36) return UNICODE_2013;
            if (version >= 23) return UNICODE;
            if (version == 14 || version == 15) return ANSI;
            throw new PstException("Unrecognized PST file version: " + version);
        }
    }

    /** The store's block obfuscation scheme ({@code bCryptMethod}, [MS-PST] §2.2.2.6). */
    public enum EncryptionType {
        NONE,
        COMPRESSIBLE,
        HIGH;

        static EncryptionType fromType(byte type) throws PstException {
            return switch (type) {
                case 0x00 -> NONE;
                case 0x01 -> COMPRESSIBLE;
                case 0x02 -> HIGH;
                // A value outside the spec means a corrupted header; decoding blocks with the
                // wrong scheme would silently produce garbage, so fail at open instead.
                default -> throw new PstException("Unrecognized encryption type: " + type);
            };
        }
    }

    private final FileChannel channel;
    private final Format format;
    private final EncryptionType encryptionType;
    private final NodeDatabase nodeDatabase;
    // The store-wide named-property map (NBT node 0x61) is expensive to parse and identical for every
    // message, so build it lazily once per file; the lock makes the lazy init safe across threads.
    private NameToIdMap nameToIdMap;
    // Lazily read store-wide default code page (message store object); resolved at most once.
    private boolean storeCodePageResolved;
    private Integer storeCodePage;

    /**
     * Opens the PST/OST file at the given path with the {@linkplain #DEFAULT_MAX_NODE_SIZE default}
     * node-size cap.
     *
     * @throws PstException if the file is not a recognizable PST/OST store
     * @throws IOException if the file cannot be read
     */
    public PstFile(Path path) throws IOException, PstException {
        this(path, DEFAULT_MAX_NODE_SIZE);
    }

    /**
     * Opens the PST/OST file at the given path.
     *
     * @param maxNodeSize cap, in bytes, on the expanded data of a single node (and therefore on a
     *     single attachment payload, message body or decompressed block). Reads of nodes that expand
     *     beyond the cap fail with a {@link PstException}; raise it to extract attachments larger
     *     than the {@linkplain #DEFAULT_MAX_NODE_SIZE default}. Must be at least
     *     {@link #MIN_MAX_NODE_SIZE}.
     * @throws IllegalArgumentException if {@code maxNodeSize} is below {@link #MIN_MAX_NODE_SIZE}
     * @throws PstException if the file is not a recognizable PST/OST store
     * @throws IOException if the file cannot be read
     */
    public PstFile(Path path, long maxNodeSize) throws IOException, PstException {
        this(path, maxNodeSize, false);
    }

    /**
     * Opens the PST/OST file at the given path, optionally verifying on-disk checksums.
     *
     * @param verifyCrc when {@code true}, every b-tree page and block read is checked against its
     *     trailer CRC ([MS-PST] §5.3) and a mismatch fails with a {@link PstException} — turning
     *     otherwise-silent bit rot into a detected error. Costs one extra trailer read per block.
     *     Supported for ANSI and Unicode stores; 2013-format (4 KiB page) stores skip verification.
     * @throws IllegalArgumentException if {@code maxNodeSize} is below {@link #MIN_MAX_NODE_SIZE}
     * @throws PstException if the file is not a recognizable PST/OST store
     * @throws IOException if the file cannot be read
     */
    public PstFile(Path path, long maxNodeSize, boolean verifyCrc) throws IOException, PstException {
        Objects.requireNonNull(path, "path");
        if (maxNodeSize < MIN_MAX_NODE_SIZE) {
            throw new IllegalArgumentException(
                    "maxNodeSize must be at least " + MIN_MAX_NODE_SIZE + " bytes, got " + maxNodeSize);
        }
        this.channel = FileChannel.open(path, StandardOpenOption.READ);

        try {
            var magicBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, magicBuffer, 0);

            if (magicBuffer.getInt(0) != MAGIC_NUMBER) {
                throw new PstException("Invalid file header: magic number mismatch");
            }

            var versionBuffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, versionBuffer, HEADER_VERSION_OFFSET);
            this.format = Format.fromVersion(versionBuffer.getShort(0));

            var encryptionOffset = (this.format == Format.ANSI) ? 461 : 513;
            var encryptionBuffer = ByteBuffer.allocate(1);
            readFully(channel, encryptionBuffer, encryptionOffset);
            this.encryptionType = EncryptionType.fromType(encryptionBuffer.get(0));

            if (this.format == Format.ANSI && this.channel.size() > 2L * 1024 * 1024 * 1024) {
                throw new PstException("ANSI PST file exceeds 2GB limit");
            }

            if (this.format != Format.ANSI) {
                var brefBuffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, brefBuffer, 224);
                long nbtOffset = brefBuffer.getLong(0);
                long bbtOffset = brefBuffer.getLong(16);
                this.nodeDatabase = new NodeDatabase(
                        channel, this.format, this.encryptionType, bbtOffset, nbtOffset, maxNodeSize, verifyCrc);
            } else {
                var brefBuffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, brefBuffer, 188);
                long nbtOffset = Integer.toUnsignedLong(brefBuffer.getInt(0));
                long bbtOffset = Integer.toUnsignedLong(brefBuffer.getInt(8));
                this.nodeDatabase = new NodeDatabase(
                        channel, this.format, this.encryptionType, bbtOffset, nbtOffset, maxNodeSize, verifyCrc);
            }
        } catch (Exception failure) {
            this.channel.close();
            throw failure;
        }
    }

    NodeDatabase nodeDatabase() {
        return nodeDatabase;
    }

    /** The store-wide named-property map, parsed once and cached for the life of this file. */
    synchronized NameToIdMap nameToIdMap() {
        if (nameToIdMap == null) {
            nameToIdMap = new NameToIdMap(nodeDatabase);
        }
        return nameToIdMap;
    }

    /**
     * The node with the given node id, or {@code null} if the store has no such node. Low-level
     * access for callers that must resolve nodes the high-level {@link Folder}/{@link Message} API
     * does not surface, such as the root folder node or orphan-recovery scans.
     *
     * @throws IOException if the node b-tree cannot be read or is malformed
     */
    public NodeEntry getNode(int nodeId) throws IOException {
        return nodeDatabase.getNode(nodeId);
    }

    /**
     * An unmodifiable snapshot of every node in the store's node b-tree (NBT), keyed by node id.
     * Built by a full b-tree walk on first use and cached; intended for full-store scans, e.g.
     * recovering messages that no folder references.
     *
     * @throws IOException if the node b-tree cannot be walked
     */
    public Map<Integer, NodeEntry> allNodes() throws IOException {
        return nodeDatabase.getAllNodes();
    }

    /**
     * Resolves a sub-node entry (for example an embedded message) within the given parent sub-node
     * b-tree, or {@code null} if it cannot be found.
     *
     * @throws IOException if the underlying store cannot be read
     */
    public NodeEntry readSubnodeEntry(long subnodeBid, int targetNodeId) throws IOException {
        return nodeDatabase.readSubnodeEntry(subnodeBid, targetNodeId);
    }

    /**
     * The property id this store assigned to a named property, identified by its property-set GUID and
     * numeric id, or {@code null} if the store defines no such named property.
     */
    public Integer namedPropertyId(UUID propertySetGuid, int propertyId) {
        return nameToIdMap().getId(propertySetGuid, propertyId);
    }

    /**
     * Whether the store carries an Outlook password (PidTagPstPassword). The "password" is only a
     * CRC kept in the message store object — the content is not encrypted with it — so this library
     * reads protected stores normally; the flag is surfaced for callers that want to warn.
     *
     * @throws IOException if the message store object cannot be read
     */
    public boolean isPasswordProtected() throws IOException {
        var storeNode = nodeDatabase.getNode(NID_MESSAGE_STORE);
        if (storeNode == null) {
            return false;
        }
        var propertyContext =
                new PropertyContext(nodeDatabase.readNodeData(storeNode.dataBid()), nodeDatabase, storeNode);
        return propertyContext.getProperty(MapiProperties.PR_PST_PASSWORD) instanceof Integer crc && crc != 0;
    }

    /**
     * The store's default code page id from the message store object (PidTagCodePageId, with
     * PR_MESSAGE_CODEPAGE as a fallback), or {@code null} when the store does not record one. Used
     * as the last-resort PT_STRING8 code page for messages that carry neither
     * {@code PR_MESSAGE_CODEPAGE} nor {@code PR_INTERNET_CPID} themselves.
     */
    public synchronized Integer storeCodePage() {
        if (!storeCodePageResolved) {
            storeCodePageResolved = true;
            try {
                var storeNode = nodeDatabase.getNode(NID_MESSAGE_STORE);
                if (storeNode != null) {
                    var propertyContext = new PropertyContext(
                            nodeDatabase.readNodeData(storeNode.dataBid()), nodeDatabase, storeNode);
                    Object value = propertyContext.getProperty(MapiProperties.PR_CODE_PAGE_ID);
                    if (value == null) {
                        value = propertyContext.getProperty(MapiProperties.PR_MESSAGE_CODEPAGE);
                    }
                    if (value instanceof Number codePage) {
                        storeCodePage = codePage.intValue();
                    }
                }
            } catch (IOException | RuntimeException exception) {
                LOG.log(System.Logger.Level.DEBUG, "Failed to read the message store object's code page", exception);
            }
        }
        return storeCodePage;
    }

    /** The store's wire format, derived from the header version. */
    public Format format() {
        return format;
    }

    /** The store's block obfuscation scheme. */
    public EncryptionType encryptionType() {
        return encryptionType;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        buffer.clear();
        while (buffer.hasRemaining()) {
            var read = channel.read(buffer, position + buffer.position());
            if (read == -1) {
                throw new PstException("Unexpected end of file while reading header");
            }
        }
    }
}
