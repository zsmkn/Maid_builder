package com.maidbuilder.common.territory;

import com.maidbuilder.core.territory.BuiltBuilding;
import com.maidbuilder.core.territory.TerritoryRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A territory: the square around a flag. Saved in {@link TerritoryData}; runtime caches (the chunk
 * index) are rebuilt from it.
 */
public final class Territory {
    private final UUID id;
    private final UUID owner;
    private String ownerName;
    /** Reserved for shared territories; not used yet. */
    private final Set<UUID> members = new LinkedHashSet<>();
    private final ResourceKey<Level> dimension;
    private BlockPos flagPos;
    private int level = 1;
    /** False once the flag is gone: everything stops until the owner puts a flag back. */
    private boolean active = true;
    /** Containers every job of the territory takes materials from. */
    private final List<BlockPos> materialSources = new ArrayList<>();
    /** Build jobs of the territory, first = built first. */
    private final List<UUID> jobQueue = new ArrayList<>();
    /** Resident maid -> game time she last reported in. */
    private final Map<UUID, Long> residents = new HashMap<>();
    private final List<RegisteredBuilding> buildings = new ArrayList<>();
    private Runnable dirtyListener = () -> {
    };

    public Territory(UUID id, UUID owner, String ownerName, ResourceKey<Level> dimension, BlockPos flagPos) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.dimension = dimension;
        this.flagPos = flagPos.immutable();
    }

    // ---- persistence ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Owner", owner);
        tag.putString("OwnerName", ownerName);
        ListTag memberList = new ListTag();
        for (UUID m : members) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", m);
            memberList.add(t);
        }
        tag.put("Members", memberList);
        tag.putString("Dimension", dimension.location().toString());
        tag.putLong("Flag", flagPos.asLong());
        tag.putInt("Level", level);
        tag.putBoolean("Active", active);
        tag.putLongArray("MaterialSources", materialSources.stream().mapToLong(BlockPos::asLong).toArray());
        ListTag queue = new ListTag();
        for (UUID job : jobQueue) {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", job);
            queue.add(t);
        }
        tag.put("Queue", queue);
        ListTag residentList = new ListTag();
        residents.forEach((maid, time) -> {
            CompoundTag t = new CompoundTag();
            t.putUUID("Id", maid);
            t.putLong("Seen", time);
            residentList.add(t);
        });
        tag.put("Residents", residentList);
        ListTag buildingList = new ListTag();
        for (RegisteredBuilding b : buildings) buildingList.add(b.save());
        tag.put("Buildings", buildingList);
        return tag;
    }

    public static Territory load(CompoundTag tag) {
        Territory t = new Territory(tag.getUUID("Id"), tag.getUUID("Owner"), tag.getString("OwnerName"),
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("Dimension"))),
                BlockPos.of(tag.getLong("Flag")));
        for (Tag m : tag.getList("Members", Tag.TAG_COMPOUND)) t.members.add(((CompoundTag) m).getUUID("Id"));
        t.level = Math.max(1, tag.getInt("Level"));
        t.active = tag.getBoolean("Active");
        for (long pos : tag.getLongArray("MaterialSources")) t.materialSources.add(BlockPos.of(pos));
        for (Tag q : tag.getList("Queue", Tag.TAG_COMPOUND)) t.jobQueue.add(((CompoundTag) q).getUUID("Id"));
        for (Tag r : tag.getList("Residents", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) r;
            t.residents.put(c.getUUID("Id"), c.getLong("Seen"));
        }
        for (Tag b : tag.getList("Buildings", Tag.TAG_COMPOUND)) t.buildings.add(RegisteredBuilding.load((CompoundTag) b));
        return t;
    }

    void setDirtyListener(Runnable listener) {
        this.dirtyListener = listener;
    }

    void setDirty() {
        dirtyListener.run();
    }

    // ---- identity and place ----

    public UUID id() {
        return id;
    }

    public String shortId() {
        return id.toString().substring(0, 8);
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public void setOwnerName(String name) {
        if (!name.equals(ownerName)) {
            ownerName = name;
            setDirty();
        }
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos flagPos() {
        return flagPos;
    }

    void setFlagPos(BlockPos pos) {
        flagPos = pos.immutable();
        setDirty();
    }

    public int level() {
        return level;
    }

    void setLevel(int level) {
        this.level = level;
        setDirty();
    }

    public boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
        setDirty();
    }

    public int radius() {
        return TerritoryLevels.table().get(level).radius();
    }

    /** Whether the column of {@code pos} lies in the territory (any height). */
    public boolean contains(BlockPos pos) {
        return TerritoryRules.inside(flagPos.getX(), flagPos.getZ(), radius(), pos.getX(), pos.getZ());
    }

    public boolean contains(ResourceKey<Level> dim, BlockPos pos) {
        return dimension.equals(dim) && contains(pos);
    }

    public boolean isOwnedBy(UUID player) {
        return owner.equals(player);
    }

    // ---- material containers ----

    public List<BlockPos> materialSources() {
        return Collections.unmodifiableList(materialSources);
    }

    public void addMaterialSource(BlockPos pos) {
        if (!materialSources.contains(pos)) {
            materialSources.add(pos.immutable());
            setDirty();
        }
    }

    public void removeMaterialSource(BlockPos pos) {
        if (materialSources.remove(pos)) setDirty();
    }

    // ---- job queue ----

    public List<UUID> jobQueue() {
        return Collections.unmodifiableList(jobQueue);
    }

    public void enqueue(UUID job) {
        if (!jobQueue.contains(job)) {
            jobQueue.add(job);
            setDirty();
        }
    }

    public void dequeue(UUID job) {
        if (jobQueue.remove(job)) setDirty();
    }

    /** Moves a job one place towards the front ({@code -1}) or the back ({@code +1}). */
    public boolean moveJob(UUID job, int direction) {
        int i = jobQueue.indexOf(job);
        int j = i + Integer.signum(direction);
        if (i < 0 || j < 0 || j >= jobQueue.size()) return false;
        Collections.swap(jobQueue, i, j);
        setDirty();
        return true;
    }

    // ---- residents ----

    public Map<UUID, Long> residents() {
        return Collections.unmodifiableMap(residents);
    }

    void reportResident(UUID maid, long gameTime) {
        Long previous = residents.put(maid, gameTime);
        // the time alone changing is not worth a save; a new resident is
        if (previous == null) setDirty();
    }

    void removeResident(UUID maid) {
        if (residents.remove(maid) != null) setDirty();
    }

    /** Drops maids that have not reported in for {@code timeout} ticks. */
    void expireResidents(long gameTime, long timeout) {
        if (residents.values().removeIf(t -> gameTime - t > timeout || t > gameTime + timeout)) setDirty();
    }

    // ---- buildings ----

    public List<RegisteredBuilding> buildings() {
        return Collections.unmodifiableList(buildings);
    }

    void assignMaid(RegisteredBuilding building, UUID maid) {
        if (building.assign(maid)) setDirty();
    }

    void unassignMaid(RegisteredBuilding building, UUID maid) {
        if (building.unassign(maid)) setDirty();
    }

    void updateIntegrity(RegisteredBuilding building, int percent, boolean working) {
        if (building.setIntegrity(percent, working)) setDirty();
    }

    void addBuilding(RegisteredBuilding building) {
        buildings.add(building);
        setDirty();
    }

    boolean removeBuilding(UUID building) {
        boolean removed = buildings.removeIf(b -> b.id().equals(building));
        if (removed) setDirty();
        return removed;
    }

    public RegisteredBuilding building(UUID building) {
        for (RegisteredBuilding b : buildings) if (b.id().equals(building)) return b;
        return null;
    }

    /** Buildings as the core rules see them (unknown templates count with no group). */
    public List<BuiltBuilding> builtBuildings() {
        List<BuiltBuilding> list = new ArrayList<>(buildings.size());
        for (RegisteredBuilding b : buildings) {
            TemplateDef def = TemplateRegistry.get(b.templateId());
            list.add(new BuiltBuilding(def == null ? "" : def.group(), def == null ? "" : def.category(),
                    def == null ? 0 : def.prosperity(), active && b.isWorking()));
        }
        return list;
    }

    public int residentCount() {
        return residents.size();
    }

    public int prosperity() {
        return TerritoryRules.prosperity(builtBuildings(), residentCount(), TerritoryLevels.table().get(level));
    }

    /** Whether a level bonus applies here now (active territory at a high enough level). */
    public boolean hasBonus(String bonus) {
        return active && TerritoryLevels.table().hasBonus(level, bonus);
    }
}
