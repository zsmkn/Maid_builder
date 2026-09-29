package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.task.MaidCheckRateTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.common.collect.ImmutableMap;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Picks the next block of the bound job that the maid has materials for, claims it and walks
 * to a spot within reach of it. Scanning is bounded ({@code maxScanPerSearch}) and runs only
 * every few ticks. When no walkable spot inside the job's work area reaches the block, it is
 * handed to {@link BuilderScaffoldTask}.
 */
public class BuilderFindTargetTask extends MaidCheckRateTask {
    private static final int IDLE_RECHECK_TICKS = 40;
    private final BuilderSession session;
    private final float speed;

    public BuilderFindTargetTask(BuilderSession session, float speed) {
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT,
                InitEntities.TARGET_POS.get(), MemoryStatus.VALUE_ABSENT));
        this.session = session;
        this.speed = speed;
        this.setMaxCheckRate(10);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        // Paths are only computed for mobs standing on something (or swimming): searching mid-jump
        // would find no way to any block and count failed tries.
        return BuilderMaidData.jobOf(maid) != null && session.idle() && (maid.onGround() || maid.isInWater())
                && super.checkExtraStartConditions(level, maid);
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        UUID jobId = BuilderMaidData.jobOf(maid);
        Optional<BuildJob> found = jobId == null ? Optional.empty() : BuildJobManager.loaded(level.getServer(), jobId);
        if (found.isEmpty() || !found.get().dimension().equals(level.dimension())) {
            setNextCheckTickCount(IDLE_RECHECK_TICKS * 5);
            return;
        }
        BuildJob job = found.get();
        job.release(maid.getUUID());
        if (job.isFinished()) {
            setNextCheckTickCount(IDLE_RECHECK_TICKS * 5);
            return;
        }

        job.touchWorker(maid.getUUID(), gameTime);
        Map<Item, Integer> inventory = MaidInventory.count(maid);
        LivingEntity owner = maid.getOwner();
        List<BlockPos> others = job.claimedByOthers(maid.getUUID());
        boolean homeMode = maid.isHomeModeEnable();
        double ownerRange = MaidBuilderConfig.OWNER_RANGE_WITHOUT_HOME.get();
        AABB self = maid.getBoundingBox();

        int index = job.findBest(level.getGameTime(), i -> {
            BlockPos pos = job.pos(i);
            if (!level.isLoaded(pos)) return false;
            if (BlockPlacer.matches(level.getBlockState(pos), job.target(i))) {
                job.setStatus(i, BuildJob.DONE);
                return false;
            }
            if (!homeMode && (owner == null || !pos.closerToCenterThan(owner.position(), ownerRange))) return false;
            if (self.intersects(new AABB(pos)) || job.isScaffold(pos) || session.isDeferred(i, gameTime)) return false;
            return MaidInventory.has(inventory, job.requirements(i));
        }, i -> score(maid, job.pos(i), others), MaidBuilderConfig.MAX_SCAN_PER_SEARCH.get());

        if (index < 0 || !job.claim(index, maid.getUUID())) {
            setNextCheckTickCount(IDLE_RECHECK_TICKS);
            return;
        }
        BlockPos pos = job.pos(index);
        setNextCheckTickCount(0); // look for the next block as soon as this one is done
        if (BuilderPlaceTask.inReach(maid, pos)) {
            maid.getBrain().setMemory(InitEntities.TARGET_POS.get(), new BlockPosTracker(pos));
            maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(pos));
            return;
        }
        BlockPos stand = ReachPlanner.findStand(level, maid, job, pos);
        if (stand != null) {
            maid.getBrain().setMemory(InitEntities.TARGET_POS.get(), new BlockPosTracker(pos));
            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(stand, speed, 0));
            maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(pos));
        } else if (MaidBuilderConfig.USE_SCAFFOLDING.get()) {
            session.scaffoldStep = index; // keeps the claim; BuilderScaffoldTask takes over
        } else {
            job.recordAttempt(index, level.getGameTime());
            job.release(maid.getUUID());
            setNextCheckTickCount(IDLE_RECHECK_TICKS);
        }
    }

    /** Distance to walk, plus a penalty next to blocks other maids are working on so the team spreads out. */
    static double score(EntityMaid maid, BlockPos pos, List<BlockPos> others) {
        double s = maid.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos));
        for (BlockPos other : others) {
            if (other.distSqr(pos) <= CROWD_DISTANCE * CROWD_DISTANCE) s += CROWD_PENALTY;
        }
        return s;
    }

    private static final int CROWD_DISTANCE = 3;
    private static final double CROWD_PENALTY = 64;
}
