package com.maidbuilder.core.schematic;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.nbt.NbtCompound;
import com.maidbuilder.core.nbt.NbtList;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One sub-region of a schematic: a box of block states addressed by local coordinates
 * {@code 0 <= x < sizeX} etc., placed at {@link #minCorner()} relative to the schematic origin.
 */
public final class SchematicRegion {
    private final String name;
    private final IntPos position;
    private final IntPos rawSize;
    private final IntPos size;
    private final IntPos minCorner;
    private final List<BlockStateData> palette;
    private final PackedBitArray blocks;
    private final Map<IntPos, NbtCompound> blockEntities;
    private final NbtList entities;
    /** Palette index of positions the file leaves unspecified (structure void); -1 if every cell is specified. */
    private final int voidId;

    public SchematicRegion(String name, IntPos position, IntPos rawSize, List<BlockStateData> palette,
                           PackedBitArray blocks, Map<IntPos, NbtCompound> blockEntities, NbtList entities) {
        this(name, position, rawSize, palette, blocks, blockEntities, entities, -1);
    }

    public SchematicRegion(String name, IntPos position, IntPos rawSize, List<BlockStateData> palette,
                           PackedBitArray blocks, Map<IntPos, NbtCompound> blockEntities, NbtList entities, int voidId) {
        this.name = name;
        this.position = position;
        this.rawSize = rawSize;
        this.size = new IntPos(Math.abs(rawSize.x()), Math.abs(rawSize.y()), Math.abs(rawSize.z()));
        this.minCorner = minCornerOf(position, rawSize);
        this.palette = List.copyOf(palette);
        this.blocks = blocks;
        this.blockEntities = Collections.unmodifiableMap(blockEntities);
        this.entities = entities;
        this.voidId = voidId;
        if (blocks.size() != volume()) {
            throw new IllegalArgumentException("Block array size " + blocks.size() + " does not match region volume " + volume());
        }
    }

    /**
     * A region's size may be negative on any axis, meaning it extends from {@code position}
     * towards negative coordinates. Returns the relative minimum corner.
     */
    public static IntPos minCornerOf(IntPos position, IntPos rawSize) {
        return new IntPos(
                position.x() + (rawSize.x() < 0 ? rawSize.x() + 1 : 0),
                position.y() + (rawSize.y() < 0 ? rawSize.y() + 1 : 0),
                position.z() + (rawSize.z() < 0 ? rawSize.z() + 1 : 0));
    }

    public String name() {
        return name;
    }

    /** Position as stored in the file (relative to schematic origin). */
    public IntPos position() {
        return position;
    }

    /** Size as stored in the file; components may be negative. */
    public IntPos rawSize() {
        return rawSize;
    }

    /** Absolute size. */
    public IntPos size() {
        return size;
    }

    public IntPos minCorner() {
        return minCorner;
    }

    public IntPos maxCorner() {
        return minCorner.add(size.x() - 1, size.y() - 1, size.z() - 1);
    }

    public long volume() {
        return (long) size.x() * size.y() * size.z();
    }

    public List<BlockStateData> palette() {
        return palette;
    }

    /** Block entity NBT keyed by region-local position (the x/y/z keys are kept in the compound). */
    public Map<IntPos, NbtCompound> blockEntities() {
        return blockEntities;
    }

    public NbtList entities() {
        return entities;
    }

    public long index(int x, int y, int z) {
        return ((long) y * size.z() + z) * size.x() + x;
    }

    public BlockStateData get(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= size.x() || y >= size.y() || z >= size.z()) {
            throw new IndexOutOfBoundsException("(" + x + "," + y + "," + z + ") outside region size " + size);
        }
        return palette.get(blocks.get(index(x, y, z)));
    }

    public long countNonAir() {
        boolean[] airIds = new boolean[palette.size()];
        for (int i = 0; i < airIds.length; i++) airIds[i] = palette.get(i).isAir();
        long count = 0;
        long volume = volume();
        for (long i = 0; i < volume; i++) {
            if (!airIds[blocks.get(i)]) count++;
        }
        return count;
    }

    /** Visits every non-air block with its position relative to the schematic origin, in y/z/x order. */
    public void forEachBlock(BlockVisitor visitor) {
        boolean[] airIds = new boolean[palette.size()];
        for (int i = 0; i < airIds.length; i++) airIds[i] = palette.get(i).isAir();
        int sx = size.x(), sy = size.y(), sz = size.z();
        long i = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++, i++) {
                    int id = blocks.get(i);
                    if (!airIds[id]) {
                        visitor.accept(minCorner.add(x, y, z), palette.get(id), new IntPos(x, y, z));
                    }
                }
            }
        }
    }

    /**
     * Visits every cell the file explicitly stores as air (not structure void), with its position
     * relative to the schematic origin, in y/z/x order.
     */
    public void forEachAir(AirVisitor visitor) {
        boolean[] airIds = new boolean[palette.size()];
        for (int i = 0; i < airIds.length; i++) airIds[i] = i != voidId && palette.get(i).isAir();
        int sx = size.x(), sy = size.y(), sz = size.z();
        long i = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++, i++) {
                    if (airIds[blocks.get(i)]) visitor.accept(minCorner.add(x, y, z));
                }
            }
        }
    }

    @FunctionalInterface
    public interface AirVisitor {
        void accept(IntPos schematicPos);
    }

    @FunctionalInterface
    public interface BlockVisitor {
        void accept(IntPos schematicPos, BlockStateData state, IntPos localPos);
    }
}
