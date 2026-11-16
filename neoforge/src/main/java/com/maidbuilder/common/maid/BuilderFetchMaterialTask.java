package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.common.collect.ImmutableMap;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.MaterialContainers;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Plan phase 6/7: when the maid has no material for any block she could build next, she gets some:
 * <ol>
 *   <li>from the nearest material container holding what the next blocks need (only her share of
 *       what is left, so the first maid at the chest does not empty it for the whole team);</li>
 *   <li>otherwise from a container holding anything the job still needs further on (she then
 *       builds those blocks first instead of waiting for the missing ones);</li>
 *   <li>otherwise from a teammate on the same job who carries more than she does: she takes half
 *       the difference, so a maid who joins late is not left idle.</li>
 * </ol>
 * Cheap by design: it only runs when she is stuck, at most every {@link #CHECK_INTERVAL} ticks,
 * and only reads the few containers bound to the job.
 */
public class BuilderFetchMaterialTask extends Behavior<EntityMaid> {
    private static final int CHECK_INTERVAL = 40;
    private static final int NOTHING_FOUND_INTERVAL = 200;
    private static final int LOOKAHEAD_STEPS = 128;
    private static final int MIN_LOOKAHEAD = 16;
    /** How far ahead the wider search (step 2) looks. */
    private static final int WIDE_LOOKAHEAD_STEPS = 4096;
    private static final int MAX_RUN_TICKS = 1200;
    private static final double TAKE_DISTANCE = 2.5;
    private static final double TEAMMATE_RANGE = 32;
    /** Smallest amount worth walking to a teammate for. */
    private static final int MIN_SHARE = 4;
    /** Failed trips in a row after which she walks somewhere else at random before trying again. */
    private static final int STUCK_TRIPS = 2;
    private static final int WANDER_RANGE = 8;

    private final BuilderSession session;
    private final float speed;
    private int cooldown;
    @Nullable
    private BlockPos container;
    @Nullable
    private EntityMaid teammate;
    @Nullable
    private Map<Item, Integer> wanted;
    /** The way round to the container when the game's path finder found none. */
    private final RouteFollower route = new RouteFollower();
    /** Trips in a row that ended without reaching the container or teammate. */
    private int failedTrips;
    /** Set when she was sent off to wander: the walk target is hers to keep when the task stops. */
    private boolean wandering;
    /** Set once she took what she came for: from then on the walk target belongs to whoever sets it next. */
    private boolean arrived;

    public BuilderFetchMaterialTask(BuilderSession session, float speed) {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT,
                InitEntities.TARGET_POS.get(), MemoryStatus.VALUE_ABSENT), MAX_RUN_TICKS);
        this.session = session;
        this.speed = speed;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        // Asked by the scaffold task to stock up before a climb: at once, and although she could still build.
        boolean topUp = session.topUp && !session.busy;
        if (topUp) cooldown = 0;
        if (--cooldown > 0 || session.busy) return false;
        cooldown = CHECK_INTERVAL;
        session.topUp = false;
        UUID jobId = BuilderMaidData.jobOf(maid);
        if (jobId == null) return false;
        Optional<BuildJob> found = BuildJobManager.loaded(level.getServer(), jobId);
        if (found.isEmpty()) return false;
        BuildJob job = found.get();
        if (job.isFinished() || !job.dimension().equals(level.dimension())) return false;

        container = null;
        teammate = null;
        if (session.scaffoldingWanted > 0) {
            // the scaffold task needs scaffolding before it can go on
            Map<Item, Integer> list = new HashMap<>(Map.of(Items.SCAFFOLDING, session.scaffoldingWanted));
            session.scaffoldingWanted = 0;
            return fromContainer(level, maid, job, list);
        }

        Map<Item, Integer> inventory = MaidInventory.count(maid);
        long now = level.getGameTime();
        job.touchWorker(maid.getUUID(), now);
        // Still something she can build with what she carries? Then keep building. (Clearing needs no
        // materials, so it does not count: a clear step waiting for scaffolding must not stop her fetching.)
        // (Nor does a block that would hang in the air: she builds that once its layer is in turn.)
        long firstLayer = job.firstBuildLayer();
        if (!topUp && job.findNext(now, i -> !job.isClearStep(i) && MaidInventory.has(inventory, job.requirements(i))
                && inRange(maid, job.pos(i)) && job.joinsBuilt(level, i, firstLayer), LOOKAHEAD_STEPS) >= 0) {
            return false;
        }
        // Only her share of what is left (at most LOOKAHEAD_STEPS): otherwise the first maid at the
        // chest empties it and the others stand idle; a bigger share means fewer trips.
        int team = Math.max(job.teamSize(now), teamNearby(level, job));
        int share = Math.max(MIN_LOOKAHEAD, Math.min(LOOKAHEAD_STEPS, (job.count(BuildJob.PENDING) - job.clearRemaining() + team - 1) / team));
        Map<Item, Integer> next = job.shoppingList(inventory, share, now);
        if (!next.isEmpty() && fromContainer(level, maid, job, next)) return true;

        Map<Item, Integer> later = job.shoppingList(inventory, WIDE_LOOKAHEAD_STEPS, now);
        later.replaceAll((item, count) -> Math.min(count, share));
        if (later.isEmpty()) return false;
        if (fromContainer(level, maid, job, later)) return true;
        if (fromTeammate(level, maid, job, inventory, later)) return true;
        cooldown = NOTHING_FOUND_INTERVAL;
        return false;
    }

    private boolean fromContainer(ServerLevel level, EntityMaid maid, BuildJob job, Map<Item, Integer> list) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        double range = MaidBuilderConfig.MATERIAL_SOURCE_RANGE.get();
        for (Map.Entry<BlockPos, IItemHandler> e : MaterialContainers.distinct(level, job.allMaterialSources(level.getServer())).entrySet()) {
            double dist = maid.distanceToSqr(Vec3.atCenterOf(e.getKey()));
            if (dist > range * range || dist >= bestDist) continue;
            if (MaterialContainers.containsAny(e.getValue(), list)) {
                best = e.getKey();
                bestDist = dist;
            }
        }
        if (best == null) return false;
        container = best;
        wanted = list;
        return true;
    }

    /** A teammate on the ground nearby with more of a needed item than this maid: take half the difference. */
    private boolean fromTeammate(ServerLevel level, EntityMaid maid, BuildJob job, Map<Item, Integer> mine, Map<Item, Integer> needed) {
        EntityMaid best = null;
        Map<Item, Integer> bestShare = Map.of();
        int bestTotal = 0;
        for (EntityMaid other : level.getEntitiesOfClass(EntityMaid.class, maid.getBoundingBox().inflate(TEAMMATE_RANGE),
                m -> m != maid && m.onGround() && job.id().equals(BuilderMaidData.jobOf(m)) && ReachPlanner.columnAt(m, job) == null)) {
            Map<Item, Integer> theirs = MaidInventory.count(other);
            Map<Item, Integer> share = new LinkedHashMap<>();
            int total = 0;
            for (Map.Entry<Item, Integer> e : needed.entrySet()) {
                int surplus = (theirs.getOrDefault(e.getKey(), 0) - mine.getOrDefault(e.getKey(), 0)) / 2;
                int amount = Math.min(e.getValue(), surplus);
                if (amount > 0) {
                    share.put(e.getKey(), amount);
                    total += amount;
                }
            }
            if (total >= MIN_SHARE && total > bestTotal) {
                best = other;
                bestShare = share;
                bestTotal = total;
            }
        }
        if (best == null) return false;
        teammate = best;
        wanted = bestShare;
        return true;
    }

    /** Builder maids bound to this job around it, including ones that have not started yet. */
    private static int teamNearby(ServerLevel level, BuildJob job) {
        double range = MaidBuilderConfig.MATERIAL_SOURCE_RANGE.get();
        AABB box = new AABB(Vec3.atLowerCornerOf(job.minCorner()), Vec3.atLowerCornerOf(job.maxCorner()).add(1, 1, 1)).inflate(range);
        return level.getEntitiesOfClass(EntityMaid.class, box,
                m -> job.id().equals(BuilderMaidData.jobOf(m)) && m.getTask().getUid().equals(TaskBuilder.UID)).size();
    }

    private static boolean inRange(EntityMaid maid, BlockPos pos) {
        if (maid.isHomeModeEnable()) return true;
        LivingEntity owner = maid.getOwner();
        return owner != null && pos.closerToCenterThan(owner.position(), MaidBuilderConfig.OWNER_RANGE_WITHOUT_HOME.get());
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        route.reset();
        maid.getBrain().eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        if (container != null) BehaviorUtils.setWalkAndLookTargetMemories(maid, container, speed, 1);
        else if (teammate != null) BehaviorUtils.setWalkAndLookTargetMemories(maid, teammate, speed, 1);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return container != null || teammate != null && teammate.isAlive();
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        Vec3 target = container != null ? Vec3.atCenterOf(container) : teammate != null ? teammate.position() : null;
        if (target == null) return;
        if (maid.distanceToSqr(target) <= TAKE_DISTANCE * TAKE_DISTANCE) {
            take(level, maid);
            failedTrips = 0;
            container = null;
            teammate = null;
            arrived = true;
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        } else if (container != null && !route.planned() && stopsShort(maid, target)) {
            // The game's path finder only got part of the way (e.g. the way out of a building is
            // longer than it looks): plan the way round before she walks into the wall's dead end.
            route.plan(level, maid, container, TAKE_DISTANCE - 0.5, speed);
        } else if (maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty()) {
            if (teammate != null && maid.distanceToSqr(teammate) <= TEAMMATE_RANGE * TEAMMATE_RANGE) {
                // she keeps moving: follow her
                BehaviorUtils.setWalkAndLookTargetMemories(maid, teammate, speed, 1);
                return;
            }
            if (container != null && !maid.onGround() && !maid.isInWater()) {
                // no path is found mid-jump or mid-fall: walk on once she has landed instead of giving up
                WalkTarget waypoint = route.current(speed);
                maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, waypoint != null ? waypoint : new WalkTarget(container, speed, 1));
                return;
            }
            if (route.advance(maid, speed)) return;
            if (container != null && !route.planned() && route.plan(level, maid, container, TAKE_DISTANCE - 0.5, speed)) return;
            MaidBuilder.LOGGER.debug("Maid {} could not reach {}", maid.getUUID(), container != null ? container : teammate);
            cooldown = NOTHING_FOUND_INTERVAL;
            container = null;
            teammate = null;
            if (++failedTrips >= STUCK_TRIPS) unstick(level, maid);
        }
    }

    /**
     * Stuck where she stands (the same trip keeps failing): lets go of the block she meant to build
     * and walks to a random spot nearby, so the next try starts from somewhere else.
     */
    private void unstick(ServerLevel level, EntityMaid maid) {
        failedTrips = 0;
        UUID jobId = BuilderMaidData.jobOf(maid);
        if (jobId != null) BuildJobManager.loaded(level.getServer(), jobId).ifPresent(job -> job.release(maid.getUUID()));
        session.scaffoldStep = -1;
        Vec3 spot = net.minecraft.world.entity.ai.util.LandRandomPos.getPos(maid, WANDER_RANGE, 3);
        MaidBuilder.LOGGER.debug("Maid {} seems stuck at {}: wandering to {}", maid.getUUID(), maid.blockPosition(), spot);
        if (spot == null) return;
        maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(spot, speed, 0));
        wandering = true;
    }

    /**
     * Whether the game's path finder gave her only part of the way, ending out of reach of the
     * container. (A container is never a path's end itself, so "cannot reach" alone means nothing:
     * a path ending next to it is fine.)
     */
    private static boolean stopsShort(EntityMaid maid, Vec3 target) {
        if (!maid.getBrain().hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)) return false;
        Path path = maid.getNavigation().getPath();
        return path != null && path.getEndNode() != null
                && Vec3.atBottomCenterOf(path.getEndNode().asBlockPos()).distanceToSqr(target) > TAKE_DISTANCE * TAKE_DISTANCE;
    }

    private void take(ServerLevel level, EntityMaid maid) {
        if (wanted == null) return;
        IItemHandler from = container != null ? MaterialContainers.handler(level, container)
                : teammate != null ? teammate.getAvailableInv(false) : null;
        if (from == null) return;
        int moved = MaterialContainers.transfer(from, maid.getAvailableInv(false), wanted);
        if (moved == 0 && container != null && putBack(level, maid, from)) {
            moved = MaterialContainers.transfer(from, maid.getAvailableInv(false), wanted);
        }
        MaidBuilder.LOGGER.debug("Maid {} took {} items from {}", maid.getUUID(), moved,
                container != null ? container : "teammate " + teammate.getUUID());
        if (moved > 0) {
            maid.swing(InteractionHand.MAIN_HAND);
            if (teammate != null) teammate.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, maid.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.5f, 1.0f);
        }
        // nothing fitted or nothing was left after all: no use asking again the very next tick
        cooldown = moved > 0 ? 0 : NOTHING_FOUND_INTERVAL;
    }

    /**
     * No room for what she came for: her few slots are full of materials for later blocks (she fetches
     * only when she can build nothing with what she carries). Those go back into the container, the
     * ones needed last first, until a slot is free; true if any did.
     */
    private boolean putBack(ServerLevel level, EntityMaid maid, IItemHandler into) {
        UUID jobId = BuilderMaidData.jobOf(maid);
        BuildJob job = jobId == null ? null : BuildJobManager.loaded(level.getServer(), jobId).orElse(null);
        if (job == null || wanted == null) return false;
        java.util.List<Item> later = new java.util.ArrayList<>(job.remainingMaterials().keySet());
        IItemHandler inv = maid.getAvailableInv(false);
        boolean any = false;
        for (int i = later.size() - 1; i >= 0; i--) {
            Item item = later.get(i);
            if (wanted.containsKey(item) || item == Items.SCAFFOLDING) continue;
            for (int slot = 0; slot < inv.getSlots(); slot++) {
                if (!inv.getStackInSlot(slot).is(item)) continue;
                net.minecraft.world.item.ItemStack taken = inv.extractItem(slot, Integer.MAX_VALUE, false);
                net.minecraft.world.item.ItemStack left = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(into, taken, false);
                if (!left.isEmpty()) inv.insertItem(slot, left, false);
                if (left.getCount() < taken.getCount()) any = true;
                if (inv.getStackInSlot(slot).isEmpty()) {
                    MaidBuilder.LOGGER.debug("Maid {} put {} back to make room", maid.getUUID(), item);
                    return true;
                }
            }
        }
        return any;
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        container = null;
        teammate = null;
        wanted = null;
        route.reset();
        // After a finished trip the walk target is no longer hers: the next task may have set its own
        // already (taking it away made a maid who had just stocked up give up her walk to the scaffolding).
        if (!wandering && !arrived) maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        wandering = false;
        arrived = false;
    }
}
