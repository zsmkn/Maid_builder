package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.google.common.collect.ImmutableMap;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Places the claimed block once the maid is within reach (plan §5.1). */
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
            brain.eraseMemory(InitEntities.TARGET_POS.get());
        }
        return false;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        BlockPos pos = maid.getBrain().getMemory(InitEntities.TARGET_POS.get())
                .map(PositionTracker::currentBlockPosition).orElse(null);
        Optional<BuildJob> found = job(level, maid);
        try {
            if (pos == null || found.isEmpty() || found.get().isCancelled()) return;
            BuildJob job = found.get();
            int index = job.claimOf(maid.getUUID());
            if (index < 0 || !job.pos(index).equals(pos) || job.status(index) != BuildJob.PENDING) return;
            placeStep(level, maid, job, index);
            session.nextPlaceTime = gameTime + PLACE_INTERVAL;
        } finally {
            found.ifPresent(job -> job.release(maid.getUUID()));
            maid.getBrain().eraseMemory(InitEntities.TARGET_POS.get());
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        }
    }

    /** Places a pending step the maid has claimed and can reach; also used from the top of a scaffold. */
    static void placeStep(ServerLevel level, EntityMaid maid, BuildJob job, int index) {
        place(level, maid, job, index, job.pos(index));
        if (job.isComplete() && !job.isCancelled()) notifyOwner(level, job);
    }

    private static void place(ServerLevel level, EntityMaid maid, BuildJob job, int index, BlockPos pos) {
        BlockState target = job.target(index);
        if (BlockPlacer.matches(level.getBlockState(pos), target)) {
            job.setStatus(index, BuildJob.DONE);
            return;
        }
        List<BuildJob.Requirement> requirements = job.requirements(index);
        if (!MaidInventory.has(maid, requirements)) return;
        if (!level.isUnobstructed(target, pos, CollisionContext.empty())) return; // an entity stands there; retry later

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
