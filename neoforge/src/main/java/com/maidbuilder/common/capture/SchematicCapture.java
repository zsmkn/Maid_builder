package com.maidbuilder.common.capture;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.BlockStateData;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.schematic.Schematic;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Copies a box of the world into a single-region {@code .litematic} whose origin is the box's
 * lowest corner. Only block states are captured: block entity data (chest contents, sign text...)
 * and entities are left out, so a blueprint never exposes what is stored in other players' chests
 * and building from it can never duplicate items.
 */
public final class SchematicCapture {
    private SchematicCapture() {
    }

    public static Schematic capture(Level level, BlockPos min, BlockPos max, String name, String author) {
        IntPos size = new IntPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
        LitematicWriter.RegionBuilder region = new LitematicWriter.RegionBuilder(name, IntPos.ZERO, size);
        Map<BlockState, BlockStateData> converted = new IdentityHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    BlockState state = level.getBlockState(pos.set(min.getX() + x, min.getY() + y, min.getZ() + z));
                    if (state.isAir()) continue;
                    region.set(x, y, z, converted.computeIfAbsent(state, SchematicCapture::toData));
                }
            }
        }
        int dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        return LitematicWriter.schematic(name, author, System.currentTimeMillis(), dataVersion, List.of(region.build()));
    }

    public static byte[] toBytes(Schematic schematic) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LitematicWriter.write(schematic, out);
        return out.toByteArray();
    }

    public static BlockStateData toData(BlockState state) {
        Map<String, String> props = new TreeMap<>();
        for (Property<?> property : state.getProperties()) props.put(property.getName(), valueName(state, property));
        return new BlockStateData(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), props);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
