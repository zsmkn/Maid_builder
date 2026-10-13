package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All territories of a server (every dimension), saved with the overworld's data storage. Keeps a
 * chunk index so "which territory is this position in" is a map lookup; the index covers the
 * largest radius any level may have, the exact square is checked afterwards.
 */
public final class TerritoryData extends SavedData {
    public static final String NAME = MaidBuilder.MOD_ID + "_territories";
    public static final Factory<TerritoryData> FACTORY = new Factory<>(TerritoryData::new, TerritoryData::load, null);

    private final Map<UUID, Territory> territories = new LinkedHashMap<>();
    private final Map<ResourceKey<Level>, Long2ObjectMap<List<Territory>>> index = new HashMap<>();
    private int indexedRadius = -1;
    /** Bumped on every change; clients are re-synced when it moves. */
    private int version;

    @Nullable
    public Territory get(UUID id) {
        return territories.get(id);
    }

    public Collection<Territory> all() {
        return Collections.unmodifiableCollection(territories.values());
    }

    public List<Territory> ownedBy(UUID player) {
        List<Territory> list = new ArrayList<>();
        for (Territory t : territories.values()) if (t.isOwnedBy(player)) list.add(t);
        return list;
    }

    public void add(Territory territory) {
        territory.setDirtyListener(this::setDirty);
        territories.put(territory.id(), territory);
        indexedRadius = -1;
        setDirty();
    }

    @Nullable
    public Territory remove(UUID id) {
        Territory removed = territories.remove(id);
        if (removed != null) {
            indexedRadius = -1;
            setDirty();
        }
        return removed;
    }

    /** The flag moved: the index must be rebuilt. */
    void reindex() {
        indexedRadius = -1;
        setDirty();
    }

    @Override
    public void setDirty() {
        super.setDirty();
        version++;
    }

    public int version() {
        return version;
    }

    /** The territory (active or not) whose square contains the column of {@code pos}. */
    @Nullable
    public Territory at(ResourceKey<Level> dimension, BlockPos pos) {
        List<Territory> candidates = index(dimension).get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (candidates == null) return null;
        for (Territory t : candidates) if (t.contains(pos)) return t;
        return null;
    }

    @Nullable
    public Territory atFlag(ResourceKey<Level> dimension, BlockPos flag) {
        for (Territory t : territories.values()) {
            if (t.dimension().equals(dimension) && t.flagPos().equals(flag)) return t;
        }
        return null;
    }

    private Long2ObjectMap<List<Territory>> index(ResourceKey<Level> dimension) {
        int radius = TerritoryLevels.maxRadius();
        if (radius != indexedRadius) rebuildIndex(radius);
        return index.getOrDefault(dimension, EMPTY);
    }

    private static final Long2ObjectMap<List<Territory>> EMPTY = new Long2ObjectOpenHashMap<>();

    private void rebuildIndex(int radius) {
        index.clear();
        for (Territory t : territories.values()) {
            Long2ObjectMap<List<Territory>> map = index.computeIfAbsent(t.dimension(), k -> new Long2ObjectOpenHashMap<>());
            BlockPos f = t.flagPos();
            for (int cx = (f.getX() - radius) >> 4; cx <= (f.getX() + radius) >> 4; cx++) {
                for (int cz = (f.getZ() - radius) >> 4; cz <= (f.getZ() + radius) >> 4; cz++) {
                    map.computeIfAbsent(ChunkPos.asLong(cx, cz), k -> new ArrayList<>(1)).add(t);
                }
            }
        }
        indexedRadius = radius;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Territory t : territories.values()) list.add(t.save());
        tag.put("Territories", list);
        return tag;
    }

    private static TerritoryData load(CompoundTag tag, HolderLookup.Provider registries) {
        TerritoryData data = new TerritoryData();
        for (Tag t : tag.getList("Territories", Tag.TAG_COMPOUND)) {
            try {
                Territory territory = Territory.load((CompoundTag) t);
                territory.setDirtyListener(data::setDirty);
                data.territories.put(territory.id(), territory);
            } catch (RuntimeException e) {
                MaidBuilder.LOGGER.error("Dropping unreadable territory {}", t, e);
            }
        }
        return data;
    }
}
