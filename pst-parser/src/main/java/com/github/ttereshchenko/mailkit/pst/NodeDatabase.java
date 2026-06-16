package com.github.ttereshchenko.mailkit.pst;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The Node Database (NDB) layer: resolves nodes and blocks through the store's NBT/BBT b-trees and
 * reads, decrypts and (for UNICODE_2013 stores) inflates block data ([MS-PST] §2.2).
 *
 * <p>B-tree lookups descend the on-disk pages lazily through a small LRU page cache instead of
 * materializing the whole index, so opening a multi-gigabyte store costs two page reads, not a full
 * index scan. {@link #getAllNodes()} walks the NBT once on first use and caches the result for
 * full-store scans (orphan recovery).
 *
 * <p>Thread-safety: instances are safe for concurrent use; the page cache and the all-nodes cache
 * are the only mutable state and both are synchronized, and {@link FileChannel} positional reads are
 * thread-safe.
 */
final class NodeDatabase {

    private static final System.Logger LOG = System.getLogger(NodeDatabase.class.getName());

    private static final int MAX_BTREE_DEPTH = 64;
    private static final int PAGE_CACHE_CAPACITY = 256;
    /** Masks the "internal" flag (bit 1) off a BID; BIDs are allocated in increments of 4. */
    private static final long BID_FLAG_MASK = ~0x02L;

    private final FileChannel channel;
    private final PstFile.Format format;
    private final PstFile.EncryptionType encryptionType;
    private final long bbtRootOffset;
    private final long nbtRootOffset;
    private final long maxNodeSize;
    private final long channelSize;
    private final boolean verifyCrc;

    /** LRU cache of raw b-tree pages keyed by file offset; guarded by itself. */
    private final Map<Long, byte[]> pageCache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > PAGE_CACHE_CAPACITY;
        }
    };

    /** Lazily built full NBT snapshot for {@link #getAllNodes()}; guarded by {@code this}. */
    private Map<Integer, NodeEntry> allNodesCache;

    NodeDatabase(
            FileChannel channel,
            PstFile.Format format,
            PstFile.EncryptionType encryptionType,
            long bbtRootOffset,
            long nbtRootOffset,
            long maxNodeSize)
            throws IOException {
        this(channel, format, encryptionType, bbtRootOffset, nbtRootOffset, maxNodeSize, false);
    }

    NodeDatabase(
            FileChannel channel,
            PstFile.Format format,
            PstFile.EncryptionType encryptionType,
            long bbtRootOffset,
            long nbtRootOffset,
            long maxNodeSize,
            boolean verifyCrc)
            throws IOException {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.format = Objects.requireNonNull(format, "format");
        this.encryptionType = Objects.requireNonNull(encryptionType, "encryptionType");
        this.bbtRootOffset = bbtRootOffset;
        this.nbtRootOffset = nbtRootOffset;
        this.maxNodeSize = maxNodeSize;
        // The 2013 (4 KiB page) trailer layout is not as well documented as ANSI/Unicode; CRC
        // verification is limited to the formats whose trailers were validated against real stores.
        this.verifyCrc = verifyCrc && format != PstFile.Format.UNICODE_2013;
        this.channelSize = channel.size();

        // Validate both root pages eagerly so a store with a corrupt or out-of-range b-tree root
        // fails at open time rather than on the first lookup.
        readBTreePage(bbtRootOffset, "BBT");
        readBTreePage(nbtRootOffset, "NBT");
    }

    /**
     * Resolves a block id against the BBT, or {@code null} if the store has no such block. The
     * internal flag (bit 1) is ignored on both sides of the comparison.
     *
     * @throws IOException if a b-tree page cannot be read or is malformed
     */
    BlockEntry getBlock(long bid) throws IOException {
        long target = bid & BID_FLAG_MASK;
        long offset = bbtRootOffset;
        for (int depth = 0; depth <= MAX_BTREE_DEPTH; depth++) {
            var page = readBTreePage(offset, "BBT");
            var buffer = ByteBuffer.wrap(page.bytes()).order(ByteOrder.LITTLE_ENDIAN);
            if (page.level() > 0) {
                long childOffset = -1;
                for (int i = 0; i < page.entryCount(); i++) {
                    int entryOffset = i * page.entrySize();
                    long key = (format != PstFile.Format.ANSI)
                            ? buffer.getLong(entryOffset)
                            : Integer.toUnsignedLong(buffer.getInt(entryOffset));
                    if (Long.compareUnsigned(key & BID_FLAG_MASK, target) <= 0) {
                        childOffset = (format != PstFile.Format.ANSI)
                                ? buffer.getLong(entryOffset + 16)
                                : Integer.toUnsignedLong(buffer.getInt(entryOffset + 8));
                    } else {
                        break;
                    }
                }
                if (childOffset == -1) {
                    return null;
                }
                offset = childOffset;
                continue;
            }
            for (int i = 0; i < page.entryCount(); i++) {
                int entryOffset = i * page.entrySize();
                long entryBid = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(entryOffset)
                        : Integer.toUnsignedLong(buffer.getInt(entryOffset));
                if ((entryBid & BID_FLAG_MASK) != target) {
                    continue;
                }
                long fileOffset = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(entryOffset + 8)
                        : Integer.toUnsignedLong(buffer.getInt(entryOffset + 4));
                int size =
                        Short.toUnsignedInt(buffer.getShort(entryOffset + ((format != PstFile.Format.ANSI) ? 16 : 8)));
                int refCount =
                        Short.toUnsignedInt(buffer.getShort(entryOffset + ((format != PstFile.Format.ANSI) ? 18 : 10)));
                int inflatedSize = (format == PstFile.Format.UNICODE_2013) ? buffer.getInt(entryOffset + 24) : 0;
                // The leaf BREF file offset is otherwise unchecked; validate it (a negative ANSI
                // offset would throw IllegalArgumentException at channel.read) before the block is
                // ever read. When CRC verification is on the BLOCKTRAILER in the 64-byte-aligned slot
                // is also read ([MS-PST] §2.2.2.8), so validate the whole slot then — failing fast here
                // with a clear message beats an opaque EOF inside verifyBlockCrc. When CRC is off only
                // the cb data bytes are read, so the bound stays at `size` to avoid rejecting a store
                // whose final block's slot padding is not present on disk.
                long requiredEnd = fileOffset + size;
                if (verifyCrc) {
                    int trailerLength = format == PstFile.Format.ANSI ? 12 : 16;
                    requiredEnd = fileOffset + (((long) size + trailerLength + 63) / 64) * 64L;
                }
                if (fileOffset < 0 || requiredEnd > channelSize) {
                    throw new PstException("Block BREF offset out of range: offset=" + fileOffset + " size=" + size);
                }
                return new BlockEntry(entryBid, fileOffset, size, refCount, inflatedSize);
            }
            return null;
        }
        throw new PstException("BBT depth limit exceeded while resolving block " + bid);
    }

    /**
     * Resolves a node id against the NBT, or {@code null} if the store has no such node.
     *
     * @throws IOException if a b-tree page cannot be read or is malformed
     */
    NodeEntry getNode(int nid) throws IOException {
        long target = Integer.toUnsignedLong(nid);
        long offset = nbtRootOffset;
        for (int depth = 0; depth <= MAX_BTREE_DEPTH; depth++) {
            var page = readBTreePage(offset, "NBT");
            var buffer = ByteBuffer.wrap(page.bytes()).order(ByteOrder.LITTLE_ENDIAN);
            if (page.level() > 0) {
                long childOffset = -1;
                for (int i = 0; i < page.entryCount(); i++) {
                    int entryOffset = i * page.entrySize();
                    long key = (format != PstFile.Format.ANSI)
                            ? buffer.getLong(entryOffset)
                            : Integer.toUnsignedLong(buffer.getInt(entryOffset));
                    if (Long.compareUnsigned(key & 0xFFFFFFFFL, target) <= 0) {
                        childOffset = (format != PstFile.Format.ANSI)
                                ? buffer.getLong(entryOffset + 16)
                                : Integer.toUnsignedLong(buffer.getInt(entryOffset + 8));
                    } else {
                        break;
                    }
                }
                if (childOffset == -1) {
                    return null;
                }
                offset = childOffset;
                continue;
            }
            for (int i = 0; i < page.entryCount(); i++) {
                int entryOffset = i * page.entrySize();
                var entry = readNbtLeafEntry(buffer, entryOffset);
                if (Integer.toUnsignedLong(entry.nodeId()) == target) {
                    return entry;
                }
            }
            return null;
        }
        throw new PstException("NBT depth limit exceeded while resolving node " + nid);
    }

    /**
     * An unmodifiable snapshot of every node in the NBT, keyed by node id. Built by a full b-tree
     * walk on first use and cached; intended for full-store scans such as orphan recovery.
     *
     * @throws IOException if the NBT cannot be walked
     */
    synchronized Map<Integer, NodeEntry> getAllNodes() throws IOException {
        if (allNodesCache == null) {
            var nodes = new HashMap<Integer, NodeEntry>();
            collectNodes(nbtRootOffset, 0, new HashSet<>(), nodes);
            allNodesCache = Collections.unmodifiableMap(nodes);
        }
        return allNodesCache;
    }

    private void collectNodes(long offset, int depth, Set<Long> visited, Map<Integer, NodeEntry> nodes)
            throws IOException {
        if (depth > MAX_BTREE_DEPTH) {
            throw new PstException("NBT depth limit exceeded");
        }
        if (!visited.add(offset)) {
            throw new PstException("Cyclic NBT reference detected at offset " + offset);
        }
        var page = readBTreePage(offset, "NBT");
        var buffer = ByteBuffer.wrap(page.bytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < page.entryCount(); i++) {
            int entryOffset = i * page.entrySize();
            if (page.level() > 0) {
                long childOffset = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(entryOffset + 16)
                        : Integer.toUnsignedLong(buffer.getInt(entryOffset + 8));
                collectNodes(childOffset, depth + 1, visited, nodes);
            } else {
                var entry = readNbtLeafEntry(buffer, entryOffset);
                nodes.put(entry.nodeId(), entry);
            }
        }
    }

    private NodeEntry readNbtLeafEntry(ByteBuffer buffer, int entryOffset) {
        long rawNid = (format != PstFile.Format.ANSI)
                ? buffer.getLong(entryOffset)
                : Integer.toUnsignedLong(buffer.getInt(entryOffset));
        int nid = (int) (rawNid & 0xFFFFFFFFL);
        long dataBid = (format != PstFile.Format.ANSI)
                ? buffer.getLong(entryOffset + 8)
                : Integer.toUnsignedLong(buffer.getInt(entryOffset + 4));
        long subBid = (format != PstFile.Format.ANSI)
                ? buffer.getLong(entryOffset + 16)
                : Integer.toUnsignedLong(buffer.getInt(entryOffset + 8));
        int parentNid =
                (format != PstFile.Format.ANSI) ? buffer.getInt(entryOffset + 24) : buffer.getInt(entryOffset + 12);
        return new NodeEntry(nid, dataBid, subBid, parentNid);
    }

    /**
     * The maximum heap payload of a single NDB block for this file's format, i.e. the block size
     * (8192) minus the block trailer (16 bytes Unicode / 12 bytes ANSI). {@code readNodeData}
     * concatenates these payloads, so a Heap-on-Node addresses block {@code k} at {@code k *} this
     * value — not {@code k * 8192} — and a Table Context's row matrix packs whole rows per payload
     * with dead bytes at each payload tail.
     */
    int heapBlockSize() {
        return (format == PstFile.Format.ANSI) ? 8180 : 8176;
    }

    /**
     * Reads, decrypts and (if needed) inflates the full data of the node data tree rooted at the
     * given block id, concatenating data-block payloads in order.
     *
     * @throws PstException if the tree is malformed or the expanded data exceeds the configured
     *     maximum node size
     */
    byte[] readNodeData(long bid) throws IOException {
        var leafBids = new ArrayList<Long>();
        collectLeafBlockIds(bid, 0, new HashSet<>(), leafBids);
        if (leafBids.size() == 1) {
            var data = readLeafBlockData(leafBids.get(0));
            if (data.length > maxNodeSize) {
                throw new PstException("Node expansion exceeded " + maxNodeSize + " limit");
            }
            return data;
        }
        var out = new ByteArrayOutputStream();
        for (long leafBid : leafBids) {
            byte[] data = readLeafBlockData(leafBid);
            if ((long) out.size() + data.length > maxNodeSize) {
                throw new PstException("Node expansion exceeded " + maxNodeSize + " limit");
            }
            out.write(data, 0, data.length);
        }
        return out.toByteArray();
    }

    /**
     * Opens a stream over the data tree rooted at the given block id, reading, decrypting and
     * inflating one data block at a time. Unlike {@link #readNodeData} the content is never
     * materialized as a whole, so this is the right primitive for large attachment payloads.
     *
     * @throws PstException if the block tree is malformed
     */
    InputStream openNodeDataStream(long bid) throws IOException {
        var leafBids = new ArrayList<Long>();
        collectLeafBlockIds(bid, 0, new HashSet<>(), leafBids);
        return new NodeDataStream(leafBids);
    }

    private final class NodeDataStream extends InputStream {
        private final List<Long> leafBids;
        private int nextBlock;
        private byte[] current = new byte[0];
        private int position;
        private long totalBytes;

        private NodeDataStream(List<Long> leafBids) {
            this.leafBids = leafBids;
        }

        @Override
        public int read() throws IOException {
            if (!ensureData()) {
                return -1;
            }
            return current[position++] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            if (!ensureData()) {
                return -1;
            }
            int copied = Math.min(length, current.length - position);
            System.arraycopy(current, position, target, offset, copied);
            position += copied;
            return copied;
        }

        private boolean ensureData() throws IOException {
            while (position >= current.length) {
                if (nextBlock >= leafBids.size()) {
                    return false;
                }
                current = readLeafBlockData(leafBids.get(nextBlock++));
                position = 0;
                totalBytes += current.length;
                if (totalBytes > maxNodeSize) {
                    throw new PstException("Node expansion exceeded " + maxNodeSize + " limit");
                }
            }
            return true;
        }
    }

    /**
     * Resolves the leaf data-block ids of a node data tree: a plain bid resolves to itself, an
     * internal bid (XBLOCK/XXBLOCK) is expanded recursively in order.
     */
    private void collectLeafBlockIds(long bid, int depth, Set<Long> visited, List<Long> leafBids) throws IOException {
        if (!visited.add(bid)) {
            throw new PstException("Cyclic node reference detected: " + bid);
        }
        if (depth > MAX_BTREE_DEPTH) {
            throw new PstException("Recursion limit exceeded while reading node data");
        }
        if ((bid & 0x02L) == 0) {
            leafBids.add(bid);
            return;
        }
        var block = getBlock(bid);
        if (block == null) {
            throw new PstException("XBLOCK not found in BBT: " + bid);
        }
        var buffer = readBlockBytes(block);
        int blockType = Byte.toUnsignedInt(buffer.get(0));
        if (blockType != 0x01) {
            throw new PstException("Invalid XBLOCK type: " + blockType);
        }
        int entryCount = Short.toUnsignedInt(buffer.getShort(2));
        int entrySize = (format != PstFile.Format.ANSI) ? 8 : 4;
        // rgbid comes from an untrusted XBLOCK header; require the entry table to fit inside the
        // block before indexing into it.
        if (8 + (long) entryCount * entrySize > block.size()) {
            throw new PstException("XBLOCK entry table out of range: cEnt=" + entryCount);
        }
        for (int i = 0; i < entryCount; i++) {
            int offset = 8 + (i * entrySize);
            long childBid = (format != PstFile.Format.ANSI)
                    ? buffer.getLong(offset)
                    : Integer.toUnsignedLong(buffer.getInt(offset));
            collectLeafBlockIds(childBid, depth + 1, visited, leafBids);
        }
    }

    /** Reads one leaf data block and applies decryption and, for UNICODE_2013 stores, inflation. */
    private byte[] readLeafBlockData(long bid) throws IOException {
        var block = getBlock(bid);
        if (block == null) {
            throw new PstException("Block not found in BBT: " + bid);
        }
        byte[] array = readBlockBytes(block).array();
        if (encryptionType == PstFile.EncryptionType.COMPRESSIBLE) {
            CompressibleEncryption.decode(array);
        } else if (encryptionType == PstFile.EncryptionType.HIGH) {
            HighEncryption.decode(array, bid);
        }

        // cbInflated == cb means the block is stored uncompressed ([MS-PST] 2013 BBT entry): inflating
        // it would at best fail twice and at worst "succeed" on payload that happens to start with a
        // zlib header, silently replacing real content with garbage.
        if (format == PstFile.Format.UNICODE_2013 && block.inflatedSize() > 0 && block.inflatedSize() != block.size()) {
            try {
                array = tryDecompress(array, block, false);
            } catch (DataFormatException | RuntimeException firstFailure) {
                try {
                    array = tryDecompress(array, block, true);
                } catch (DataFormatException | RuntimeException secondFailure) {
                    // Both inflate attempts failed; keep the raw (still-compressed) bytes as a
                    // best-effort fallback, but log so the resulting garbage is traceable.
                    LOG.log(
                            System.Logger.Level.DEBUG,
                            () -> "Failed to decompress 2013 block " + bid + "; using raw bytes",
                            secondFailure);
                }
            }
        }
        return array;
    }

    private ByteBuffer readBlockBytes(BlockEntry block) throws IOException {
        var buffer = ByteBuffer.allocate(block.size()).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, buffer, block.offset());
        if (verifyCrc) {
            verifyBlockCrc(block, buffer.array());
        }
        return buffer;
    }

    private byte[] tryDecompress(byte[] array, BlockEntry block, boolean nowrap) throws DataFormatException {
        var inflater = new Inflater(nowrap);
        try {
            inflater.setInput(array, 0, (int) block.size());
            int initialCapacity = Math.min(block.inflatedSize(), 1024 * 1024);
            var inflated = new ByteArrayOutputStream(initialCapacity);
            var chunk = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(chunk);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                inflated.write(chunk, 0, count);
                if (inflated.size() > maxNodeSize) {
                    throw new DataFormatException("Decompression bomb detected (>" + maxNodeSize + " bytes)");
                }
            }
            byte[] result = inflated.toByteArray();
            return result.length > 0 ? result : array;
        } finally {
            inflater.end();
        }
    }

    NodeEntry readSubnodeEntry(long subBid, int targetNid) throws IOException {
        return readSubnodeEntry(subBid, targetNid, 0);
    }

    private NodeEntry readSubnodeEntry(long subBid, int targetNid, int depth) throws IOException {
        if (depth > MAX_BTREE_DEPTH) {
            throw new PstException("Recursion limit exceeded while reading subnode entry");
        }
        if (subBid == 0) {
            return null;
        }

        var block = getBlock(subBid);
        if (block == null) {
            throw new PstException("Subnode block not found in BBT: " + subBid);
        }
        var buffer = readBlockBytes(block);

        int blockType = Byte.toUnsignedInt(buffer.get(0));
        int level = Byte.toUnsignedInt(buffer.get(1));
        int entryCount = Short.toUnsignedInt(buffer.getShort(2));

        if (blockType != 0x02) {
            throw new PstException("Invalid subnode block type: " + blockType);
        }

        // The 4-byte dwPadding after the SL/SI block header exists only in the Unicode format
        // (MS-PST 2.2.2.8.3.3): ANSI entries start right after the 4-byte header.
        int entriesStart = (format != PstFile.Format.ANSI) ? 8 : 4;

        if (level > 0) {
            // SIBLOCK (branch)
            int entrySize = (format != PstFile.Format.ANSI) ? 16 : 8;
            if (entriesStart + (long) entryCount * entrySize > block.size()) {
                throw new PstException("SIBLOCK entry table out of range: cEnt=" + entryCount);
            }
            long selectedChildBid = -1;
            for (int i = 0; i < entryCount; i++) {
                int offset = entriesStart + (i * entrySize);
                long rawNid = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(offset)
                        : Integer.toUnsignedLong(buffer.getInt(offset));
                long childBid = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(offset + 8)
                        : Integer.toUnsignedLong(buffer.getInt(offset + 4));

                if (Integer.compareUnsigned((int) rawNid, targetNid) <= 0) {
                    selectedChildBid = childBid;
                } else {
                    break;
                }
            }
            if (selectedChildBid != -1) {
                return readSubnodeEntry(selectedChildBid, targetNid, depth + 1);
            }
            return null;
        }

        // SLBLOCK (leaf)
        int entrySize = (format != PstFile.Format.ANSI) ? 24 : 12;
        if (entriesStart + (long) entryCount * entrySize > block.size()) {
            throw new PstException("SLBLOCK entry table out of range: cEnt=" + entryCount);
        }
        for (int i = 0; i < entryCount; i++) {
            int offset = entriesStart + (i * entrySize);
            long rawNid = (format != PstFile.Format.ANSI)
                    ? buffer.getLong(offset)
                    : Integer.toUnsignedLong(buffer.getInt(offset));
            int nid = (int) (rawNid & 0xFFFFFFFFL);
            long dataBid = (format != PstFile.Format.ANSI)
                    ? buffer.getLong(offset + 8)
                    : Integer.toUnsignedLong(buffer.getInt(offset + 4));
            long childSubBid = (format != PstFile.Format.ANSI)
                    ? buffer.getLong(offset + 16)
                    : Integer.toUnsignedLong(buffer.getInt(offset + 8));

            if (nid == targetNid) {
                return new NodeEntry(nid, dataBid, childSubBid, 0);
            }
        }
        return null;
    }

    byte[] readSubnodeData(long subBid, int targetNid) throws IOException {
        NodeEntry entry = readSubnodeEntry(subBid, targetNid);
        if (entry != null) {
            return readNodeData(entry.dataBid());
        }
        return null;
    }

    /** A b-tree page with its trailer already parsed and validated. */
    private record BTreePage(byte[] bytes, int entryCount, int entrySize, int level) {}

    private BTreePage readBTreePage(long offset, String which) throws IOException {
        if (offset < 0 || offset >= channelSize) {
            throw new PstException("Invalid " + which + " page offset: " + offset);
        }
        byte[] bytes = pageBytes(offset);
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        int trailerOffset =
                (format == PstFile.Format.UNICODE_2013) ? 4056 : ((format == PstFile.Format.UNICODE) ? 488 : 496);
        int entryCount;
        int entrySize;
        int level;
        if (format == PstFile.Format.UNICODE_2013) {
            entryCount = Short.toUnsignedInt(buffer.getShort(trailerOffset));
            entrySize = Byte.toUnsignedInt(buffer.get(trailerOffset + 4));
            level = Byte.toUnsignedInt(buffer.get(trailerOffset + 5));
        } else {
            entryCount = Byte.toUnsignedInt(buffer.get(trailerOffset));
            entrySize = Byte.toUnsignedInt(buffer.get(trailerOffset + 2));
            level = Byte.toUnsignedInt(buffer.get(trailerOffset + 3));
        }

        // cEnt and cbEnt come straight from the page trailer ([MS-PST] §2.2.2.7.7.1); a crafted page
        // could otherwise drive offset = i * entrySize past the fixed page buffer (IndexOutOfBounds).
        validatePageEntries(entryCount, entrySize, trailerOffset, which);
        return new BTreePage(bytes, entryCount, entrySize, level);
    }

    private byte[] pageBytes(long offset) throws IOException {
        synchronized (pageCache) {
            var cached = pageCache.get(offset);
            if (cached != null) {
                return cached;
            }
        }
        int pageSize = (format == PstFile.Format.UNICODE_2013) ? 4096 : 512;
        var buffer = ByteBuffer.allocate(pageSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, buffer, offset);
        byte[] bytes = buffer.array();
        if (verifyCrc) {
            verifyPageCrc(bytes, offset);
        }
        synchronized (pageCache) {
            pageCache.put(offset, bytes);
        }
        return bytes;
    }

    /**
     * Verifies the PAGETRAILER CRC ([MS-PST] §2.2.2.7.1): the CRC covers the page minus its trailer
     * (496 bytes Unicode / 500 ANSI), and the {@code dwCRC} field sits at 500 (Unicode) or 508
     * (ANSI — the ANSI trailer orders {@code bid} before {@code dwCRC}). Offsets validated against
     * real Unicode and ANSI stores.
     */
    private void verifyPageCrc(byte[] page, long offset) throws PstException {
        var ansi = format == PstFile.Format.ANSI;
        var stored = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).getInt(ansi ? 508 : 500);
        var computed = PstCrc.compute(page, 0, ansi ? 500 : 496);
        if (stored != computed) {
            throw new PstException("Page CRC mismatch at offset " + offset
                    + ": the store is corrupted (stored 0x" + Integer.toHexString(stored) + ", computed 0x"
                    + Integer.toHexString(computed) + ")");
        }
    }

    /**
     * Verifies the BLOCKTRAILER CRC ([MS-PST] §2.2.2.8.1): blocks are stored in 64-byte-aligned
     * slots with the trailer in the last 16 (Unicode) / 12 (ANSI) bytes; the CRC covers the
     * {@code cb} data bytes as stored (still encrypted), and {@code dwCRC} sits at trailer offset 4
     * (Unicode) or 8 (ANSI). Offsets validated against real Unicode and ANSI stores.
     */
    private void verifyBlockCrc(BlockEntry block, byte[] data) throws IOException {
        var ansi = format == PstFile.Format.ANSI;
        var trailerLength = ansi ? 12 : 16;
        var slotSize = ((block.size() + trailerLength + 63) / 64) * 64L;
        var trailer = ByteBuffer.allocate(trailerLength).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, trailer, block.offset() + slotSize - trailerLength);
        var stored = trailer.getInt(ansi ? 8 : 4);
        var computed = PstCrc.compute(data, 0, block.size());
        if (stored != computed) {
            throw new PstException("Block CRC mismatch for block 0x" + Long.toHexString(block.blockId())
                    + ": the store is corrupted (stored 0x" + Integer.toHexString(stored) + ", computed 0x"
                    + Integer.toHexString(computed) + ")");
        }
    }

    private static void validatePageEntries(int numEntries, int entrySize, int trailerOffset, String which)
            throws PstException {
        if (numEntries < 0) {
            throw new PstException(which + " page has a negative entry count: " + numEntries);
        }
        // An empty page is legitimate; only a non-empty one must declare a positive entry size whose
        // table fits before the page trailer.
        if (numEntries > 0 && (entrySize <= 0 || (long) numEntries * entrySize > trailerOffset)) {
            throw new PstException(
                    which + " page entry table out of range: cEnt=" + numEntries + " cbEnt=" + entrySize);
        }
    }

    private void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        buffer.clear();
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read == -1) {
                throw new PstException("Unexpected EOF reading b-tree data at position " + position + " (file size: "
                        + channelSize + ")");
            }
        }
    }
}
