package com.github.ttereshchenko.mailkit.conversion.pst;

import com.intellij.openapi.diagnostic.Logger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class NodeDatabase {

    private static final Logger LOG = Logger.getInstance(NodeDatabase.class);

    private final FileChannel channel;
    private final PstFile.Format format;
    private final PstFile.EncryptionType encryptionType;
    private final Map<Long, BlockEntry> bbt = new HashMap<>();
    private final Map<Integer, NodeEntry> nbt = new HashMap<>();
    private final long maxNodeSize;

    public NodeDatabase(
            FileChannel channel,
            PstFile.Format format,
            PstFile.EncryptionType encryptionType,
            long bbtRootOffset,
            long nbtRootOffset,
            long maxNodeSize)
            throws IOException {
        this.channel = channel;
        this.format = format;
        this.encryptionType = encryptionType;
        this.maxNodeSize = maxNodeSize;

        loadBbt(bbtRootOffset, 0, new HashSet<>());
        loadNbt(nbtRootOffset, 0, new HashSet<>());
    }

    public BlockEntry getBlock(long bid) {
        // The BID has internal flags. We mask out the internal flag (bit 1).
        return bbt.get(bid & ~0x02L);
    }

    public NodeEntry getNode(int nid) {
        return nbt.get(nid);
    }

    /**
     * The maximum heap payload of a single NDB block for this file's format, i.e. the block size
     * (8192) minus the block trailer (16 bytes Unicode / 12 bytes ANSI). {@code readNodeData}
     * concatenates these payloads, so a Heap-on-Node addresses block {@code k} at {@code k *} this
     * value — not {@code k * 8192}.
     */
    int heapBlockSize() {
        return (format == PstFile.Format.ANSI) ? 8180 : 8176;
    }

    public byte[] readNodeData(long bid) throws IOException {
        return readNodeData(bid, 0, new java.util.HashSet<>(), new long[] {0});
    }

    private byte[] readNodeData(long bid, int depth, java.util.Set<Long> visited, long[] totalSize) throws IOException {
        if (!visited.add(bid)) {
            throw new PstException("Cyclic node reference detected: " + bid);
        }
        if (depth > 64) {
            throw new PstException("Recursion limit exceeded while reading node data");
        }
        if ((bid & 0x02L) != 0) {
            var block = getBlock(bid);
            if (block == null) {
                throw new PstException("XBLOCK not found in BBT: " + bid);
            }
            var buffer = ByteBuffer.allocate(block.size()).order(ByteOrder.LITTLE_ENDIAN);
            readFully(channel, buffer, block.offset());

            int bType = Byte.toUnsignedInt(buffer.get(0));
            if (bType != 0x01) {
                throw new PstException("Invalid XBLOCK type: " + bType);
            }
            int cLevel = Byte.toUnsignedInt(buffer.get(1));
            int cEnt = Short.toUnsignedInt(buffer.getShort(2));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int entrySize = (format != PstFile.Format.ANSI) ? 8 : 4;
            for (int i = 0; i < cEnt; i++) {
                int offset = 8 + (i * entrySize);
                long childBid = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(offset)
                        : Integer.toUnsignedLong(buffer.getInt(offset));

                byte[] childData = readNodeData(childBid, depth + 1, visited, totalSize);
                totalSize[0] += childData.length;
                if (totalSize[0] > maxNodeSize) {
                    throw new PstException("Node expansion exceeded " + maxNodeSize + " limit");
                }
                out.write(childData);
            }
            return out.toByteArray();
        }

        var block = getBlock(bid);
        if (block == null) {
            throw new PstException("Block not found in BBT: " + bid);
        }

        var buffer = ByteBuffer.allocate(block.size()).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, buffer, block.offset());
        byte[] array = buffer.array();
        if (encryptionType == PstFile.EncryptionType.COMPRESSIBLE) {
            CompressibleEncryption.decode(array);
        } else if (encryptionType == PstFile.EncryptionType.HIGH) {
            HighEncryption.decode(array, bid);
        }

        boolean isCompressedBlock = block.inflatedSize() > 0;

        if (format == PstFile.Format.UNICODE_2013 && isCompressedBlock) {
            try {
                array = tryDecompress(array, block, false);
            } catch (Exception err) {
                try {
                    array = tryDecompress(array, block, true);
                } catch (Exception err2) {
                    // Both inflate attempts failed; keep the raw (still-compressed) bytes as a
                    // best-effort fallback, but log so the resulting garbage is traceable.
                    LOG.debug("Failed to decompress 2013 block " + bid + "; using raw bytes", err2);
                }
            }
        }
        return array;
    }

    private byte[] tryDecompress(byte[] array, BlockEntry block, boolean nowrap) throws DataFormatException {
        Inflater inflater = new Inflater(nowrap);
        try {
            inflater.setInput(array, 0, (int) block.size());
            int initialCapacity = Math.min(block.inflatedSize(), 1024 * 1024);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(initialCapacity);
            byte[] bufferInner = new byte[4096];
            while (!inflater.finished()) {
                int count = inflater.inflate(bufferInner);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                bos.write(bufferInner, 0, count);
                if (bos.size() > maxNodeSize) {
                    throw new DataFormatException("Decompression bomb detected (>" + maxNodeSize + " bytes)");
                }
            }
            byte[] inflated = bos.toByteArray();
            if (inflated.length > 0) {
                return inflated;
            }
            return array;
        } finally {
            inflater.end();
        }
    }

    public NodeEntry readSubnodeEntry(long subBid, int targetNid) throws IOException {
        return readSubnodeEntry(subBid, targetNid, 0);
    }

    private NodeEntry readSubnodeEntry(long subBid, int targetNid, int depth) throws IOException {
        if (depth > 64) {
            throw new PstException("Recursion limit exceeded while reading subnode entry");
        }
        if (subBid == 0) return null;

        var block = getBlock(subBid);
        if (block == null) {
            throw new PstException("Subnode block not found in BBT: " + subBid);
        }

        var buffer = ByteBuffer.allocate(block.size()).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, buffer, block.offset());

        int bType = Byte.toUnsignedInt(buffer.get(0));
        int cLevel = Byte.toUnsignedInt(buffer.get(1));
        int cEnt = Short.toUnsignedInt(buffer.getShort(2));

        if (bType != 0x02) {
            throw new PstException("Invalid subnode block type: " + bType);
        }

        if (cLevel > 0) {
            // SIBLOCK (branch)
            int entrySize = (format != PstFile.Format.ANSI) ? 16 : 8;
            long selectedChildBid = -1;
            for (int i = 0; i < cEnt; i++) {
                int offset = 8 + (i * entrySize);
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
        for (int i = 0; i < cEnt; i++) {
            int offset = 8 + (i * entrySize);
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

    public byte[] readSubnodeData(long subBid, int targetNid) throws IOException {
        NodeEntry entry = readSubnodeEntry(subBid, targetNid);
        if (entry != null) {
            return readNodeData(entry.dataBid());
        }
        return null;
    }

    public Map<Integer, NodeEntry> getAllNodes() {
        return Collections.unmodifiableMap(nbt);
    }

    private void loadBbt(long bTreeOffset, int depth, Set<Long> visited) throws IOException {
        if (depth > 64) {
            throw new PstException("BBT depth limit exceeded");
        }
        if (!visited.add(bTreeOffset)) {
            throw new PstException("Cyclic BBT reference detected at offset " + bTreeOffset);
        }
        if (bTreeOffset < 0 || bTreeOffset >= channel.size()) {
            throw new PstException("Invalid BBT offset: " + bTreeOffset);
        }

        int pageSize = (format == PstFile.Format.UNICODE_2013) ? 4096 : 512;
        var buffer = ByteBuffer.allocate(pageSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, buffer, bTreeOffset);

        int trailerOffset =
                (format == PstFile.Format.UNICODE_2013) ? 4056 : ((format == PstFile.Format.UNICODE) ? 488 : 496);
        int numEntries;
        int entrySize;
        int level;

        if (format == PstFile.Format.UNICODE_2013) {
            numEntries = Short.toUnsignedInt(buffer.getShort(trailerOffset));
            entrySize = Byte.toUnsignedInt(buffer.get(trailerOffset + 4));
            level = Byte.toUnsignedInt(buffer.get(trailerOffset + 5));
        } else {
            numEntries = Byte.toUnsignedInt(buffer.get(trailerOffset));
            entrySize = Byte.toUnsignedInt(buffer.get(trailerOffset + 2));
            level = Byte.toUnsignedInt(buffer.get(trailerOffset + 3));
        }

        // cEnt and cbEnt come straight from the page trailer ([MS-PST] §2.2.2.7.7.1); a crafted page
        // could otherwise drive offset = i * entrySize past the fixed page buffer (IndexOutOfBounds).
        validatePageEntries(numEntries, entrySize, trailerOffset, "BBT");

        long channelSize = channel.size();
        if (level > 0) {
            for (int i = 0; i < numEntries; i++) {
                int offset = i * entrySize;
                long childBTreeOffset = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(offset + 16)
                        : Integer.toUnsignedLong(buffer.getInt(offset + 8));
                loadBbt(childBTreeOffset, depth + 1, visited);
            }
        } else {
            for (int i = 0; i < numEntries; i++) {
                int offset = i * entrySize;
                long bid = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(offset)
                        : Integer.toUnsignedLong(buffer.getInt(offset));
                long fileOffset = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(offset + 8)
                        : Integer.toUnsignedLong(buffer.getInt(offset + 4));
                int size = Short.toUnsignedInt(buffer.getShort(offset + ((format != PstFile.Format.ANSI) ? 16 : 8)));
                int refCount =
                        Short.toUnsignedInt(buffer.getShort(offset + ((format != PstFile.Format.ANSI) ? 18 : 10)));
                int inflatedSize = (format == PstFile.Format.UNICODE_2013) ? buffer.getInt(offset + 24) : 0;
                // The leaf BREF file offset is otherwise unchecked; validate it (a negative ANSI offset
                // would throw IllegalArgumentException at channel.read) before the block is ever read.
                if (fileOffset < 0 || fileOffset + size > channelSize) {
                    throw new PstException("Block BREF offset out of range: offset=" + fileOffset + " size=" + size);
                }
                bbt.put(bid & ~0x02L, new BlockEntry(bid, fileOffset, size, refCount, inflatedSize));
            }
        }
    }

    private void loadNbt(long bTreeOffset, int depth, Set<Long> visited) throws IOException {
        if (depth > 64) {
            throw new PstException("NBT depth limit exceeded");
        }
        if (!visited.add(bTreeOffset)) {
            throw new PstException("Cyclic NBT reference detected at offset " + bTreeOffset);
        }
        if (bTreeOffset < 0 || bTreeOffset >= channel.size()) {
            throw new PstException("Invalid NBT offset: " + bTreeOffset);
        }

        int pageSize = (format == PstFile.Format.UNICODE_2013) ? 4096 : 512;
        var buffer = ByteBuffer.allocate(pageSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, buffer, bTreeOffset);

        int trailerOffset =
                (format == PstFile.Format.UNICODE_2013) ? 4056 : ((format == PstFile.Format.UNICODE) ? 488 : 496);
        int numEntries;
        int entrySize;
        int level;

        if (format == PstFile.Format.UNICODE_2013) {
            numEntries = Short.toUnsignedInt(buffer.getShort(trailerOffset));
            entrySize = Byte.toUnsignedInt(buffer.get(trailerOffset + 4));
            level = Byte.toUnsignedInt(buffer.get(trailerOffset + 5));
        } else {
            numEntries = Byte.toUnsignedInt(buffer.get(trailerOffset));
            entrySize = Byte.toUnsignedInt(buffer.get(trailerOffset + 2));
            level = Byte.toUnsignedInt(buffer.get(trailerOffset + 3));
        }

        validatePageEntries(numEntries, entrySize, trailerOffset, "NBT");

        if (level > 0) {
            for (int i = 0; i < numEntries; i++) {
                int offset = i * entrySize;
                long childBTreeOffset = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(offset + 16)
                        : Integer.toUnsignedLong(buffer.getInt(offset + 8));
                loadNbt(childBTreeOffset, depth + 1, visited);
            }
        } else {
            for (int i = 0; i < numEntries; i++) {
                int offset = i * entrySize;
                long rawNid = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(offset)
                        : Integer.toUnsignedLong(buffer.getInt(offset));
                int nid = (int) (rawNid & 0xFFFFFFFFL);
                long dataBid = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(offset + 8)
                        : Integer.toUnsignedLong(buffer.getInt(offset + 4));
                long subBid = (format != PstFile.Format.ANSI)
                        ? buffer.getLong(offset + 16)
                        : Integer.toUnsignedLong(buffer.getInt(offset + 8));
                int parentNid =
                        (format != PstFile.Format.ANSI) ? buffer.getInt(offset + 24) : buffer.getInt(offset + 12);
                nbt.put(nid, new NodeEntry(nid, dataBid, subBid, parentNid));
            }
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
                try {
                    throw new PstException("Unexpected EOF reading BTree block at position " + position
                            + " (channel size: " + channel.size() + ")");
                } catch (IOException failure) {
                    throw new PstException("Unexpected EOF at position " + position);
                }
            }
        }
    }
}
