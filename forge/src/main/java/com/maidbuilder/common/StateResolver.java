package com.maidbuilder.common;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.core.schematic.BlockStateData;
import com.maidbuilder.core.transform.Placement;
import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns schematic state strings into game {@link BlockState}s, upgrading states saved by
 * older game versions through the vanilla DataFixer. One resolver per schematic (the data
 * version is per file); results are cached because palettes are small.
 */
public final class StateResolver {
    /**
     * Blocks renamed after 1.20.1: schematics saved by newer versions use the new name, which this
     * version only knows under the old one (the DataFixer only upgrades, never downgrades).
     */
    private static final Map<String, String> DOWNGRADE_ALIASES = Map.of(
            "minecraft:short_grass", "minecraft:grass");

    private final int dataVersion;
    private final int currentVersion;
    private final Map<BlockStateData, BlockState> cache = new HashMap<>();
    private final Set<String> unknownBlocks = new LinkedHashSet<>();

    public StateResolver(int dataVersion) {
        this.dataVersion = dataVersion;
        this.currentVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    }

    /** Returns the game state, or air if the block does not exist (see {@link #unknownBlocks()}). */
    public BlockState resolve(BlockStateData data) {
        return cache.computeIfAbsent(data, this::doResolve);
    }

    /** Resolves and applies the placement's mirror then rotation using the game's own block logic. */
    public BlockState resolve(BlockStateData data, Placement placement) {
        return resolve(data)
                .mirror(Convert.toMc(placement.mirror()))
                .rotate(Convert.toMc(placement.rotation()));
    }

    public Set<String> unknownBlocks() {
        return unknownBlocks;
    }

    private BlockState doResolve(BlockStateData data) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", data.name());
        if (!data.properties().isEmpty()) {
            CompoundTag props = new CompoundTag();
            data.properties().forEach(props::putString);
            tag.put("Properties", props);
        }
        if (dataVersion > 0 && dataVersion < currentVersion) {
            try {
                Tag upgraded = DataFixers.getDataFixer()
                        .update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, tag), dataVersion, currentVersion)
                        .getValue();
                if (upgraded instanceof CompoundTag c) tag = c;
            } catch (RuntimeException e) {
                MaidBuilder.LOGGER.warn("Failed to upgrade block state {} from data version {}", data, dataVersion, e);
            }
        }
        String alias = DOWNGRADE_ALIASES.get(tag.getString("Name"));
        if (alias != null) tag.putString("Name", alias);
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("Name"));
        if (id == null || BuiltInRegistries.BLOCK.getHolder(ResourceKey.create(Registries.BLOCK, id)).isEmpty()) {
            unknownBlocks.add(tag.getString("Name"));
            return Blocks.AIR.defaultBlockState();
        }
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
    }
}
