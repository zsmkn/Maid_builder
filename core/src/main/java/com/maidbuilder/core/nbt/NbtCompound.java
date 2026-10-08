package com.maidbuilder.core.nbt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Minimal NBT compound. Values are plain Java objects: boxed primitives, String,
 * byte[] / int[] / long[], {@link NbtList} and {@link NbtCompound}.
 * Getters are lenient like vanilla: a missing key or wrong type yields a default value.
 */
public final class NbtCompound {
    private final Map<String, Object> values = new LinkedHashMap<>();

    public Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public int size() {
        return values.size();
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public boolean contains(String key, byte type) {
        Object v = values.get(key);
        return v != null && NbtType.of(v) == type;
    }

    public Object get(String key) {
        return values.get(key);
    }

    public NbtCompound put(String key, Object value) {
        NbtType.of(value); // validates the value type
        values.put(key, value);
        return this;
    }

    public int getInt(String key) {
        return values.get(key) instanceof Number n && !(n instanceof Float || n instanceof Double) ? n.intValue() : 0;
    }

    public long getLong(String key) {
        return values.get(key) instanceof Number n && !(n instanceof Float || n instanceof Double) ? n.longValue() : 0L;
    }

    public String getString(String key) {
        return values.get(key) instanceof String s ? s : "";
    }

    public long[] getLongArray(String key) {
        return values.get(key) instanceof long[] a ? a : new long[0];
    }

    public NbtCompound getCompound(String key) {
        return values.get(key) instanceof NbtCompound c ? c : new NbtCompound();
    }

    public NbtList getList(String key) {
        return values.get(key) instanceof NbtList l ? l : new NbtList(NbtType.END);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NbtCompound c && NbtList.deepEquals(values, c.values);
    }

    @Override
    public int hashCode() {
        return values.keySet().hashCode();
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
