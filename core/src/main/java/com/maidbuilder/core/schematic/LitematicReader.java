package com.maidbuilder.core.schematic;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.nbt.NbtCompound;
import com.maidbuilder.core.nbt.NbtIo;
import com.maidbuilder.core.nbt.NbtList;
import com.maidbuilder.core.nbt.NbtType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses Litematica {@code .litematic} files (gzip-compressed NBT).
 * Only the 1.13+ "flattened" formats (Version >= 5) are supported; block states are kept as raw
 * strings and upgraded on the mod side using {@link Schematic#minecraftDataVersion()}.
 */
public final class LitematicReader {
    public static final int MIN_SUPPORTED_VERSION = 5;
    public static final int MAX_SUPPORTED_VERSION = 7;
    /** First data version after the 1.13 flattening that Litematica treats as modern. */
    private static final int DATA_VERSION_1_13_2 = 1631;

    private final long maxVolume;
    private final long maxNbtBytes;

    /**
     * @param maxVolume   upper bound on the total volume of all regions (guards untrusted uploads)
     * @param maxNbtBytes upper bound on the decoded NBT size
     */
    public LitematicReader(long maxVolume, long maxNbtBytes) {
        this.maxVolume = maxVolume;
        this.maxNbtBytes = maxNbtBytes;
    }

    public LitematicReader() {
        this(256L * 256 * 384, 256L * 1024 * 1024);
    }

    public Schematic read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    public Schematic read(InputStream in) throws IOException {
        return read(NbtIo.read(in, maxNbtBytes));
    }

    public Schematic read(NbtCompound root) throws SchematicFormatException {
        if (!root.contains("Version", NbtType.INT)) {
            throw new SchematicFormatException("Not a litematic file: missing 'Version'");
        }
        int version = root.getInt("Version");
        int dataVersion = root.getInt("MinecraftDataVersion");
        if (version < MIN_SUPPORTED_VERSION || (dataVersion > 0 && dataVersion < DATA_VERSION_1_13_2)) {
            throw new SchematicFormatException("Pre-1.13 litematic (Version " + version + ", data version "
                    + dataVersion + ") is not supported; re-save it with a modern Litematica");
        }
        if (version > MAX_SUPPORTED_VERSION) {
            throw new SchematicFormatException("Litematic format Version " + version + " is newer than supported ("
                    + MAX_SUPPORTED_VERSION + ")");
        }

        SchematicMetadata metadata = readMetadata(root.getCompound("Metadata"));
        NbtCompound regionsTag = root.getCompound("Regions");
        List<SchematicRegion> regions = new ArrayList<>();
        long totalVolume = 0;
        for (String regionName : regionsTag.keys()) {
            if (!(regionsTag.get(regionName) instanceof NbtCompound regionTag)) continue;
            SchematicRegion region = readRegion(regionName, regionTag);
            totalVolume += region.volume();
            if (totalVolume > maxVolume) {
                throw new SchematicFormatException("Schematic volume exceeds the limit of " + maxVolume + " blocks");
            }
            regions.add(region);
        }
        return new Schematic(version, root.getInt("SubVersion"), dataVersion, metadata, regions);
    }

    private static SchematicMetadata readMetadata(NbtCompound tag) {
        return new SchematicMetadata(
                tag.getString("Name"),
                tag.getString("Author"),
                tag.getString("Description"),
                readPos(tag.getCompound("EnclosingSize")),
                tag.getInt("TotalBlocks"),
                tag.getInt("TotalVolume"),
                tag.getInt("RegionCount"),
                tag.getLong("TimeCreated"),
                tag.getLong("TimeModified"));
    }

    private SchematicRegion readRegion(String name, NbtCompound tag) throws SchematicFormatException {
        IntPos position = readPos(tag.getCompound("Position"));
        IntPos rawSize = readPos(tag.getCompound("Size"));
        if (rawSize.x() == 0 || rawSize.y() == 0 || rawSize.z() == 0) {
            throw new SchematicFormatException("Region '" + name + "' has a zero size " + rawSize);
        }
        long volume = Math.abs((long) rawSize.x()) * Math.abs((long) rawSize.y()) * Math.abs((long) rawSize.z());
        if (volume > maxVolume) {
            throw new SchematicFormatException("Region '" + name + "' volume " + volume + " exceeds the limit of " + maxVolume);
        }

        List<BlockStateData> palette = readPalette(tag.getList("BlockStatePalette"));
        if (palette.isEmpty()) palette = List.of(BlockStateData.AIR);

        int bits = PackedBitArray.bitsForPaletteSize(palette.size());
        long[] data = tag.getLongArray("BlockStates");
        if (data.length < PackedBitArray.requiredLongs(bits, volume)) {
            throw new SchematicFormatException("Region '" + name + "' BlockStates has " + data.length
                    + " longs, expected " + PackedBitArray.requiredLongs(bits, volume));
        }
        PackedBitArray blocks = new PackedBitArray(bits, volume, data);
        for (long i = 0; i < volume; i++) {
            if (blocks.get(i) >= palette.size()) {
                throw new SchematicFormatException("Region '" + name + "' references palette index "
                        + blocks.get(i) + " but palette has " + palette.size() + " entries");
            }
        }

        Map<IntPos, NbtCompound> blockEntities = new HashMap<>();
        for (Object o : tag.getList("TileEntities")) {
            if (o instanceof NbtCompound te) {
                blockEntities.put(new IntPos(te.getInt("x"), te.getInt("y"), te.getInt("z")), te);
            }
        }
        NbtList entities = tag.getList("Entities");
        return new SchematicRegion(name, position, rawSize, palette, blocks, blockEntities, entities);
    }

    private static List<BlockStateData> readPalette(NbtList list) throws SchematicFormatException {
        List<BlockStateData> palette = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof NbtCompound entry)) {
                throw new SchematicFormatException("BlockStatePalette entry is not a compound");
            }
            String blockName = entry.getString("Name");
            if (blockName.isEmpty()) throw new SchematicFormatException("BlockStatePalette entry without Name");
            Map<String, String> props = new TreeMap<>();
            NbtCompound propsTag = entry.getCompound("Properties");
            for (String key : propsTag.keys()) {
                props.put(key, propsTag.getString(key));
            }
            palette.add(new BlockStateData(blockName, props));
        }
        return palette;
    }

    static IntPos readPos(NbtCompound tag) {
        return new IntPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }
}
