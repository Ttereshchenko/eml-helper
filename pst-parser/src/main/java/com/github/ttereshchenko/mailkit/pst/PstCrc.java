package com.github.ttereshchenko.mailkit.pst;

/**
 * The PST CRC algorithm ([MS-PST] §5.3): a reflected CRC-32 (polynomial {@code 0xEDB88320}) with a
 * zero initial value and no final inversion — deliberately distinct from {@code java.util.zip.CRC32},
 * which uses {@code 0xFFFFFFFF} for both. Used by the page and block trailers and by
 * PidTagPstPassword.
 */
final class PstCrc {

    private static final int[] TABLE = buildTable();

    private PstCrc() {}

    private static int[] buildTable() {
        var table = new int[256];
        for (var index = 0; index < 256; index++) {
            var value = index;
            for (var bit = 0; bit < 8; bit++) {
                value = (value & 1) != 0 ? (value >>> 1) ^ 0xEDB88320 : value >>> 1;
            }
            table[index] = value;
        }
        return table;
    }

    /** The CRC of {@code length} bytes of {@code data} starting at {@code offset}. */
    static int compute(byte[] data, int offset, int length) {
        var crc = 0;
        for (var index = offset; index < offset + length; index++) {
            crc = TABLE[(crc ^ data[index]) & 0xFF] ^ (crc >>> 8);
        }
        return crc;
    }
}
