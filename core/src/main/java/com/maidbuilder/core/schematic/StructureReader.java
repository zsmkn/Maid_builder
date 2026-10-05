package com.maidbuilder.core.schematic;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.nbt.NbtCompound;
import com.maidbuilder.core.nbt.NbtList;
import com.maidbuilder.core.nbt.NbtType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses vanilla structure files ({@code .nbt}, as saved by the structure block): a size, a
 * palette and a sparse list of blocks. Positions the file does not list (structure void) become
 * air, which the builder never places, so they leave the world as it is. Files with several
 * palettes (random variants, e.g. shipwrecks) use the first one.
 */
public final class StructureReader {
    /** First data version after the 1.13 flattening that the mod accepts (same as for litematics). */
    private static final int MIN_DATA_VERSION = 1631;

    private final long maxVolume;

    public StructureReader(long maxVolume) {
        this.maxVolume = maxVolume;
    }

    /** Whether the compound looks like a vanilla structure file. */
    public static boolean isStructure(NbtCompound root) {
        return root.contains("size", NbtType.LIST) && root.contains("blocks", NbtType.LIST)
                && (root.contains("palette", NbtType.LIST) || root.contains("palettes", NbtType.LIST));
    }

    public Schematic read(NbtCompound root) throws SchematicFormatException {
        if (!isStructure(root)) throw new SchematicFormatException("Not a structure file: missing 'size', 'blocks' or 'palette'");
        int dataVersion = root.getInt("DataVersion");
        if (dataVersion < MIN_DATA_VERSION) {
            throw new SchematicFormatException("Pre-1.13 structure file (data version " + dataVersion
                    + ") is not supported; load and save it again with a structure block in a modern version");
        }

        IntPos size = readIntList(root.getList("size"), "size");
        if (size.x() <= 0 || size.y() <= 0 || size.z() <= 0) {
            throw new SchematicFormatException("Structure has an empty size " + size);
        }
        long volume = (long) size.x() * size.y() * size.z();
        if (volume > maxVolume) {
            throw new SchematicFormatException("Structure volume " + volume + " exceeds the limit of " + maxVolume);
        }

        NbtList paletteTag = root.contains("palette", NbtType.LIST) ? root.getList("palette") : firstPalette(root.getList("palettes"));
        // Index 0 is reserved for the unlisted positions.
        List<BlockStateData> palette = new ArrayList<>(paletteTag.size() + 1);
        palette.add(BlockStateData.AIR);
        for (Object o : paletteTag) {
            if (!(o instanceof NbtCompound entry)) throw new SchematicFormatException("Palette entry is not a compound");
            palette.add(readState(entry));
        }

        PackedBitArray blocks = new PackedBitArray(PackedBitArray.bitsForPaletteSize(palette.size()), volume);
        Map<IntPos, NbtCompound> blockEntities = new HashMap<>();
        for (Object o : root.getList("blocks")) {
            if (!(o instanceof NbtCompound block)) throw new SchematicFormatException("Block entry is not a compound");
            IntPos pos = readIntList(block.getList("pos"), "pos");
            if (pos.x() < 0 || pos.y() < 0 || pos.z() < 0 || pos.x() >= size.x() || pos.y() >= size.y() || pos.z() >= size.z()) {
                throw new SchematicFormatException("Block at " + pos + " is outside the structure size " + size);
            }
            int state = block.getInt("state");
            if (state < 0 || state + 1 >= palette.size()) {
                throw new SchematicFormatException("Block at " + pos + " references palette index " + state
                        + " but the palette has " + (palette.size() - 1) + " entries");
            }
            blocks.set(((long) pos.y() * size.z() + pos.z()) * size.x() + pos.x(), state + 1);
            if (block.contains("nbt", NbtType.COMPOUND)) {
                // Same layout as litematic tile entities: the local position is kept in the compound.
                NbtCompound nbt = new NbtCompound();
                block.getCompound("nbt").asMap().forEach(nbt::put);
                nbt.put("x", pos.x()).put("y", pos.y()).put("z", pos.z());
                blockEntities.put(pos, nbt);
            }
        }

        SchematicRegion region = new SchematicRegion("structure", IntPos.ZERO, size, palette, blocks, blockEntities,
                new NbtList(NbtType.COMPOUND));
        int nonAir = (int) Math.min(Integer.MAX_VALUE, region.countNonAir());
        SchematicMetadata metadata = new SchematicMetadata("", root.getString("author"), "", size, nonAir,
                (int) Math.min(Integer.MAX_VALUE, volume), 1, 0, 0);
        return new Schematic(0, 0, dataVersion, metadata, List.of(region));
    }

    private static NbtList firstPalette(NbtList palettes) throws SchematicFormatException {
        if (palettes.size() == 0 || !(palettes.get(0) instanceof NbtList first)) {
            throw new SchematicFormatException("Structure has no palette");
        }
        return first;
    }

    private static BlockStateData readState(NbtCompound entry) throws SchematicFormatException {
        String name = entry.getString("Name");
        if (name.isEmpty()) throw new SchematicFormatException("Palette entry without Name");
        Map<String, String> props = new TreeMap<>();
        NbtCompound propsTag = entry.getCompound("Properties");
        for (String key : propsTag.keys()) props.put(key, propsTag.getString(key));
        return new BlockStateData(name, props);
    }

    private static IntPos readIntList(NbtList list, String what) throws SchematicFormatException {
        if (list.size() != 3 || !(list.get(0) instanceof Integer x) || !(list.get(1) instanceof Integer y)
                || !(list.get(2) instanceof Integer z)) {
            throw new SchematicFormatException("'" + what + "' is not a list of three integers");
        }
        return new IntPos(x, y, z);
    }
}
