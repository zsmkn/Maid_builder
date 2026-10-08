package com.maidbuilder.common.job;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.BlockBreaker;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.Convert;
import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.common.StateResolver;
import com.maidbuilder.core.plan.BuildPhase;
import com.maidbuilder.core.plan.BuildPlan;
import com.maidbuilder.core.plan.BuildPlanner;
import com.maidbuilder.core.plan.BuildStep;
import com.maidbuilder.core.plan.MaterialRules;
import com.maidbuilder.core.schematic.BlockStateData;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.transform.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * A schematic placed in the world plus per-block progress. Only the placement and the
 * per-step status bytes are saved; the ordered plan is rebuilt from the stored schematic copy
 * (deterministic), so the save stays small and never needs re-planning inside a tick.
 */
public final class BuildJob {
    public static final byte PENDING = 0;
    public static final byte DONE = 1;
    /** Left for the player: unreachable, obstructed or out of retries. */
    public static final byte NEEDS_PLAYER = 2;
    /** Denied by a protection mod or unresolvable. */
    public static final byte FAILED = 3;
    private static final long RETRY_COOLDOWN_TICKS = 100;

    public record Requirement(Item item, int count) {
    }

    private final UUID id;
    private final UUID owner;
    private final String ownerName;
    private final String schematicName;
    private final String schematicHash;
    private final ResourceKey<Level> dimension;
    private final BlockPos origin;
    private final Rotation rotation;
    private final Mirror mirror;
    private final boolean placeFluids;
    /** What maids may break; jobs saved before clearing existed load as OFF so their plan stays the same. */
    private ClearMode clearMode = ClearMode.OFF;
    private int clearRadius;
    private byte[] status;
    /** Cancelled by its owner: maids stop building, take their scaffolding down, then the job is deleted. */
    private boolean cancelled;
    /** Its territory lost its flag: maids stop (and take their scaffolding down) until a flag is back. */
    private boolean suspended;
    /** Territory whose queue this job is in, the template it builds, and the building it repairs (all optional). */
    @Nullable
    private UUID territoryId;
    @Nullable
    private String templateId;
    @Nullable
    private UUID buildingId;
    /** Containers the maids take missing materials from (plan phase 6). */
    private final List<BlockPos> materialSources = new ArrayList<>();
    /** Scaffolding blocks the maids put up; removed again once the job is complete. */
    private final Set<BlockPos> scaffolds = new LinkedHashSet<>();

    // Runtime state, rebuilt on demand.
    private BuildPlan plan;
    private BlockState[] targets;
    private List<List<Requirement>> requirements;
    /** Every position the finished structure occupies (incl. door tops and bed heads). */
    private LongSet occupied;
    private BlockPos min = BlockPos.ZERO, max = BlockPos.ZERO;
    private int cursor;
    private final Map<Integer, UUID> claims = new HashMap<>();
    private final Map<UUID, Integer> claimByMaid = new HashMap<>();
    private final Map<Integer, Integer> attempts = new HashMap<>();
    /** Game time before which a step that just failed is not tried again. */
    private final Map<Integer, Long> retryAfter = new HashMap<>();
    /** Blocks each maid placed since the job was loaded (not saved). */
    private final Map<UUID, Integer> placed = new HashMap<>();
    /** Game time each maid last worked on this job, to know how many share it. */
    private final Map<UUID, Long> workers = new HashMap<>();
    /** Scaffold columns (keyed by x/z) in use by a maid, so two maids do not climb the same one. */
    private final Map<Long, UUID> columnClaims = new HashMap<>();
    private List<String> unknownBlocks = List.of();
    private Runnable dirtyListener = () -> {
    };

