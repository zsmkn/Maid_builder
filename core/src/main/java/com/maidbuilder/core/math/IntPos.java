package com.maidbuilder.core.math;

/**
 * Immutable integer block position. Core-side replacement for {@code BlockPos}.
 */
public record IntPos(int x, int y, int z) implements Comparable<IntPos> {
    public static final IntPos ZERO = new IntPos(0, 0, 0);

    public IntPos add(int dx, int dy, int dz) {
        return new IntPos(x + dx, y + dy, z + dz);
    }

    public IntPos add(IntPos o) {
        return add(o.x, o.y, o.z);
    }

    public IntPos subtract(IntPos o) {
        return new IntPos(x - o.x, y - o.y, z - o.z);
    }

    public long distSqr(IntPos o) {
        long dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public static IntPos min(IntPos a, IntPos b) {
        return new IntPos(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
    }

    public static IntPos max(IntPos a, IntPos b) {
        return new IntPos(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
    }

    /** Packs into a long using the same bit layout as vanilla {@code BlockPos.asLong}. */
    public long asLong() {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static IntPos fromLong(long packed) {
        int x = (int) (packed >> 38);
        int y = (int) (packed << 52 >> 52);
        int z = (int) (packed << 26 >> 38);
        return new IntPos(x, y, z);
    }

    /** Orders bottom-to-top, then by z, then by x. */
    @Override
    public int compareTo(IntPos o) {
        if (y != o.y) return Integer.compare(y, o.y);
        if (z != o.z) return Integer.compare(z, o.z);
        return Integer.compare(x, o.x);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
