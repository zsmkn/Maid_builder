package com.maidbuilder.core.nbt;

/** NBT tag type ids as defined by the Minecraft NBT format. */
public final class NbtType {
    public static final byte END = 0;
    public static final byte BYTE = 1;
    public static final byte SHORT = 2;
    public static final byte INT = 3;
    public static final byte LONG = 4;
    public static final byte FLOAT = 5;
    public static final byte DOUBLE = 6;
    public static final byte BYTE_ARRAY = 7;
    public static final byte STRING = 8;
    public static final byte LIST = 9;
    public static final byte COMPOUND = 10;
    public static final byte INT_ARRAY = 11;
    public static final byte LONG_ARRAY = 12;

    private NbtType() {
    }

    /** Returns the tag id for a value held by {@link NbtCompound} / {@link NbtList}, or throws. */
    public static byte of(Object value) {
        if (value instanceof Byte) return BYTE;
        if (value instanceof Short) return SHORT;
        if (value instanceof Integer) return INT;
        if (value instanceof Long) return LONG;
        if (value instanceof Float) return FLOAT;
        if (value instanceof Double) return DOUBLE;
        if (value instanceof byte[]) return BYTE_ARRAY;
        if (value instanceof String) return STRING;
        if (value instanceof NbtList) return LIST;
        if (value instanceof NbtCompound) return COMPOUND;
        if (value instanceof int[]) return INT_ARRAY;
        if (value instanceof long[]) return LONG_ARRAY;
        throw new IllegalArgumentException("Not an NBT value: " + (value == null ? "null" : value.getClass()));
    }
}
