package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                // Lookups descend lazily, so the cycle is detected when a block is resolved, not at
                // construction; the root page itself is structurally valid. getBlock tracks visited page
                // offsets, so a loop back to an ancestor is reported as a cycle at the first revisit
                // rather than only when the depth cap trips.
                var database = new NodeDatabase(
                        channel, PstFile.Format.UNICODE_2013, PstFile.EncryptionType.NONE, 0, 4096, 64L * 1024 * 1024);
                var exception = assertThrows(PstException.class, () -> database.getBlock(1L));
                assertTrue(exception.getMessage().contains("Cyclic"), exception.getMessage());
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
    void testUndersizedEntrySizeThrowsAtOpen() throws Exception {
        // A cbEnt below the smallest legitimate b-tree entry (a Unicode BTENTRY/BBTENTRY is 24 bytes,
        // [MS-PST] §2.2.2.7.7.2/§2.2.2.7.7.3) would make the per-entry reads — which use hardcoded
        // sub-offsets (the BREF at +16, the 2013 cbInflated at +18) — stride into adjacent entries
        // or the page trailer. Before the fix only cbEnt <= 0 was rejected, so an 8-byte stride was
        // accepted and silently mis-read; the eagerly validated root page must now reject it.
        Path tempFile = Files.createTempFile("test_undersized", ".pst");
        try {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                raf.setLength(8192);
                ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
                int trailerOffset = 4056;
                buf.putShort(trailerOffset, (short) 1); // numEntries
                buf.put(trailerOffset + 4, (byte) 8); // entrySize 8 < the 24-byte minimum
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
                buf.putShort(16, (short) compressedSize); // cb (stored/compressed size)
                // cbInflated is the uint16 at +18 (see NodeDatabase#getBlock). The true 5 MB inflated size
                // does not fit a uint16 and is not needed here: the field is only a capacity hint, and the
                // bomb guard bounds the result by maxNodeSize. Any value != cb flags the block as compressed.
                buf.putShort(18, (short) 0xFFFF); // cbInflated != cb -> inflate
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
                buf.putShort(16, (short) payloadSize); // cb (stored size)
                buf.putShort(18, (short) payloadSize); // cbInflated == cb (+18) -> stored uncompressed
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

    @Test
    void cbInflatedIsReadAtOffset18NotTheNextEntryAt24() throws Exception {
        // Regression: cbInflated is the uint16 at +18 of a 24-byte 2013 BBTENTRY. The old code read a
        // uint32 at +24, which with a 24-byte stride lands on the *next* entry's BID. This page carries a
        // second entry whose BID sits exactly at entry[0]+24, so a +24 read sees a non-zero,
        // "looks-compressed" value; the uncompressed block under test (cbInflated == cb, valid-zlib
        // payload) would then be wrongly inflated into garbage. Reading +18 keeps it raw.
        Path tempFile = Files.createTempFile("test_inflated_offset", ".pst");
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
                buf.putShort(trailerOffset, (short) 2); // two entries
                buf.put(trailerOffset + 4, (byte) 24); // entrySize
                buf.put(trailerOffset + 5, (byte) 0); // level = 0 (leaf)
                // entry[0]: the uncompressed block under test.
                buf.putLong(0, 4L); // bid
                buf.putLong(8, 8192L); // fileOffset
                buf.putShort(16, (short) payloadSize); // cb
                buf.putShort(18, (short) payloadSize); // cbInflated == cb -> uncompressed
                // entry[1] begins at +24, so its BID's low 32 bits are exactly what a buggy getInt(+24)
                // would read for entry[0]. Pick a value != cb so the old code would inflate the block.
                buf.putLong(24, 0x0001_0000L); // unused second block; low int = 65536 != cb
                buf.putLong(32, 8192L);
                buf.putShort(40, (short) payloadSize);
                buf.putShort(42, (short) payloadSize);
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
                        "cbInflated must be read at +18; a +24 read sees the next entry's BID and wrongly inflates");
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
