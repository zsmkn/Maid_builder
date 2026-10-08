package com.maidbuilder.core.schematic;

/**
 * Fixed-width unsigned integers packed densely into a long array, where a value may
 * straddle two longs (the Litematica layout; vanilla chunk storage never straddles).
 * Bits are filled from the least significant end of each long.
 */
public final class PackedBitArray {
    private final long[] data;
    private final int bits;
    private final long size;
    private final long mask;

    public PackedBitArray(int bits, long size) {
        this(bits, size, new long[requiredLongs(bits, size)]);
    }

    public PackedBitArray(int bits, long size, long[] data) {
        if (bits < 1 || bits > 32) throw new IllegalArgumentException("bits must be in 1..32, got " + bits);
        if (size < 0) throw new IllegalArgumentException("negative size");
        if (data.length < requiredLongs(bits, size)) {
            throw new IllegalArgumentException("Backing array too short: need " + requiredLongs(bits, size) + " longs, got " + data.length);
        }
        this.bits = bits;
        this.size = size;
        this.data = data;
        this.mask = (1L << bits) - 1L;
    }

    public static int requiredLongs(int bits, long size) {
        return (int) ((size * bits + 63L) / 64L);
    }

    /** Minimum bits for a palette of the given size, as Litematica computes it (never below 2). */
    public static int bitsForPaletteSize(int paletteSize) {
        return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(paletteSize, 1) - 1));
    }

    public int get(long index) {
        checkIndex(index);
        long bitPos = index * bits;
        int word = (int) (bitPos >>> 6);
        int offset = (int) (bitPos & 63);
        long value = data[word] >>> offset;
        int bitsInFirst = 64 - offset;
        if (bitsInFirst < bits) {
            value |= data[word + 1] << bitsInFirst;
        }
        return (int) (value & mask);
    }

    public void set(long index, int value) {
        checkIndex(index);
        long v = value & 0xFFFFFFFFL;
        if ((v & ~mask) != 0) throw new IllegalArgumentException("Value " + value + " does not fit in " + bits + " bits");
        long bitPos = index * bits;
        int word = (int) (bitPos >>> 6);
        int offset = (int) (bitPos & 63);
        data[word] = (data[word] & ~(mask << offset)) | (v << offset);
        int bitsInFirst = 64 - offset;
        if (bitsInFirst < bits) {
            int remaining = bits - bitsInFirst;
            long highMask = (1L << remaining) - 1L;
            data[word + 1] = (data[word + 1] & ~highMask) | (v >>> bitsInFirst);
        }
    }

    private void checkIndex(long index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index " + index + " out of 0.." + (size - 1));
    }

    public int bits() {
        return bits;
    }

    public long size() {
        return size;
    }

    public long[] rawData() {
        return data;
    }
}
