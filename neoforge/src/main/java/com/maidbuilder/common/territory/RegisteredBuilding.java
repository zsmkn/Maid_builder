package com.maidbuilder.common.territory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A finished template building of a territory. Where it is placed is kept so its blocks can be
 * compared with the template ({@link BuildingIntegrity}) and so a repair job can be put in the same place.
 */
public final class RegisteredBuilding {
    private final UUID id;
    private final String templateId;
    private final String schematicHash;
    private final BlockPos origin;
    private final Rotation rotation;
    private final Mirror mirror;
    private final BlockPos min, max;
    /** Intact enough to count; set by the integrity check. */
    private boolean working = true;
    private int integrity = 100;
    /** Game time of the building's next periodic event (a trader or villager arriving); 0 = as soon as it works. */
    private long nextEvent;
    /** Shop: items sold on {@link #soldDay} (item id -> count); a new day starts from zero. */
    private final java.util.Map<String, Integer> sold = new java.util.HashMap<>();
    private long soldDay = -1;
    /** Maids working at this building (plan G4). */
    private final Set<UUID> assignedMaids = new LinkedHashSet<>();

    public RegisteredBuilding(UUID id, String templateId, String schematicHash, BlockPos origin, Rotation rotation, Mirror mirror,
                              BlockPos min, BlockPos max) {
        this.id = id;
        this.templateId = templateId;
        this.schematicHash = schematicHash;
        this.origin = origin.immutable();
        this.rotation = rotation;
        this.mirror = mirror;
        this.min = min.immutable();
        this.max = max.immutable();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Template", templateId);
        tag.putString("Hash", schematicHash);
        tag.putLong("Origin", origin.asLong());
        tag.putString("Rotation", rotation.name());
        tag.putString("Mirror", mirror.name());
        tag.putLong("Min", min.asLong());
        tag.putLong("Max", max.asLong());
        tag.putBoolean("Working", working);
        tag.putInt("Integrity", integrity);
        if (nextEvent != 0) tag.putLong("NextEvent", nextEvent);
        if (!sold.isEmpty()) {
            CompoundTag soldTag = new CompoundTag();
            sold.forEach(soldTag::putInt);
            tag.put("Sold", soldTag);
            tag.putLong("SoldDay", soldDay);
        }
        ListTag maids = new ListTag();
        for (UUID m : assignedMaids) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", m);
            maids.add(t);
        }
        tag.put("Maids", maids);
        return tag;
    }

    public static RegisteredBuilding load(CompoundTag tag) {
        RegisteredBuilding b = new RegisteredBuilding(tag.getUUID("Id"), tag.getString("Template"), tag.getString("Hash"),
                BlockPos.of(tag.getLong("Origin")), enumOr(Rotation.class, tag.getString("Rotation"), Rotation.NONE),
                enumOr(Mirror.class, tag.getString("Mirror"), Mirror.NONE), BlockPos.of(tag.getLong("Min")), BlockPos.of(tag.getLong("Max")));
        b.working = !tag.contains("Working") || tag.getBoolean("Working");
        b.integrity = tag.contains("Integrity") ? tag.getInt("Integrity") : 100;
        b.nextEvent = tag.getLong("NextEvent");
        if (tag.contains("Sold")) {
            CompoundTag soldTag = tag.getCompound("Sold");
            for (String key : soldTag.getAllKeys()) b.sold.put(key, soldTag.getInt(key));
            b.soldDay = tag.getLong("SoldDay");
        }
        for (Tag t : tag.getList("Maids", Tag.TAG_COMPOUND)) b.assignedMaids.add(((CompoundTag) t).getUUID("Id"));
        return b;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public UUID id() {
        return id;
    }

    public String shortId() {
        return id.toString().substring(0, 8);
    }

    public String templateId() {
        return templateId;
    }

    public String schematicHash() {
        return schematicHash;
    }

    public BlockPos origin() {
        return origin;
    }

    public Rotation rotation() {
        return rotation;
    }

    public Mirror mirror() {
        return mirror;
    }

    public BlockPos min() {
        return min;
    }

    public BlockPos max() {
        return max;
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    public AABB box() {
        return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
    }

    public BlockPos center() {
        return new BlockPos((min.getX() + max.getX()) / 2, min.getY(), (min.getZ() + max.getZ()) / 2);
    }

    public boolean isWorking() {
        return working;
    }

    public int integrity() {
        return integrity;
    }

    /** Returns true if anything changed. */
    boolean setIntegrity(int percent, boolean working) {
        boolean changed = this.working != working || this.integrity != percent;
        this.integrity = percent;
        this.working = working;
        return changed;
    }

    public long nextEvent() {
        return nextEvent;
    }

    public void setNextEvent(long gameTime) {
        nextEvent = gameTime;
    }

    /** How many of the item the shop sold on {@code day}. */
    public int soldOn(long day, String item) {
        return day == soldDay ? sold.getOrDefault(item, 0) : 0;
    }

    void recordSale(long day, String item, int count) {
        if (day != soldDay) {
            sold.clear();
            soldDay = day;
        }
        sold.merge(item, count, Integer::sum);
    }

    public Set<UUID> assignedMaids() {
        return Collections.unmodifiableSet(assignedMaids);
    }

    boolean assign(UUID maid) {
        return assignedMaids.add(maid);
    }

    boolean unassign(UUID maid) {
        return assignedMaids.remove(maid);
    }
}
