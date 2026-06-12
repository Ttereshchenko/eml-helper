package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class NodeDatabaseTest {

    @Test
    void testCyclicBbtThrowsException() throws Exception {
        Path tempFile = Files.createTempFile("test_cycle", ".pst");
        try {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                raf.setLength(8192);
                ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);

                int trailerOffset = 4056;
                buf.putShort(trailerOffset, (short) 1); // numEntries
                buf.put(trailerOffset + 4, (byte) 24); // entrySize
                buf.put(trailerOffset + 5, (byte) 1); // level = 1 (intermediate)

                // Entry 0
                // childBTreeOffset is at offset + 16 for UNICODE_2013
                buf.putLong(16, 0); // Loops back to 0!

                raf.write(buf.array());
            }

            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                // Lookups descend lazily, so the cycle is detected (via the depth cap) when a block
                // is resolved, not at construction; the root page itself is structurally valid.
                var database = new NodeDatabase(
                        channel, PstFile.Format.UNICODE_2013, PstFile.EncryptionType.NONE, 0, 4096, 64L * 1024 * 1024);
                assertThrows(PstException.class, () -> database.getBlock(1L));
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testOversizedEntryTableThrowsAtOpen() throws Exception {
        // cEnt * cbEnt would run past the fixed page buffer ([MS-PST] §2.2.2.7.7.1); the root pages
        // are validated eagerly, so a corrupt root must throw a clean PstException at construction.
        Path tempFile = Files.createTempFile("test_oversized", ".pst");
        try {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                raf.setLength(8192);
                ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
                int trailerOffset = 4056;
                buf.putShort(trailerOffset, (short) 4096); // numEntries: 4096 * 24 far exceeds the page
                buf.put(trailerOffset + 4, (byte) 24); // entrySize
                buf.put(trailerOffset + 5, (byte) 0); // level = 0 (leaf)
                raf.write(buf.array());
            }

            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                assertThrows(
                        PstException.class,
                        () -> new NodeDatabase(
                                channel,
                                PstFile.Format.UNICODE_2013,
                                PstFile.EncryptionType.NONE,
                                0,
                                4096,
                                64L * 1024 * 1024));
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testOutOfRangeBlockOffsetThrowsException() throws Exception {
        // A leaf BBTENTRY whose BREF file offset points past the file must throw a clean PstException
        // when the block is resolved rather than reaching channel.read with an out-of-range position.
        Path tempFile = Files.createTempFile("test_bref", ".pst");
        try {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                raf.setLength(8192);
                ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
                int trailerOffset = 4056;
                buf.putShort(trailerOffset, (short) 1); // numEntries
                buf.put(trailerOffset + 4, (byte) 24); // entrySize
                buf.put(trailerOffset + 5, (byte) 0); // level = 0 (leaf)
                // Leaf BBTENTRY 0: bid@0, fileOffset@8, size@16
                buf.putLong(0, 1L); // bid
                buf.putLong(8, 100_000L); // fileOffset well past the 8192-byte file
                buf.putShort(16, (short) 64); // size
                raf.write(buf.array());
            }

            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                var database = new NodeDatabase(
                        channel, PstFile.Format.UNICODE_2013, PstFile.EncryptionType.NONE, 0, 4096, 64L * 1024 * 1024);
                assertThrows(PstException.class, () -> database.getBlock(1L));
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testLargeInflatedBlockIsAccepted() throws Exception {
        Path tempFile = Files.createTempFile("test_large", ".pst");
        try {
            // Compress 5MB of zeroes
            var deflater = new Deflater();
            var raw = new byte[5 * 1024 * 1024]; // 5MB
            deflater.setInput(raw);
            deflater.finish();
            var compressed = new byte[5 * 1024 * 1024];
            int compressedSize = deflater.deflate(compressed);

            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                raf.setLength(8192 + compressedSize);

                // BBT root: a leaf page at 0 with one entry describing the compressed block at 8192.
                ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
                int trailerOffset = 4056;
                buf.putShort(trailerOffset, (short) 1); // numEntries
                buf.put(trailerOffset + 4, (byte) 24); // entrySize
                buf.put(trailerOffset + 5, (byte) 0); // level = 0 (leaf)
                buf.putLong(0, 4L); // bid (no internal flag)
                buf.putLong(8, 8192L); // fileOffset
                buf.putShort(16, (short) compressedSize); // size
                buf.putInt(24, 5 * 1024 * 1024); // inflated size
                raf.write(buf.array());

                // NBT root: an empty leaf page at 4096 (all zeroes parse as cEnt=0).
                raf.seek(8192);
                raf.write(compressed, 0, compressedSize);
            }

            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                var database = new NodeDatabase(
                        channel, PstFile.Format.UNICODE_2013, PstFile.EncryptionType.NONE, 0, 4096, 64L * 1024 * 1024);

                byte[] inflated = database.readNodeData(4L);
                assertEquals(5 * 1024 * 1024, inflated.length);

                try (var stream = database.openNodeDataStream(4L)) {
                    assertArrayEquals(inflated, stream.readAllBytes());
                }
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void uncompressedBlockWithZlibLookingPayloadIsNotInflated() throws Exception {
        // F6 regression: a 2013-format block stored uncompressed (cbInflated == cb) whose payload
        // happens to be a valid zlib stream must be returned raw — the old "> 0" check inflated it
        // "successfully" and silently replaced the real content with the inflated garbage.
        Path tempFile = Files.createTempFile("test_uncompressed", ".pst");
        try {
            var deflater = new Deflater();
            deflater.setInput("not the real content".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            deflater.finish();
            var payload = new byte[256];
            int payloadSize = deflater.deflate(payload);
            var raw = java.util.Arrays.copyOf(payload, payloadSize);

            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                raf.setLength(8192 + payloadSize);
                ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
                int trailerOffset = 4056;
                buf.putShort(trailerOffset, (short) 1); // numEntries
                buf.put(trailerOffset + 4, (byte) 24); // entrySize
                buf.put(trailerOffset + 5, (byte) 0); // level = 0 (leaf)
                buf.putLong(0, 4L); // bid
                buf.putLong(8, 8192L); // fileOffset
                buf.putShort(16, (short) payloadSize); // size
                buf.putInt(24, payloadSize); // inflated size == size: stored uncompressed
                raf.write(buf.array());
                raf.seek(8192);
                raf.write(raw);
            }

            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                var database = new NodeDatabase(
                        channel, PstFile.Format.UNICODE_2013, PstFile.EncryptionType.NONE, 0, 4096, 64L * 1024 * 1024);
                assertArrayEquals(
                        raw,
                        database.readNodeData(4L),
                        "An uncompressed block must surface its raw bytes even when they parse as zlib");
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
