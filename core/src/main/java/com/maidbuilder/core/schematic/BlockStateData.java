package com.maidbuilder.core.schematic;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A block state described purely by strings: a namespaced block id plus property values.
 * This is how states are stored in schematic palettes; conversion to real game states
 * happens on the mod side.
 */
public final class BlockStateData {
    public static final BlockStateData AIR = new BlockStateData("minecraft:air", Map.of());

    private final String name;
    private final Map<String, String> properties;
    private final int hash;

    public BlockStateData(String name, Map<String, String> properties) {
        this.name = normalizeName(Objects.requireNonNull(name, "name"));
        this.properties = Collections.unmodifiableMap(new TreeMap<>(properties));
        this.hash = Objects.hash(this.name, this.properties);
    }

    public static BlockStateData of(String name) {
        return new BlockStateData(name, Map.of());
    }

    /** Parses {@code "minecraft:oak_stairs[facing=east,half=top]"}. */
    public static BlockStateData parse(String text) {
        String s = text.trim();
        int open = s.indexOf('[');
        if (open < 0) return of(s);
        if (!s.endsWith("]")) throw new IllegalArgumentException("Malformed block state: " + text);
        Map<String, String> props = new TreeMap<>();
        String body = s.substring(open + 1, s.length() - 1);
        if (!body.isBlank()) {
            for (String pair : body.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("Malformed property '" + pair + "' in " + text);
                props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return new BlockStateData(s.substring(0, open), props);
    }

    private static String normalizeName(String name) {
        return name.indexOf(':') < 0 ? "minecraft:" + name : name;
    }

    public String name() {
        return name;
    }

    /** Block id without namespace, e.g. {@code oak_stairs}. */
    public String path() {
        return name.substring(name.indexOf(':') + 1);
    }

    public Map<String, String> properties() {
        return properties;
    }

    public String get(String property) {
        return properties.get(property);
    }

    public String getOrDefault(String property, String fallback) {
        return properties.getOrDefault(property, fallback);
    }

    public boolean has(String property) {
        return properties.containsKey(property);
    }

    public BlockStateData with(String property, String value) {
        if (Objects.equals(properties.get(property), value)) return this;
        Map<String, String> copy = new TreeMap<>(properties);
        copy.put(property, value);
        return new BlockStateData(name, copy);
    }

    public BlockStateData withProperties(Map<String, String> newProperties) {
        return new BlockStateData(name, newProperties);
    }

    public boolean isAir() {
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air") || name.equals("minecraft:void_air");
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof BlockStateData b && hash == b.hash && name.equals(b.name) && properties.equals(b.properties));
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
