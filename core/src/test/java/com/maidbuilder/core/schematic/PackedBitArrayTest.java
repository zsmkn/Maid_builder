package com.maidbuilder.core.schematic;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackedBitArrayTest {
    /** Independent reference decoder: treat the long array as one little-endian bit stream. */
    private static int referenceGet(long[] data, int bits, long index) {
        BigInteger stream = BigInteger.ZERO;
        for (int i = data.length - 1; i >= 0; i--) {
            stream = stream.shiftLeft(64).or(new BigInteger(Long.toUnsignedString(data[i])));
        }
        return stream.shiftRight((int) (index * bits)).and(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)).intValue();
    }

    @Test
    void roundTripsEveryWidthIncludingValuesThatStraddleLongs() {
        Random random = new Random(42);
        for (int bits = 1; bits <= 32; bits++) {
            int size = 200;
            PackedBitArray arr = new PackedBitArray(bits, size);
            int[] expected = new int[size];
            long max = (1L << bits) - 1;
            for (int i = 0; i < size; i++) {
                expected[i] = (int) (random.nextLong() & max);
                arr.set(i, expected[i]);
            }
            for (int i = 0; i < size; i++) {
                assertEquals(expected[i], arr.get(i), "bits=" + bits + " index=" + i);
                assertEquals(expected[i], referenceGet(arr.rawData(), bits, i), "reference bits=" + bits + " index=" + i);
            }
        }
    }

    @Test
    void decodesHandBuiltStraddlingValue() {
        // 5-bit entries: index 12 occupies bits 60..64, i.e. 4 bits in long 0 and 1 bit in long 1.
        long[] data = new long[2];
        int value = 0b10110;
        data[0] |= (long) (value & 0b1111) << 60;
        data[1] |= value >>> 4;
        PackedBitArray arr = new PackedBitArray(5, 20, data);
        assertEquals(value, arr.get(12));
        assertEquals(0, arr.get(11));
        assertEquals(0, arr.get(13));
    }

    @Test
    void bitsForPaletteMatchesLitematica() {
        assertEquals(2, PackedBitArray.bitsForPaletteSize(1));
        assertEquals(2, PackedBitArray.bitsForPaletteSize(2));
        assertEquals(2, PackedBitArray.bitsForPaletteSize(4));
        assertEquals(3, PackedBitArray.bitsForPaletteSize(5));
        assertEquals(4, PackedBitArray.bitsForPaletteSize(16));
        assertEquals(5, PackedBitArray.bitsForPaletteSize(17));
        assertEquals(9, PackedBitArray.bitsForPaletteSize(300));
    }

    @Test
    void rejectsShortBackingArrayAndOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new PackedBitArray(5, 100, new long[2]));
        PackedBitArray arr = new PackedBitArray(3, 10);
        assertThrows(IndexOutOfBoundsException.class, () -> arr.get(10));
        assertThrows(IllegalArgumentException.class, () -> arr.set(0, 8));
    }
}
