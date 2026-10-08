package com.maidbuilder.core.schematic;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.nbt.NbtCompound;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LitematicReaderTest {
    private static final int DATA_VERSION_1_21_1 = 3955;

    private static Schematic roundTrip(Schematic schematic) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LitematicWriter.write(schematic, out);
        return new LitematicReader().read(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    void readsSingleRegionWithWidePalette() throws IOException {
        // 20 distinct states -> 5 bits per entry, so many entries straddle long boundaries.
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("main", IntPos.ZERO, new IntPos(5, 4, 3));
        String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
                "cyan", "purple", "blue", "brown", "green", "red", "black"};
        int i = 0;
        for (int y = 0; y < 4; y++)
            for (int z = 0; z < 3; z++)
                for (int x = 0; x < 5; x++, i++)
                    if (i % 7 != 0) rb.set(x, y, z, "minecraft:" + colors[i % colors.length] + "_wool");
        rb.set(1, 0, 0, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        rb.set(2, 0, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
        rb.set(2, 1, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        rb.set(3, 0, 0, "minecraft:red_bed[facing=south,occupied=false,part=foot]");
        Schematic original = LitematicWriter.schematic("house", DATA_VERSION_1_21_1, List.of(rb.build()));

        Schematic read = roundTrip(original);
        SchematicRegion region = read.regions().get(0);
        assertTrue(region.palette().size() > 16, "palette should need 5 bits");
        assertEquals(original.metadata().totalBlocks(), read.countNonAir());
        assertEquals(read.metadata().totalBlocks(), read.countNonAir());
        assertEquals(new IntPos(5, 4, 3), read.metadata().enclosingSize());
        assertEquals(DATA_VERSION_1_21_1, read.minecraftDataVersion());
        SchematicRegion expected = original.regions().get(0);
        for (int y = 0; y < 4; y++)
            for (int z = 0; z < 3; z++)
                for (int x = 0; x < 5; x++)
                    assertEquals(expected.get(x, y, z), region.get(x, y, z), "at " + x + "," + y + "," + z);
        assertEquals("east", region.get(1, 0, 0).get("facing"));
        assertEquals("upper", region.get(2, 1, 0).get("half"));
    }

    @Test
    void handlesNegativeSizesAndMultipleRegions() throws IOException {
        // Region extending towards negative x/z from position (0,0,0): covers x in [-2,0], z in [-1,0].
        LitematicWriter.RegionBuilder neg = new LitematicWriter.RegionBuilder("neg", IntPos.ZERO, new IntPos(-3, 2, -2));
        neg.set(0, 0, 0, "minecraft:stone");      // world-relative (-2, 0, -1)
        neg.set(2, 1, 1, "minecraft:glass");      // world-relative (0, 1, 0)
        LitematicWriter.RegionBuilder pos = new LitematicWriter.RegionBuilder("pos", new IntPos(5, 0, 5), new IntPos(2, 1, 1));
        pos.set(0, 0, 0, "minecraft:dirt");
        pos.set(1, 0, 0, "minecraft:dirt");
        Schematic read = roundTrip(LitematicWriter.schematic("multi", DATA_VERSION_1_21_1, List.of(neg.build(), pos.build())));

        assertEquals(2, read.regions().size());
        SchematicRegion n = read.regions().stream().filter(r -> r.name().equals("neg")).findFirst().orElseThrow();
        assertEquals(new IntPos(-3, 2, -2), n.rawSize());
        assertEquals(new IntPos(3, 2, 2), n.size());
        assertEquals(new IntPos(-2, 0, -1), n.minCorner());
        assertEquals(new IntPos(0, 1, 0), n.maxCorner());
        assertEquals(new IntPos(-2, 0, -1), read.minCorner());
        assertEquals(new IntPos(6, 1, 5), read.maxCorner());
        assertEquals(new IntPos(9, 2, 7), read.metadata().enclosingSize());
        assertEquals(4, read.countNonAir());

        StringBuilder seen = new StringBuilder();
        n.forEachBlock((p, s, local) -> seen.append(s.path()).append(p));
        assertEquals("stone(-2, 0, -1)glass(0, 1, 0)", seen.toString());
    }

    @Test
    void keepsBlockEntities() throws IOException {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("r", IntPos.ZERO, new IntPos(1, 1, 1));
        rb.set(0, 0, 0, "minecraft:oak_sign[rotation=4,waterlogged=false]");
        rb.blockEntity(0, 0, 0, new NbtCompound().put("id", "minecraft:sign"));
        Schematic read = roundTrip(LitematicWriter.schematic("sign", DATA_VERSION_1_21_1, List.of(rb.build())));
        NbtCompound be = read.regions().get(0).blockEntities().get(IntPos.ZERO);
        assertEquals("minecraft:sign", be.getString("id"));
    }

    @Test
    void rejectsLegacyAndTruncatedFiles() {
        NbtCompound legacy = new NbtCompound().put("Version", 4).put("MinecraftDataVersion", 1343);
        assertThrows(SchematicFormatException.class, () -> new LitematicReader().read(legacy));
        assertThrows(SchematicFormatException.class, () -> new LitematicReader().read(new NbtCompound()));

        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("r", IntPos.ZERO, new IntPos(4, 4, 4));
        rb.set(0, 0, 0, "minecraft:stone");
        NbtCompound root = LitematicWriter.toNbt(LitematicWriter.schematic("t", DATA_VERSION_1_21_1, List.of(rb.build())));
        root.getCompound("Regions").getCompound("r").put("BlockStates", new long[1]);
        assertThrows(SchematicFormatException.class, () -> new LitematicReader().read(root));
    }

    @Test
    void rejectsOversizedRegions() {
        NbtCompound root = new NbtCompound().put("Version", 6).put("MinecraftDataVersion", DATA_VERSION_1_21_1);
        NbtCompound region = new NbtCompound()
                .put("Position", new NbtCompound().put("x", 0).put("y", 0).put("z", 0))
                .put("Size", new NbtCompound().put("x", 10_000).put("y", 384).put("z", 10_000));
        root.put("Regions", new NbtCompound().put("huge", region));
        assertThrows(SchematicFormatException.class, () -> new LitematicReader().read(root));
    }

    /**
     * Regression tests against real files: drop .litematic files into {@code local-schematics/}
     * (git-ignored) and each is checked against its own Metadata.TotalBlocks.
     */
    @TestFactory
    Stream<DynamicTest> realSchematicsMatchTheirMetadata() throws IOException {
        Path dir = Path.of(System.getProperty("maidbuilder.testSchematics", "local-schematics"));
        if (!Files.isDirectory(dir)) return Stream.empty();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".litematic")).sorted().toList();
        }
        return files.stream().map(file -> DynamicTest.dynamicTest(file.getFileName().toString(), () -> {
            Schematic schematic = new LitematicReader().read(file);
            assertEquals(schematic.metadata().totalBlocks(), schematic.countNonAir(), "TotalBlocks");
            assertEquals(schematic.metadata().regionCount(), schematic.regions().size(), "RegionCount");
            IntPos enclosing = schematic.maxCorner().subtract(schematic.minCorner()).add(1, 1, 1);
            assertEquals(schematic.metadata().enclosingSize(), enclosing, "EnclosingSize");
        }));
    }
}
