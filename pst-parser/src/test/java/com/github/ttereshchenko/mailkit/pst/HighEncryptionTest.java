package com.github.ttereshchenko.mailkit.pst;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The HIGH ("strong") scheme is a salted byte permutation; encoding is the exact inverse chain of
 * {@link HighEncryption#decode}. These tests build the inverse lookup tables and verify that
 * decode(encode(x)) round-trips, which pins both the table contents and the salt arithmetic.
 */
class HighEncryptionTest {

    private static int[] invert(byte[] table) {
        var inverse = new int[256];
        for (int i = 0; i < 256; i++) {
            inverse[table[i] & 0xFF] = i;
        }
        return inverse;
    }

    private static int[] invert(int[] table) {
        var inverse = new int[256];
        for (int i = 0; i < 256; i++) {
            inverse[table[i] & 0xFF] = i;
        }
        return inverse;
    }

    /** Applies the inverse of {@link HighEncryption#decode}: same salt schedule, reversed steps. */
    private static byte[] encode(byte[] plain, long bid) {
        var invHigh1 = invert(HighEncryption.HIGH_1);
        var invHigh2 = invert(HighEncryption.HIGH_2);
        var invCompEnc = invert(CompressibleEncryption.COMP_ENC);

        var encoded = plain.clone();
        int key = (int) bid;
        int salt = (((key & 0xffff0000) >>> 16) ^ (key & 0x0000ffff));
        for (int i = 0; i < encoded.length; i++) {
            int lowerSalt = salt & 0x00ff;
            int upperSalt = (salt & 0xff00) >>> 8;
            int value = encoded[i] & 0xFF;

            value += lowerSalt;
            value = invCompEnc[value & 0xFF];
            value += upperSalt;
            value = invHigh2[value & 0xFF];
            value -= upperSalt;
            value = invHigh1[value & 0xFF];
            value -= lowerSalt;

            encoded[i] = (byte) value;
            salt++;
        }
        return encoded;
    }

    @Test
    void decodeInvertsEncodeForRandomData() {
        var random = new Random(42);
        var plain = new byte[8176];
        random.nextBytes(plain);

        for (long bid : new long[] {4L, 0x1234L, 0xCAFEBABEL, 0x1_0000_0004L}) {
            byte[] encoded = encode(plain, bid);
            assertFalse(Arrays.equals(plain, encoded), "Encoding must actually transform the data");
            HighEncryption.decode(encoded, bid);
            assertArrayEquals(plain, encoded, "decode(encode(x)) must round-trip for bid " + bid);
        }
    }

    @Test
    void saltDependsOnBlockId() {
        var plain = new byte[64];
        byte[] first = encode(plain, 4L);
        byte[] second = encode(plain, 8L);
        assertFalse(Arrays.equals(first, second), "Different BIDs must produce different ciphertext");
    }

    @Test
    void lookupTablesArePermutations() {
        assertEquals(
                256,
                IntStream.range(0, 256)
                        .map(index -> HighEncryption.HIGH_1[index] & 0xFF)
                        .distinct()
                        .count());
        assertEquals(
                256,
                IntStream.range(0, 256)
                        .map(index -> HighEncryption.HIGH_2[index] & 0xFF)
                        .distinct()
                        .count());
        assertEquals(
                256,
                IntStream.range(0, 256)
                        .map(index -> CompressibleEncryption.COMP_ENC[index] & 0xFF)
                        .distinct()
                        .count());
    }
}
