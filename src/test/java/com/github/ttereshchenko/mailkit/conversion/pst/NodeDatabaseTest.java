package com.github.ttereshchenko.mailkit.conversion.pst;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;

class NodeDatabaseTest {

    @Test
    void testCyclicBbtThrowsException() throws Exception {
        Path tempFile = Files.createTempFile("test_cycle", ".pst");
        try {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                raf.setLength(4096);
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
                assertThrows(
                        PstException.class,
                        () -> new NodeDatabase(
                                channel,
                                PstFile.Format.UNICODE_2013,
                                PstFile.EncryptionType.NONE,
                                0,
                                0,
                                64L * 1024 * 1024));
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testOversizedEntryTableThrowsException() throws Exception {
        // cEnt * cbEnt would run past the fixed page buffer ([MS-PST] §2.2.2.7.7.1); before the bounds
        // check this threw IndexOutOfBoundsException during construction instead of a clean PstException.
        Path tempFile = Files.createTempFile("test_oversized", ".pst");
        try {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                raf.setLength(4096);
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
                                0,
                                64L * 1024 * 1024));
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testOutOfRangeBlockOffsetThrowsException() throws Exception {
        // A leaf BBTENTRY whose BREF file offset points past the file must throw a clean PstException
        // rather than reaching channel.read with an out-of-range position.
        Path tempFile = Files.createTempFile("test_bref", ".pst");
        try {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                raf.setLength(4096);
                ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
                int trailerOffset = 4056;
                buf.putShort(trailerOffset, (short) 1); // numEntries
                buf.put(trailerOffset + 4, (byte) 24); // entrySize
                buf.put(trailerOffset + 5, (byte) 0); // level = 0 (leaf)
                // Leaf BBTENTRY 0: bid@0, fileOffset@8, size@16
                buf.putLong(0, 1L); // bid
                buf.putLong(8, 100_000L); // fileOffset well past the 4096-byte file
                buf.putShort(16, (short) 64); // size
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
                                0,
                                64L * 1024 * 1024));
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
            java.util.zip.Deflater deflater = new java.util.zip.Deflater();
            byte[] raw = new byte[5 * 1024 * 1024]; // 5MB
            deflater.setInput(raw);
            deflater.finish();
            byte[] comp = new byte[5 * 1024 * 1024];
            int compSize = deflater.deflate(comp);

            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                raf.setLength(1024); // mock header
                raf.seek(1024);
                raf.write(comp, 0, compSize);
            }

            try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.READ)) {
                NodeDatabase database = new NodeDatabase(
                        channel, PstFile.Format.UNICODE_2013, PstFile.EncryptionType.NONE, 0, 0, 64L * 1024 * 1024);

                // 1024 offset, compressed size, inflated size = 5MB
                var block = new BlockEntry(1L, 1024L, compSize, 1, 5 * 1024 * 1024);

                // Inject block into BBT
                var bbtField = NodeDatabase.class.getDeclaredField("bbt");
                bbtField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<Long, BlockEntry> bbt = (java.util.Map<Long, BlockEntry>) bbtField.get(database);
                bbt.put(1L, block);

                var readMethod = NodeDatabase.class.getDeclaredMethod(
                        "readNodeData", long.class, int.class, java.util.Set.class, long[].class);
                readMethod.setAccessible(true);
                byte[] inflated =
                        (byte[]) readMethod.invoke(database, 1L, 0, new java.util.HashSet<>(), new long[] {0});

                org.junit.jupiter.api.Assertions.assertEquals(5 * 1024 * 1024, inflated.length);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
