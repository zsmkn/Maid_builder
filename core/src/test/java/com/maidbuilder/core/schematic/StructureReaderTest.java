package com.maidbuilder.core.schematic;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.nbt.NbtCompound;
import com.maidbuilder.core.nbt.NbtIo;
import com.maidbuilder.core.nbt.NbtList;
import com.maidbuilder.core.nbt.NbtType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureReaderTest {
    private static final int DATA_VERSION_1_21_1 = 3955;
    private static final SchematicReader READER = new SchematicReader(1_000_000, 64L * 1024 * 1024);

    private static NbtList ints(int... values) {
        NbtList list = new NbtList(NbtType.INT);
        for (int v : values) list.add(v);
        return list;
    }

    private static NbtCompound state(String name, Map<String, String> props) {
        NbtCompound tag = new NbtCompound().put("Name", name);
        if (!props.isEmpty()) {
            NbtCompound p = new NbtCompound();
            props.forEach(p::put);
            tag.put("Properties", p);
        }
        return tag;
    }

    private static NbtCompound block(int state, int x, int y, int z) {
        return new NbtCompound().put("state", state).put("pos", ints(x, y, z));
    }

    /** 3x2x2: stone floor row, a door, a chest with contents, one explicit air and unlisted (void) positions. */
    private static NbtCompound house() {
        NbtList palette = new NbtList(NbtType.COMPOUND)
                .add(state("minecraft:stone", Map.of()))
                .add(state("minecraft:oak_door", Map.of("facing", "north", "half", "lower", "hinge", "left", "open", "false", "powered", "false")))
                .add(state("minecraft:oak_door", Map.of("facing", "north", "half", "upper", "hinge", "left", "open", "false", "powered", "false")))
                .add(state("minecraft:chest", Map.of("facing", "west", "type", "single", "waterlogged", "false")))
                .add(state("minecraft:air", Map.of()));
        NbtCompound chestNbt = new NbtCompound().put("id", "minecraft:chest").put("Items", new NbtList(NbtType.COMPOUND));
        NbtList blocks = new NbtList(NbtType.COMPOUND)
                .add(block(0, 0, 0, 0)).add(block(0, 1, 0, 0)).add(block(0, 2, 0, 0))
                .add(block(1, 1, 0, 1)).add(block(2, 1, 1, 1))
                .add(block(3, 2, 0, 1).put("nbt", chestNbt))
                .add(block(4, 0, 1, 0));
        return new NbtCompound()
                .put("DataVersion", DATA_VERSION_1_21_1)
                .put("size", ints(3, 2, 2))
                .put("palette", palette)
                .put("blocks", blocks)
                .put("entities", new NbtList(NbtType.COMPOUND));
    }

    private static Schematic readCompressed(NbtCompound root) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, out);
        return READER.read(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    void readsBlocksPaletteAndBlockEntities() throws IOException {
        Schematic schematic = readCompressed(house());
        assertEquals(DATA_VERSION_1_21_1, schematic.minecraftDataVersion());
        assertEquals(1, schematic.regions().size());
        SchematicRegion region = schematic.regions().getFirst();
        assertEquals(new IntPos(3, 2, 2), region.size());
        assertEquals(IntPos.ZERO, schematic.minCorner());
        assertEquals("minecraft:stone", region.get(2, 0, 0).name());
        assertEquals("lower", region.get(1, 0, 1).get("half"));
        assertEquals("upper", region.get(1, 1, 1).get("half"));
        assertEquals("west", region.get(2, 0, 1).get("facing"));
        assertTrue(region.get(0, 1, 0).isAir(), "explicit air");
        assertTrue(region.get(0, 0, 1).isAir(), "unlisted position");
        assertEquals(6, schematic.countNonAir());
        NbtCompound chest = region.blockEntities().get(new IntPos(2, 0, 1));
        assertEquals("minecraft:chest", chest.getString("id"));
        assertEquals(2, chest.getInt("x"));
    }

    @Test
    void usesFirstOfSeveralPalettes() throws IOException {
        NbtCompound root = house();
        NbtList first = root.getList("palette");
        NbtList second = new NbtList(NbtType.COMPOUND);
        for (int i = 0; i < first.size(); i++) second.add(state("minecraft:dirt", Map.of()));
        NbtCompound variant = new NbtCompound();
        root.asMap().forEach((k, v) -> {
            if (!k.equals("palette")) variant.put(k, v);
        });
        variant.put("palettes", new NbtList(NbtType.LIST).add(first).add(second));
        SchematicRegion region = readCompressed(variant).regions().getFirst();
        assertEquals("minecraft:stone", region.get(0, 0, 0).name());
    }

    @Test
    void detectsLitematicToo() throws IOException {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("main", IntPos.ZERO, new IntPos(1, 1, 1));
        rb.set(0, 0, 0, "minecraft:stone");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LitematicWriter.write(LitematicWriter.schematic("one", DATA_VERSION_1_21_1, List.of(rb.build())), out);
        Schematic read = READER.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("minecraft:stone", read.regions().getFirst().get(0, 0, 0).name());
    }

    @Test
    void rejectsBadFiles() {
        NbtCompound outside = house();
        outside.getList("blocks").add(block(0, 3, 0, 0));
        assertThrows(SchematicFormatException.class, () -> READER.read(outside));

        NbtCompound badIndex = house();
        badIndex.getList("blocks").add(block(5, 0, 1, 1));
        assertThrows(SchematicFormatException.class, () -> READER.read(badIndex));

        NbtCompound old = house().put("DataVersion", 1343);
        assertThrows(SchematicFormatException.class, () -> READER.read(old));

        NbtCompound huge = house().put("size", ints(1000, 1000, 1000));
        assertThrows(SchematicFormatException.class, () -> READER.read(huge));

        assertThrows(SchematicFormatException.class, () -> READER.read(new NbtCompound().put("foo", 1)));
    }
}
