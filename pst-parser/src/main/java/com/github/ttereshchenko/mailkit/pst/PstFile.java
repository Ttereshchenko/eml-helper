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
 * <p>Opening a {@code PstFile} takes ownership of a read-only {@link java.nio.channels.FileChannel},
 * validates the header eagerly (magic number, format version, encryption type) and loads the Node
 * Database (NBT/BBT). Navigate the store by constructing a {@link Folder} from a node id — the root
 * folder is {@code 0x122} — and reading {@link Message}s from it.
 *
 * <p>This is an {@link AutoCloseable} resource: always use it in a try-with-resources block so the
 * underlying channel is released.
 *
 * <p><strong>Thread-safety:</strong> instances are <em>not</em> thread-safe. The store-wide
 * named-property map backing {@link #namedPropertyId} is built and cached lazily without
 * synchronization, so a single {@code PstFile} must be confined to one thread.
 */
public final class PstFile implements AutoCloseable {

    private static final int MAGIC_NUMBER = 0x4E444221; // "!BDN"
    private static final int HEADER_VERSION_OFFSET = 10;

    public enum Format {
        ANSI,
        UNICODE,
        UNICODE_2013;

        static Format fromVersion(short version) throws PstException {
            if (version >= 36) return UNICODE_2013;
            if (version >= 23) return UNICODE;
            if (version == 14 || version == 15) return ANSI;
            throw new PstException("Unrecognized PST file version: " + version);
        }
    }

    public enum EncryptionType {
        NONE,
        COMPRESSIBLE,
        HIGH;

        static EncryptionType fromType(byte type) {
            return switch (type) {
                case 0x01 -> COMPRESSIBLE;
                case 0x02 -> HIGH;
                default -> NONE;
            };
        }
    }

    private final FileChannel channel;
    private final Format format;
    private final EncryptionType encryptionType;
    private final NodeDatabase nodeDatabase;
    private final long maxNodeSize;
    // The store-wide named-property map (NBT node 0x61) is expensive to parse and identical for every
    // message, so build it lazily once per file rather than per appointment. A single conversion runs
    // single-threaded on one background task, so plain lazy init is sufficient.
    private NameToIdMap nameToIdMap;

    public PstFile(Path path) throws IOException, PstException {
        this(path, 64L * 1024 * 1024);
    }

    public PstFile(Path path, long maxNodeSize) throws IOException, PstException {
        Objects.requireNonNull(path, "path");
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
        this.maxNodeSize = maxNodeSize;

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
                        channel, this.format, this.encryptionType, bbtOffset, nbtOffset, this.maxNodeSize);
            } else {
                var brefBuffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
                readFully(channel, brefBuffer, 188);
                long nbtOffset = Integer.toUnsignedLong(brefBuffer.getInt(0));
                long bbtOffset = Integer.toUnsignedLong(brefBuffer.getInt(8));
                this.nodeDatabase = new NodeDatabase(
                        channel, this.format, this.encryptionType, bbtOffset, nbtOffset, this.maxNodeSize);
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
    NameToIdMap nameToIdMap() {
        if (nameToIdMap == null) {
            nameToIdMap = new NameToIdMap(nodeDatabase);
        }
        return nameToIdMap;
    }

    /**
     * The node with the given node id, or {@code null} if the store has no such node (or failed to
     * load its node database). Low-level access for callers that must resolve nodes the high-level
     * {@link Folder}/{@link Message} API does not surface, such as the root folder node or
     * orphan-recovery scans.
     */
    public NodeEntry getNode(int nodeId) {
        return nodeDatabase == null ? null : nodeDatabase.getNode(nodeId);
    }

    /**
     * An unmodifiable view of every node in the store's node b-tree (NBT), keyed by node id; empty if
     * the store failed to load its node database. Intended for full-store scans, e.g. recovering
     * messages that no folder references.
     */
    public Map<Integer, NodeEntry> allNodes() {
        return nodeDatabase == null ? Map.of() : nodeDatabase.getAllNodes();
    }

    /**
     * Resolves a sub-node entry (for example an embedded message) within the given parent sub-node
     * b-tree, or {@code null} if it cannot be found.
     *
     * @throws IOException if the underlying store cannot be read
     */
    public NodeEntry readSubnodeEntry(long subnodeBid, int targetNodeId) throws IOException {
        return nodeDatabase == null ? null : nodeDatabase.readSubnodeEntry(subnodeBid, targetNodeId);
    }

    /**
     * The property id this store assigned to a named property, identified by its property-set GUID and
     * numeric id, or {@code null} if the store defines no such named property.
     */
    public Integer namedPropertyId(UUID propertySetGuid, int propertyId) {
        return nameToIdMap().getId(propertySetGuid, propertyId);
    }

    public Format format() {
        return format;
    }

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