    public BuildJob(UUID id, UUID owner, String ownerName, String schematicName, String schematicHash,
                    ResourceKey<Level> dimension, BlockPos origin, Rotation rotation, Mirror mirror,
                    boolean placeFluids, @Nullable byte[] status) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.schematicName = schematicName;
        this.schematicHash = schematicHash;
        this.dimension = dimension;
        this.origin = origin.immutable();
        this.rotation = rotation;
        this.mirror = mirror;
        this.placeFluids = placeFluids;
        this.status = status;
    }

    // ---- persistence ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Owner", owner);
        tag.putString("OwnerName", ownerName);
        tag.putString("SchematicName", schematicName);
        tag.putString("SchematicHash", schematicHash);
        tag.putString("Dimension", dimension.location().toString());
        tag.putLong("Origin", origin.asLong());
        tag.putString("Rotation", rotation.name());
        tag.putString("Mirror", mirror.name());
        tag.putBoolean("PlaceFluids", placeFluids);
        tag.putString("ClearMode", clearMode.name());
        tag.putInt("ClearRadius", clearRadius);
        if (status != null) tag.putByteArray("Status", status);
        if (cancelled) tag.putBoolean("Cancelled", true);
        if (suspended) tag.putBoolean("Suspended", true);
        if (territoryId != null) tag.putUUID("Territory", territoryId);
        if (templateId != null) tag.putString("Template", templateId);
        if (buildingId != null) tag.putUUID("Building", buildingId);
        tag.putLongArray("MaterialSources", materialSources.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putLongArray("Scaffolds", scaffolds.stream().mapToLong(BlockPos::asLong).toArray());
        return tag;
    }

    public static BuildJob load(CompoundTag tag) {
        BuildJob job = new BuildJob(
                tag.getUUID("Id"),
                tag.getUUID("Owner"),
                tag.getString("OwnerName"),
                tag.getString("SchematicName"),
                tag.getString("SchematicHash"),
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("Dimension"))),
                BlockPos.of(tag.getLong("Origin")),
                enumOr(Rotation.class, tag.getString("Rotation"), Rotation.NONE),
                enumOr(Mirror.class, tag.getString("Mirror"), Mirror.NONE),
                tag.getBoolean("PlaceFluids"),
                tag.contains("Status") ? tag.getByteArray("Status") : null);
        job.clearMode = enumOr(ClearMode.class, tag.getString("ClearMode"), ClearMode.OFF);
        job.clearRadius = tag.getInt("ClearRadius");
        for (long pos : tag.getLongArray("MaterialSources")) job.materialSources.add(BlockPos.of(pos));
        for (long pos : tag.getLongArray("Scaffolds")) job.scaffolds.add(BlockPos.of(pos));
        job.cancelled = tag.getBoolean("Cancelled");
        job.suspended = tag.getBoolean("Suspended");
        if (tag.hasUUID("Territory")) job.territoryId = tag.getUUID("Territory");
        if (tag.contains("Template")) job.templateId = tag.getString("Template");
        if (tag.hasUUID("Building")) job.buildingId = tag.getUUID("Building");
        return job;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    void setDirtyListener(Runnable listener) {
        this.dirtyListener = listener;
    }

    // ---- plan loading ----

    public boolean isLoaded() {
        return plan != null;
    }

    /** Loads the schematic copy and builds the plan. Cheap after the first call. */
    public void ensureLoaded(MinecraftServer server) throws IOException {
        if (plan != null) return;
        Schematic schematic = SchematicStore.loadStored(server, schematicHash);
        Placement placement = Convert.placement(origin, rotation, mirror);
        BuildPlan built = BuildPlanner.plan(schematic, placement, new BuildPlanner.Options(placeFluids, airClearRadius()));
        StateResolver resolver = new StateResolver(schematic.minecraftDataVersion());
        int n = built.size();
        BlockState[] resolved = new BlockState[n];
        List<List<Requirement>> reqs = new ArrayList<>(n);
        LongSet taken = new LongOpenHashSet(n);
        BlockPos lo = null, hi = null;
        for (int i = 0; i < n; i++) {
            BuildStep step = built.steps().get(i);
            resolved[i] = resolver.resolve(step.schematicState(), placement);
            reqs.add(requirementsFor(step.schematicState(), resolved[i]));
            BlockPos pos = Convert.toBlockPos(step.worldPos());
            if (step.phase() != BuildPhase.CLEAR) {
                for (BlockPlacer.Part part : BlockPlacer.partsOf(pos, resolved[i])) taken.add(part.pos().asLong());
            }
            lo = lo == null ? pos : BlockPos.min(lo, pos);
            hi = hi == null ? pos : BlockPos.max(hi, pos);
        }
        if (status == null || status.length != n) {
            status = new byte[n];
        }
        for (int i = 0; i < n; i++) {
            if (status[i] == PENDING && built.steps().get(i).phase() != BuildPhase.CLEAR
                    && (resolved[i].isAir() || reqs.get(i).isEmpty())) status[i] = FAILED;
        }
        this.unknownBlocks = List.copyOf(resolver.unknownBlocks());
        this.targets = resolved;
        this.requirements = reqs;
        this.occupied = taken;
        this.min = lo == null ? origin : lo;
        this.max = hi == null ? origin : hi;
        this.plan = built;
        this.cursor = 0;
    }

    // ---- clearing ----

    public ClearMode clearMode() {
        return clearMode;
    }

    public int clearRadius() {
        return clearRadius;
    }

    /** Radius the plan clears the schematic's air within; 0 when air is left alone. */
    public int airClearRadius() {
        return clearMode == ClearMode.ALL ? Math.max(1, clearRadius) : 0;
    }

    /** Takes the server's clearing settings; for new jobs, before they are first loaded. */
    public void useDefaultClearing() {
        this.clearMode = MaidBuilderConfig.CLEAR_MODE.get();
        this.clearRadius = MaidBuilderConfig.CLEAR_RADIUS.get();
    }

    /** For repairs: wrong blocks of the building are replaced, but nothing else is cleared. Before the first load. */
    public void limitClearingToReplace() {
        if (clearMode == ClearMode.ALL) clearMode = ClearMode.REPLACE;
    }

    /**
     * Changes what the maids may break. When the planned air cells change, the plan is rebuilt and
     * the progress of the building steps is carried over by position.
     */
    public void setClearing(ClearMode mode, int radius, MinecraftServer server) throws IOException {
        if (mode == clearMode && radius == clearRadius) return;
        ensureLoaded(server);
        int oldRadius = airClearRadius();
        Map<Long, Byte> progress = new HashMap<>();
        for (int i = 0; i < status.length; i++) {
            if (!isClearStep(i)) progress.put(pos(i).asLong(), status[i]);
        }
        clearMode = mode;
        clearRadius = radius;
        if (airClearRadius() != oldRadius) {
            plan = null;
            status = null;
            claims.clear();
            claimByMaid.clear();
            attempts.clear();
            retryAfter.clear();
            ensureLoaded(server);
            for (int i = 0; i < status.length; i++) {
                Byte old = isClearStep(i) ? null : progress.get(pos(i).asLong());
                if (old != null) status[i] = old;
            }
        }
        dirtyListener.run();
    }

    /** A step that breaks whatever stands in the schematic's air instead of placing a block. */
    public boolean isClearStep(int index) {
        return plan.steps().get(index).phase() == BuildPhase.CLEAR;
    }

    /** Whether the world already holds what step {@code index} wants at its position. */
    public boolean isSatisfied(int index, BlockState inWorld) {
        if (isClearStep(index)) return BlockBreaker.isCleared(inWorld) || isScaffold(pos(index));
        return BlockPlacer.matches(inWorld, targets[index]);
    }

    /** Clear steps still to do. */
    public int clearRemaining() {
        if (plan == null) return 0;
        int n = 0;
        for (int i = 0; i < status.length; i++) {
            if (status[i] == PENDING && isClearStep(i)) n++;
        }
        return n;
    }

    /**
     * Marks pending clear steps whose cell is already empty as done, looking at up to {@code budget}
     * of them from the first unfinished step. Most of the schematic's air usually is empty already,
     * and this keeps the maids' bounded searches from wading through it.
     */
    public void sweepClear(net.minecraft.server.level.ServerLevel level, int budget) {
        if (plan == null) return;
        while (cursor < status.length && status[cursor] != PENDING) cursor++;
        for (int i = cursor, seen = 0; i < status.length && seen < budget && isClearStep(i); i++) {
            if (status[i] != PENDING || claims.containsKey(i)) continue;
            seen++;
            BlockPos pos = pos(i);
            if (level.isLoaded(pos) && isSatisfied(i, level.getBlockState(pos))) setStatus(i, DONE);
        }
    }

    /** Items one placement of {@code state} consumes (the base item plus extras such as a potted plant). */
    public static List<Requirement> requirementsFor(BlockStateData data, BlockState state) {
        if (state.isAir()) return List.of();
        Item base = MaterialRules.hasItemOverride(data)
                ? BuiltInRegistries.ITEM.get(ResourceLocation.parse(MaterialRules.itemFor(data))) : state.getBlock().asItem();
        if (base == Items.AIR) {
            base = BuiltInRegistries.ITEM.get(ResourceLocation.parse(MaterialRules.itemFor(data)));
        }
        if (base == Items.AIR) return List.of();
        List<Requirement> list = new ArrayList<>(2);
        list.add(new Requirement(base, MaterialRules.countFor(data)));
        for (String extra : MaterialRules.extraItems(data)) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(extra));
            if (item != Items.AIR) list.add(new Requirement(item, 1));
        }
        return list;
    }

    // ---- queries ----

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public String schematicName() {
        return schematicName;
    }

    public String schematicHash() {
        return schematicHash;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
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

    public List<String> unknownBlocks() {
        return unknownBlocks;
    }

    public String shortId() {
        return id.toString().substring(0, 8);
    }

    public int size() {
        return targets == null ? (status == null ? 0 : status.length) : targets.length;
    }

    public int count(byte state) {
        if (status == null) return 0;
        int c = 0;
        for (byte b : status) if (b == state) c++;
        return c;
    }

    public boolean isComplete() {
        return status != null && count(PENDING) == 0;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Nothing to build now: complete, cancelled or suspended. Scaffolding may still have to come down. */
    public boolean isFinished() {
        return cancelled || suspended || isComplete();
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean value) {
        if (suspended == value) return;
        suspended = value;
        if (value) {
            claims.clear();
            claimByMaid.clear();
        }
        dirtyListener.run();
    }

    // ---- territory ----

    /** Links the job to a territory's queue (and optionally a template building or the building it repairs). */
    public void setTerritory(@Nullable UUID territory, @Nullable String template, @Nullable UUID building) {
        this.territoryId = territory;
        this.templateId = template;
        this.buildingId = building;
        dirtyListener.run();
    }

    @Nullable
    public UUID territoryId() {
        return territoryId;
    }

    @Nullable
    public String templateId() {
        return templateId;
    }

    /** The building this job repairs; null for a new building or a plain job. */
    @Nullable
    public UUID buildingId() {
        return buildingId;
    }

    /** Whether maids still have something to do for this job (build, or take scaffolding down). */
    public boolean needsMaids() {
        return !isFinished() || !scaffolds.isEmpty();
    }

    public void cancel() {
        if (cancelled) return;
        cancelled = true;
        claims.clear();
        claimByMaid.clear();
        dirtyListener.run();
    }

    public BlockPos pos(int index) {
        return Convert.toBlockPos(plan.steps().get(index).worldPos());
    }

    public BlockState target(int index) {
        return targets[index];
    }

    public List<Requirement> requirements(int index) {
        return requirements.get(index);
    }

    public byte status(int index) {
        return status[index];
    }

    /** Whether the finished structure has a block at {@code pos}. */
    public boolean isStructurePos(BlockPos pos) {
        return occupied != null && occupied.contains(pos.asLong());
    }

    /** Lowest corner of the planned blocks. */
    public BlockPos minCorner() {
        return min;
    }

    public BlockPos maxCorner() {
        return max;
    }

    /**
     * Horizontal circle the maids may roam in while looking for a way up: centred on the
     * structure, covering its footprint plus {@code margin} blocks.
     */
    /** Centre of the planned blocks. */
    public net.minecraft.world.phys.Vec3 center() {
        return new net.minecraft.world.phys.Vec3((min.getX() + max.getX() + 1) / 2.0, (min.getY() + max.getY() + 1) / 2.0,
                (min.getZ() + max.getZ() + 1) / 2.0);
    }

    /** Distance from {@link #center()} to the farthest corner of the planned blocks. */
    public double radius() {
        double hx = (max.getX() - min.getX() + 1) / 2.0, hy = (max.getY() - min.getY() + 1) / 2.0, hz = (max.getZ() - min.getZ() + 1) / 2.0;
        return Math.sqrt(hx * hx + hy * hy + hz * hz);
    }

    public boolean inWorkArea(BlockPos pos, double margin) {
        double cx = (min.getX() + max.getX() + 1) / 2.0, cz = (min.getZ() + max.getZ() + 1) / 2.0;
        double hx = (max.getX() - min.getX() + 1) / 2.0, hz = (max.getZ() - min.getZ() + 1) / 2.0;
        double radius = Math.sqrt(hx * hx + hz * hz) + margin;
        double dx = pos.getX() + 0.5 - cx, dz = pos.getZ() + 0.5 - cz;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** Remaining items needed for all pending steps. */
    public Map<Item, Integer> remainingMaterials() {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        if (targets == null) return needed;
        for (int i = 0; i < targets.length; i++) {
            if (status[i] != PENDING) continue;
            for (Requirement r : requirements.get(i)) needed.merge(r.item(), r.count(), Integer::sum);
        }
        return needed;
    }

    /** Items needed for the whole structure, built or not; steps that failed for good are left out. */
    public Map<Item, Integer> totalMaterials() {
        Map<Item, Integer> needed = new LinkedHashMap<>();
        if (targets == null) return needed;
        for (int i = 0; i < targets.length; i++) {
            if (status[i] == FAILED) continue;
            for (Requirement r : requirements.get(i)) needed.merge(r.item(), r.count(), Integer::sum);
        }
        return needed;
    }

    /**
     * Returns the first pending, unclaimed step accepted by {@code filter} that is not cooling down
     * after a failed try, scanning at most {@code maxScan} pending steps from the first unfinished
     * one; -1 if none.
     */
    public int findNext(long gameTime, IntPredicate filter, int maxScan) {
        if (targets == null) return -1;
        while (cursor < status.length && status[cursor] != PENDING) cursor++;
        int scanned = 0;
        for (int i = cursor; i < status.length && scanned < maxScan; i++) {
            if (status[i] != PENDING) continue;
            scanned++;
            if (claims.containsKey(i)) continue;
            Long notBefore = retryAfter.get(i);
            if (notBefore != null && notBefore > gameTime) continue;
            if (filter.test(i)) return i;
        }
        return -1;
    }

    /** How a step is scored against the others of its layer; lower wins. */
    @FunctionalInterface
    public interface StepScore {
        double score(int index);
    }

    /**
     * Like {@link #findNext}, but instead of the very first acceptable step it picks the best-scoring
     * one among the acceptable steps of the same layer (same build phase and height) as the first,
     * so several maids can spread out over a layer while the bottom-up order is kept.
     */
    public int findBest(long gameTime, IntPredicate filter, StepScore score, int maxScan) {
        if (targets == null) return -1;
        while (cursor < status.length && status[cursor] != PENDING) cursor++;
        int scanned = 0, best = -1;
        double bestScore = Double.MAX_VALUE;
        long layer = Long.MIN_VALUE;
        for (int i = cursor; i < status.length && scanned < maxScan; i++) {
            if (status[i] != PENDING) continue;
            if (best >= 0 && layerOf(i) != layer) break;
            scanned++;
            if (claims.containsKey(i)) continue;
            Long notBefore = retryAfter.get(i);
            if (notBefore != null && notBefore > gameTime) continue;
            if (!filter.test(i)) continue;
            double s = score.score(i);
            if (best < 0) layer = layerOf(i);
            if (s < bestScore) {
                best = i;
                bestScore = s;
            }
        }
        return best;
    }

    private long layerOf(int index) {
        BuildStep step = plan.steps().get(index);
        return ((long) step.phase().ordinal() << 32) | (step.worldPos().y() & 0xFFFFFFFFL);
    }

    /** Positions other maids have claimed (to keep maids from crowding one spot). */
    public List<BlockPos> claimedByOthers(UUID maid) {
        List<BlockPos> list = new ArrayList<>(claims.size());
        claims.forEach((index, holder) -> {
            if (!holder.equals(maid)) list.add(pos(index));
        });
        return list;
    }

    // ---- team ----

    private static final long WORKER_TIMEOUT = 400;

    public void recordPlaced(UUID maid) {
        placed.merge(maid, 1, Integer::sum);
    }

    public int placedBy(UUID maid) {
        return placed.getOrDefault(maid, 0);
    }

    public void touchWorker(UUID maid, long gameTime) {
        workers.put(maid, gameTime);
    }

    /** Maids that worked on this job recently (0 if none). */
    public int activeWorkers(long gameTime) {
        workers.values().removeIf(t -> gameTime - t > WORKER_TIMEOUT || t > gameTime);
        return workers.size();
    }

    /** Maids that worked on this job recently (at least 1). */
    public int teamSize(long gameTime) {
        workers.values().removeIf(t -> gameTime - t > WORKER_TIMEOUT || t > gameTime);
        return Math.max(1, workers.size());
    }

    /**
     * Items the maid lacks for the next {@code lookahead} pending steps she could work on, i.e. what
     * she should fetch from a material container. Steps cooling down after a failure are ignored.
     */
    public Map<Item, Integer> shoppingList(Map<Item, Integer> inventory, int lookahead, long gameTime) {
        Map<Item, Integer> wanted = new LinkedHashMap<>();
        if (targets == null) return wanted;
        int seen = 0;
        for (int i = cursor; i < status.length && seen < lookahead; i++) {
            if (status[i] != PENDING || claims.containsKey(i) || isClearStep(i)) continue; // clearing needs no materials
            Long notBefore = retryAfter.get(i);
            if (notBefore != null && notBefore > gameTime) continue;
            seen++;
            for (Requirement r : requirements.get(i)) wanted.merge(r.item(), r.count(), Integer::sum);
        }
        wanted.replaceAll((item, count) -> count - inventory.getOrDefault(item, 0));
        wanted.values().removeIf(count -> count <= 0);
        return wanted;
    }

    // ---- material containers ----

    public List<BlockPos> materialSources() {
        return java.util.Collections.unmodifiableList(materialSources);
    }

    /** The job's own material containers plus those of its territory (while the territory is active). */
    public List<BlockPos> allMaterialSources(MinecraftServer server) {
        if (territoryId == null) return materialSources();
        List<BlockPos> extra = com.maidbuilder.common.territory.TerritoryManager.materialSourcesOf(server, territoryId);
        if (extra.isEmpty()) return materialSources();
        List<BlockPos> all = new ArrayList<>(materialSources);
        for (BlockPos pos : extra) if (!all.contains(pos)) all.add(pos);
        return all;
    }

    public void addMaterialSource(BlockPos pos) {
        if (!materialSources.contains(pos)) {
            materialSources.add(pos.immutable());
            dirtyListener.run();
        }
    }

    public void removeMaterialSource(BlockPos pos) {
        if (materialSources.remove(pos)) dirtyListener.run();
    }

    /** Adds or removes a material container; returns true if it is now a source. */
    public boolean toggleMaterialSource(BlockPos pos) {
        boolean added;
        if (materialSources.remove(pos)) {
            added = false;
        } else {
            materialSources.add(pos.immutable());
            added = true;
        }
        dirtyListener.run();
        return added;
    }

    // ---- scaffolding ----

    public Set<BlockPos> scaffolds() {
        return Collections.unmodifiableSet(scaffolds);
    }

    public boolean isScaffold(BlockPos pos) {
        return scaffolds.contains(pos);
    }

    public void addScaffold(BlockPos pos) {
        if (scaffolds.add(pos.immutable())) dirtyListener.run();
    }

    public void removeScaffold(BlockPos pos) {
        if (scaffolds.remove(pos)) dirtyListener.run();
    }

    /** Tracked scaffolding grouped into columns by x/z, each sorted bottom to top. */
    public Map<Long, List<BlockPos>> scaffoldColumns() {
        Map<Long, List<BlockPos>> columns = new TreeMap<>();
        for (BlockPos pos : scaffolds) columns.computeIfAbsent(columnKey(pos), k -> new ArrayList<>()).add(pos);
        for (List<BlockPos> column : columns.values()) column.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        return columns;
    }

    public static long columnKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX(), 0, pos.getZ());
    }

    /** Reserves a scaffold column for one maid; true if it is free or already hers. */
    public boolean claimColumn(long key, UUID maid) {
        UUID holder = columnClaims.putIfAbsent(key, maid);
        return holder == null || holder.equals(maid);
    }

    /** Free, or claimed by this maid herself. */
    public boolean columnFreeFor(long key, UUID maid) {
        UUID holder = columnClaims.get(key);
        return holder == null || holder.equals(maid);
    }

    public void releaseColumns(UUID maid) {
        columnClaims.values().removeIf(maid::equals);
    }

    // ---- claims (several maids can share a job) ----

    public boolean claim(int index, UUID maid) {
        release(maid);
        if (claims.putIfAbsent(index, maid) != null) return false;
        claimByMaid.put(maid, index);
        return true;
    }

    public void release(UUID maid) {
        Integer index = claimByMaid.remove(maid);
        if (index != null) claims.remove(index, maid);
    }

    public int claimOf(UUID maid) {
        return claimByMaid.getOrDefault(maid, -1);
    }

    // ---- progress ----

    public void setStatus(int index, byte value) {
        if (status[index] != value) {
            MaidBuilder.LOGGER.debug("Job {} step {} {} at {}: {} -> {}", shortId(), index, targets[index], pos(index), status[index], value);
            status[index] = value;
            attempts.remove(index);
            retryAfter.remove(index);
            dirtyListener.run();
        }
    }

    /**
     * Records a failed try. The step then cools down (longer after each failure) so other steps get
     * built meanwhile; after the configured number of tries it is left for the player.
     */
    public void recordAttempt(int index, long gameTime) {
        int n = attempts.merge(index, 1, Integer::sum);
        retryAfter.put(index, gameTime + RETRY_COOLDOWN_TICKS * n);
        if (n >= MaidBuilderConfig.MAX_ATTEMPTS.get()) {
            MaidBuilder.LOGGER.debug("Job {} step {} at {} left for the player after {} attempts", shortId(), index, pos(index), n);
            setStatus(index, NEEDS_PLAYER);
        }
    }

    /** Puts every step left for the player back into the queue. */
    public int retryNeedsPlayer() {
        int n = 0;
        for (int i = 0; i < status.length; i++) {
            if (status[i] == NEEDS_PLAYER) {
                status[i] = PENDING;
                n++;
            }
        }
        attempts.clear();
        retryAfter.clear();
        cursor = 0;
        if (n > 0) dirtyListener.run();
        return n;
    }
}
