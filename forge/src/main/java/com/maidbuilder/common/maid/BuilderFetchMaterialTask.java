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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.IItemHandler;

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
    private static final int MAX_RUN_TICKS = 600;
    private static final double TAKE_DISTANCE = 2.5;
    private static final double TEAMMATE_RANGE = 32;
    /** Smallest amount worth walking to a teammate for. */
    private static final int MIN_SHARE = 4;

    private final BuilderSession session;
    private final float speed;
    private int cooldown;
    @Nullable
    private BlockPos container;
    @Nullable
    private EntityMaid teammate;
    @Nullable
    private Map<Item, Integer> wanted;

    public BuilderFetchMaterialTask(BuilderSession session, float speed) {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT,
                InitEntities.TARGET_POS.get(), MemoryStatus.VALUE_ABSENT), MAX_RUN_TICKS);
        this.session = session;
        this.speed = speed;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (--cooldown > 0 || session.busy) return false;
        cooldown = CHECK_INTERVAL;
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
        // Still something she can build with what she carries? Then keep building.
        if (job.findNext(now, i -> MaidInventory.has(inventory, job.requirements(i)) && inRange(maid, job.pos(i)), LOOKAHEAD_STEPS) >= 0) {
            return false;
        }
        // Only her share of what is left (at most LOOKAHEAD_STEPS): otherwise the first maid at the
        // chest empties it and the others stand idle; a bigger share means fewer trips.
        int team = Math.max(job.teamSize(now), teamNearby(level, job));
        int share = Math.max(MIN_LOOKAHEAD, Math.min(LOOKAHEAD_STEPS, (job.count(BuildJob.PENDING) + team - 1) / team));
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
        for (Map.Entry<BlockPos, IItemHandler> e : MaterialContainers.distinct(level, job.materialSources()).entrySet()) {
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
            container = null;
            teammate = null;
        } else if (maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty()) {
            if (teammate != null && maid.distanceToSqr(teammate) <= TEAMMATE_RANGE * TEAMMATE_RANGE) {
                // she keeps moving: follow her
                BehaviorUtils.setWalkAndLookTargetMemories(maid, teammate, speed, 1);
                return;
            }
            MaidBuilder.LOGGER.debug("Maid {} could not reach {}", maid.getUUID(), container != null ? container : teammate);
            cooldown = NOTHING_FOUND_INTERVAL;
            container = null;
            teammate = null;
        }
    }

    private void take(ServerLevel level, EntityMaid maid) {
        if (wanted == null) return;
        IItemHandler from = container != null ? MaterialContainers.handler(level, container)
                : teammate != null ? teammate.getAvailableInv(false) : null;
        if (from == null) return;
        int moved = MaterialContainers.transfer(from, maid.getAvailableInv(false), wanted);
        MaidBuilder.LOGGER.debug("Maid {} took {} items from {}", maid.getUUID(), moved,
                container != null ? container : "teammate " + teammate.getUUID());
        if (moved > 0) {
            maid.swing(InteractionHand.MAIN_HAND);
            if (teammate != null) teammate.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, maid.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.5f, 1.0f);
        }
        cooldown = 0;
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        container = null;
        teammate = null;
        wanted = null;
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }
}
