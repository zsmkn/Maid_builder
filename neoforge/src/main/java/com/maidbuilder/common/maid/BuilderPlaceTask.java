package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.common.collect.ImmutableMap;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.BlockBreaker;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.common.job.ClearMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Places the claimed block once the maid is within reach (plan §5.1), breaking a wrong block there first (§5.3). */
public class BuilderPlaceTask extends Behavior<EntityMaid> {
    /** Ticks between two placements, so quick building still looks like work. */
    private static final int PLACE_INTERVAL = 5;
    private final BuilderSession session;

    public BuilderPlaceTask(BuilderSession session) {
        super(ImmutableMap.of(InitEntities.TARGET_POS.get(), MemoryStatus.VALUE_PRESENT));
        this.session = session;
    }

    static boolean inReach(EntityMaid maid, BlockPos pos) {
        return ReachPlanner.inReach(maid, pos);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        Brain<EntityMaid> brain = maid.getBrain();
        Optional<PositionTracker> target = brain.getMemory(InitEntities.TARGET_POS.get());
        if (target.isEmpty()) return false;
        BlockPos pos = target.get().currentBlockPosition();
        if (inReach(maid, pos)) return level.getGameTime() >= session.nextPlaceTime;
        if (brain.getMemory(MemoryModuleType.WALK_TARGET).isEmpty()) {
            // Walking ended (arrived as close as possible, or gave up) without getting in reach.
            job(level, maid).ifPresent(job -> {
                int index = job.claimOf(maid.getUUID());
                MaidBuilder.LOGGER.debug("Maid {} stopped walking out of reach of {} (maid at {})", maid.getUUID(), pos, maid.blockPosition());
                if (index >= 0 && job.pos(index).equals(pos)) job.recordAttempt(index, level.getGameTime());
                job.release(maid.getUUID());
            });
            stopBreaking(level, maid, session);
            brain.eraseMemory(InitEntities.TARGET_POS.get());
        }
        return false;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        BlockPos pos = maid.getBrain().getMemory(InitEntities.TARGET_POS.get())
                .map(PositionTracker::currentBlockPosition).orElse(null);
        Optional<BuildJob> found = job(level, maid);
        boolean working = false;
        try {
            if (pos == null || found.isEmpty() || found.get().isCancelled()) return;
            BuildJob job = found.get();
            int index = job.claimOf(maid.getUUID());
            if (index < 0 || !job.pos(index).equals(pos) || job.status(index) != BuildJob.PENDING) return;
            working = workStep(level, maid, job, index, session, PLACE_INTERVAL);
            session.nextPlaceTime = gameTime + PLACE_INTERVAL;
        } finally {
            if (!working) {
                found.ifPresent(job -> job.release(maid.getUUID()));
                maid.getBrain().eraseMemory(InitEntities.TARGET_POS.get());
                stopBreaking(level, maid, session);
            }
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }

    /**
     * Works on a pending step the maid has claimed and can reach: places its block, or breaks what
     * is in the way (or, for a clear step, what stands in the schematic's air). Also used from the
     * top of a scaffold. Called every {@code interval} ticks while she keeps at it.
     *
     * @return true while she is still breaking a block: keep the claim and call again
     */
    static boolean workStep(ServerLevel level, EntityMaid maid, BuildJob job, int index, BuilderSession session, int interval) {
        boolean working = work(level, maid, job, index, session, interval);
        if (!working) stopBreaking(level, maid, session);
        if (job.isComplete() && !job.isCancelled()) notifyOwner(level, job);
        return working;
    }

    private static boolean work(ServerLevel level, EntityMaid maid, BuildJob job, int index, BuilderSession session, int interval) {
        BlockPos pos = job.pos(index);
        if (job.isSatisfied(index, level.getBlockState(pos))) {
            job.setStatus(index, BuildJob.DONE);
            return false;
        }
        if (job.isClearStep(index)) return breakFor(level, maid, job, index, BlockBreaker.primaryPos(level, pos), session, interval);

        BlockState target = job.target(index);
        List<BuildJob.Requirement> requirements = job.requirements(index);
        if (!MaidInventory.has(maid, requirements)) return false;
        if (job.clearMode() != ClearMode.OFF) {
            BlockPos blocker = obstruction(level, job, pos, target);
            if (blocker != null) return breakFor(level, maid, job, index, blocker, session, interval);
        }
        if (!level.isUnobstructed(target, pos, CollisionContext.empty())) return false; // an entity stands there; retry later

        BlockPlacer.Result result = BlockPlacer.placeAsEntity(level, pos, target, maid);
        MaidBuilder.LOGGER.debug("Maid {} placing {} at {}: {}", maid.getUUID(), target, pos, result);
        switch (result) {
            case PLACED -> {
                MaidInventory.consume(maid, requirements);
                maid.swing(InteractionHand.MAIN_HAND);
                job.setStatus(index, BuildJob.DONE);
                job.recordPlaced(maid.getUUID());
            }
            case CANNOT_SURVIVE -> job.recordAttempt(index, level.getGameTime());
            case OBSTRUCTED -> job.setStatus(index, BuildJob.NEEDS_PLAYER);
            case DENIED -> job.setStatus(index, BuildJob.FAILED);
        }
        return false;
    }

    /** The first cell the target (or its other half) would go in that holds a block which is not simply replaced. */
    @Nullable
    private static BlockPos obstruction(ServerLevel level, BuildJob job, BlockPos pos, BlockState target) {
        for (BlockPlacer.Part part : BlockPlacer.partsOf(pos, target)) {
            if (!level.getBlockState(part.pos()).canBeReplaced() && !job.isScaffold(part.pos())) {
                return BlockBreaker.primaryPos(level, part.pos());
            }
        }
        return null;
    }

    /**
     * Puts {@code interval} ticks of work into breaking the block at {@code target}, showing the
     * cracks, and breaks it once enough work is done; its drops go to her inventory.
     *
     * @return true while the step needs more work (still breaking, or broken and now to be placed)
     */
    private static boolean breakFor(ServerLevel level, EntityMaid maid, BuildJob job, int index, BlockPos target,
                                    BuilderSession session, int interval) {
        BlockState state = level.getBlockState(target);
        if (BlockBreaker.isCleared(state)) return !job.isClearStep(index);
        if (target.equals(maid.getOnPos()) || maid.getBoundingBox().intersects(new AABB(target))) {
            job.recordAttempt(index, level.getGameTime()); // never dig away her own footing; try from elsewhere later
            return false;
        }
        if (!BlockBreaker.breakable(level, target, state)) {
            MaidBuilder.LOGGER.debug("Maid {} leaves {} at {} to the player", maid.getUUID(), state, target);
            job.setStatus(index, BuildJob.NEEDS_PLAYER);
            return false;
        }
        if (!target.equals(session.breaking)) {
            stopBreaking(level, maid, session);
            session.breaking = target;
        }
        session.breakProgress += interval;
        int needed = BlockBreaker.breakTicks(level, target, state);
        maid.swing(InteractionHand.MAIN_HAND);
        maid.getLookControl().setLookAt(Vec3.atCenterOf(target));
        if (session.breakProgress < needed) {
            level.destroyBlockProgress(maid.getId(), target, Math.min(9, session.breakProgress * 10 / needed));
            SoundType sound = state.getSoundType(level, target, maid);
            level.playSound(null, target, sound.getHitSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F);
            return true;
        }
        stopBreaking(level, maid, session);
        BlockBreaker.Result result = BlockBreaker.breakAsEntity(level, target, maid, job.owner(), maid.getMainHandItem(),
                stack -> MaidInventory.give(maid, stack));
        MaidBuilder.LOGGER.debug("Maid {} breaking {} at {}: {}", maid.getUUID(), state, target, result);
        switch (result) {
            case BROKEN -> {
                if (!job.isClearStep(index)) return true; // place the right block on the next call
                if (job.isSatisfied(index, level.getBlockState(job.pos(index)))) job.setStatus(index, BuildJob.DONE);
            }
            case PROTECTED -> job.setStatus(index, BuildJob.NEEDS_PLAYER);
            case DENIED -> job.setStatus(index, BuildJob.FAILED);
        }
        return false;
    }

    /** Forgets the block she was breaking and removes its cracks. */
    static void stopBreaking(ServerLevel level, EntityMaid maid, BuilderSession session) {
        if (session.breaking != null) level.destroyBlockProgress(maid.getId(), session.breaking, -1);
        session.breaking = null;
        session.breakProgress = 0;
    }

    private static Optional<BuildJob> job(ServerLevel level, EntityMaid maid) {
        UUID id = BuilderMaidData.jobOf(maid);
        return id == null ? Optional.empty() : BuildJobManager.loaded(level.getServer(), id);
    }

    private static void notifyOwner(ServerLevel level, BuildJob job) {
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(job.owner());
        if (owner == null) return;
        int left = job.count(BuildJob.NEEDS_PLAYER) + job.count(BuildJob.FAILED);
        owner.sendSystemMessage(left == 0
                ? Component.translatable("message.maidbuilder.job.complete", job.schematicName())
                : Component.translatable("message.maidbuilder.job.complete_with_leftovers", job.schematicName(), left));
    }
}
