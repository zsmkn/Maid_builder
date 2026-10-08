package com.maidbuilder.core.schematic;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.nbt.NbtCompound;
import com.maidbuilder.core.nbt.NbtIo;
import com.maidbuilder.core.nbt.NbtList;
import com.maidbuilder.core.nbt.NbtType;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and writes {@code .litematic} files: test fixtures and blueprints captured in game.
 */
public final class LitematicWriter {
    private LitematicWriter() {
    }

    public static void write(Schematic schematic, OutputStream out) throws IOException {
        NbtIo.writeCompressed(toNbt(schematic), out);
    }

    public static NbtCompound toNbt(Schematic schematic) {
        NbtCompound root = new NbtCompound();
        root.put("MinecraftDataVersion", schematic.minecraftDataVersion());
        root.put("Version", schematic.formatVersion());
        root.put("SubVersion", schematic.formatSubVersion());

        SchematicMetadata m = schematic.metadata();
        NbtCompound meta = new NbtCompound();
        meta.put("Name", m.name());
        meta.put("Author", m.author());
        meta.put("Description", m.description());
        meta.put("EnclosingSize", posTag(m.enclosingSize()));
        meta.put("TotalBlocks", m.totalBlocks());
        meta.put("TotalVolume", m.totalVolume());
        meta.put("RegionCount", m.regionCount());
        meta.put("TimeCreated", m.timeCreated());
        meta.put("TimeModified", m.timeModified());
        root.put("Metadata", meta);

        NbtCompound regions = new NbtCompound();
        for (SchematicRegion r : schematic.regions()) {
            regions.put(r.name(), regionTag(r));
        }
        root.put("Regions", regions);
        return root;
    }

    private static NbtCompound regionTag(SchematicRegion r) {
        NbtCompound tag = new NbtCompound();
        tag.put("Position", posTag(r.position()));
        tag.put("Size", posTag(r.rawSize()));

        NbtList palette = new NbtList(NbtType.COMPOUND);
        for (BlockStateData s : r.palette()) {
            NbtCompound entry = new NbtCompound().put("Name", s.name());
            if (!s.properties().isEmpty()) {
                NbtCompound props = new NbtCompound();
                s.properties().forEach(props::put);
                entry.put("Properties", props);
            }
            palette.add(entry);
        }
        tag.put("BlockStatePalette", palette);

        int bits = PackedBitArray.bitsForPaletteSize(r.palette().size());
        PackedBitArray arr = new PackedBitArray(bits, r.volume());
        IntPos size = r.size();
        for (int y = 0; y < size.y(); y++)
            for (int z = 0; z < size.z(); z++)
                for (int x = 0; x < size.x(); x++)
                    arr.set(r.index(x, y, z), r.palette().indexOf(r.get(x, y, z)));
        tag.put("BlockStates", arr.rawData());

        NbtList tiles = new NbtList(NbtType.COMPOUND);
        r.blockEntities().values().forEach(tiles::add);
        tag.put("TileEntities", tiles);
        tag.put("Entities", r.entities());
        tag.put("PendingBlockTicks", new NbtList(NbtType.COMPOUND));
        tag.put("PendingFluidTicks", new NbtList(NbtType.COMPOUND));
        return tag;
    }

    private static NbtCompound posTag(IntPos p) {
        return new NbtCompound().put("x", p.x()).put("y", p.y()).put("z", p.z());
    }

    /** Mutable builder for a single region, addressed by region-local coordinates. */
    public static final class RegionBuilder {
        private final String name;
        private final IntPos position;
        private final IntPos rawSize;
        private final IntPos size;
        private final List<BlockStateData> palette = new ArrayList<>(List.of(BlockStateData.AIR));
        private final Map<BlockStateData, Integer> ids = new HashMap<>(Map.of(BlockStateData.AIR, 0));
        private final int[] cells;
        private final Map<IntPos, NbtCompound> blockEntities = new HashMap<>();

        public RegionBuilder(String name, IntPos position, IntPos rawSize) {
            this.name = name;
            this.position = position;
            this.rawSize = rawSize;
            this.size = new IntPos(Math.abs(rawSize.x()), Math.abs(rawSize.y()), Math.abs(rawSize.z()));
            this.cells = new int[size.x() * size.y() * size.z()];
        }

        public RegionBuilder set(int x, int y, int z, BlockStateData state) {
            int id = ids.computeIfAbsent(state, s -> {
                palette.add(s);
                return palette.size() - 1;
            });
            cells[(y * size.z() + z) * size.x() + x] = id;
            return this;
        }

        public RegionBuilder set(int x, int y, int z, String state) {
            return set(x, y, z, BlockStateData.parse(state));
        }

        public RegionBuilder blockEntity(int x, int y, int z, NbtCompound data) {
            blockEntities.put(new IntPos(x, y, z), data.put("x", x).put("y", y).put("z", z));
            return this;
        }

        public SchematicRegion build() {
            int bits = PackedBitArray.bitsForPaletteSize(palette.size());
            PackedBitArray arr = new PackedBitArray(bits, cells.length);
            for (int i = 0; i < cells.length; i++) arr.set(i, cells[i]);
            return new SchematicRegion(name, position, rawSize, palette, arr, blockEntities, new NbtList(NbtType.COMPOUND));
        }
    }

    /** Wraps regions into a schematic with metadata computed the way Litematica does. */
    public static Schematic schematic(String name, int dataVersion, List<SchematicRegion> regions) {
        return schematic(name, "", 0, dataVersion, regions);
    }

    /**
     * Same, with an author and a creation time (epoch millis) as Litematica records them.
     */
    public static Schematic schematic(String name, String author, long timeMillis, int dataVersion, List<SchematicRegion> regions) {
        Schematic tmp = new Schematic(6, 1, dataVersion,
                new SchematicMetadata(name, author, "", IntPos.ZERO, 0, 0, 0, 0, 0), regions);
        IntPos min = tmp.minCorner(), max = tmp.maxCorner();
        IntPos enclosing = max.subtract(min).add(1, 1, 1);
        long volume = 0;
        for (SchematicRegion r : regions) volume += r.volume();
        return new Schematic(6, 1, dataVersion, new SchematicMetadata(name, author, "", enclosing,
                (int) tmp.countNonAir(), (int) volume, regions.size(), timeMillis, timeMillis), regions);
    }
}
