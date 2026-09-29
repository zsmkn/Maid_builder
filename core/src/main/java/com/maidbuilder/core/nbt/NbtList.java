package com.maidbuilder.core.nbt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Minimal homogeneous NBT list. */
public final class NbtList implements Iterable<Object> {
    private byte elementType;
    private final List<Object> values = new ArrayList<>();

    public NbtList(byte elementType) {
        this.elementType = elementType;
    }

    public byte elementType() {
        return elementType;
    }

    public int size() {
        return values.size();
    }

    public Object get(int index) {
        return values.get(index);
    }

    public NbtCompound getCompound(int index) {
        return values.get(index) instanceof NbtCompound c ? c : new NbtCompound();
    }

    public List<Object> asList() {
        return Collections.unmodifiableList(values);
    }

    public NbtList add(Object value) {
        byte type = NbtType.of(value);
        if (values.isEmpty() && (elementType == NbtType.END || elementType == type)) {
            elementType = type;
        } else if (elementType != type) {
            throw new IllegalArgumentException("List of type " + elementType + " cannot hold type " + type);
        }
        values.add(value);
        return this;
    }

    @Override
    public Iterator<Object> iterator() {
        return asList().iterator();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NbtList l) || l.values.size() != values.size()) return false;
        for (int i = 0; i < values.size(); i++) {
            if (!valueEquals(values.get(i), l.values.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return values.size();
    }

    @Override
    public String toString() {
        return values.toString();
    }

    static boolean deepEquals(Map<String, Object> a, Map<String, Object> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<String, Object> e : a.entrySet()) {
            if (!valueEquals(e.getValue(), b.get(e.getKey()))) return false;
        }
        return true;
    }

    static boolean valueEquals(Object a, Object b) {
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y);
        if (a instanceof int[] x && b instanceof int[] y) return Arrays.equals(x, y);
        if (a instanceof long[] x && b instanceof long[] y) return Arrays.equals(x, y);
        return Objects.equals(a, b);
    }
}
