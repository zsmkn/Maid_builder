package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.MaterialContainers;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reaching high blocks (plan phase 7). When {@link BuilderFindTargetTask} finds no walkable spot
 * within reach of a block inside the job's work area, this task:
 * <ol>
 *   <li>picks a column next to the block ({@link ReachPlanner#planColumn}), reusing existing scaffolding;</li>
 *   <li>walks to its foot and stacks scaffolding from her inventory (like a player extending a column from below);</li>
 *   <li>climbs it (scaffolding is climbable: she jumps inside it);</li>
 *   <li>builds every block she has materials for within reach of the top;</li>
 *   <li>sneaks to slide back down (sneaking entities fall through scaffolding).</li>
 * </ol>
 * The scaffolding stays up for later blocks and is taken down by {@link BuilderTeardownTask} once
 * the job is complete. Without scaffolding she asks her owner for some (or fetches it from a
 * material container) and carries on with other blocks meanwhile.
 */
public class BuilderScaffoldTask extends Behavior<EntityMaid> {
    private enum Stage { WALK, BUILD, CLIMB, WORK, DESCEND, DONE }

    private static final int MAX_RUN_TICKS = 20 * 60 * 5;
    private static final int PLACE_INTERVAL = 4;
    private static final int WORK_INTERVAL = 5;
    private static final int WALK_TIMEOUT = 20 * 20;
    private static final int WORK_TIMEOUT = 20 * 60;
    private static final int WAIT_FOR_SCAFFOLDING = 20 * 30;
    private static final int NOTIFY_INTERVAL = 20 * 60;
    private static final int FETCH_AT_LEAST = 16;
    private static final int COLUMN_BUSY_WAIT = 100;

    private final BuilderSession session;
    private final float speed;
    private Stage stage = Stage.DONE;
    @Nullable
    private BuildJob job;
    /** Foot of the column and the height she stands at on top of it. */
    private BlockPos base = BlockPos.ZERO;
    private int standY;
    private int stageTicks;
    private UUID maidId = new UUID(0, 0);

    public BuilderScaffoldTask(BuilderSession session, float speed) {
        super(ImmutableMap.of(), MAX_RUN_TICKS);
        this.session = session;
        this.speed = speed;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (session.busy) return false;
        Optional<BuildJob> found = job(level, maid);
        if (found.isEmpty()) {
            session.scaffoldStep = -1;
            return false;
        }
        // Still on (or in) a column, e.g. after a reload or when her schedule interrupted her: get down first.
        return session.scaffoldStep >= 0 || ReachPlanner.columnAt(maid, found.get()) != null;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        session.busy = true;
        maidId = maid.getUUID();
        stage = Stage.DONE;
        job = job(level, maid).orElse(null);
        if (job == null) return;

        List<BlockPos> current = ReachPlanner.columnAt(maid, job);
        if (current != null) {
            session.scaffoldStep = -1;
            base = current.getFirst();
            standY = current.getLast().getY() + 1;
            enter(onTop(maid) ? Stage.WORK : Stage.DESCEND);
            MaidBuilder.LOGGER.debug("Maid {} is on scaffolding at {} (y {}), stage {}", maid.getUUID(), base, maid.getY(), stage);
            return;
        }

        int index = session.scaffoldStep;
        session.scaffoldStep = -1;
        if (index < 0 || job.isFinished() || job.claimOf(maid.getUUID()) != index || job.status(index) != BuildJob.PENDING) return;
        BlockPos target = job.pos(index);
        ReachPlanner.Column column = ReachPlanner.planColumn(level, maid, job, target);
        if (column == null && ReachPlanner.otherColumnsInUse(job, maid)) {
            // maybe only the columns her teammates are on would do: let them build it
            session.defer(index, gameTime + COLUMN_BUSY_WAIT);
            return;
        }
        if (column == null) {
            MaidBuilder.LOGGER.debug("Maid {} found neither a way nor room for scaffolding to reach {}", maid.getUUID(), target);
            job.recordAttempt(index, gameTime);
            return;
        }
        if (!job.claimColumn(column.key(), maid.getUUID())) {
            session.defer(index, gameTime + COLUMN_BUSY_WAIT);
            return;
        }
        int have = MaidInventory.count(maid, Items.SCAFFOLDING);
        if (have < column.newBlocks()) {
            session.defer(index, gameTime + WAIT_FOR_SCAFFOLDING);
            if (containersHaveScaffolding(level, maid, job)) {
                session.scaffoldingWanted = Math.max(column.newBlocks() - have, FETCH_AT_LEAST);
            } else {
                askForScaffolding(level, maid, target, gameTime);
            }
            return;
        }
        base = column.base();
        standY = column.standY();
        MaidBuilder.LOGGER.debug("Maid {} puts up scaffolding at {} (stand y {}, {} new) to reach {}",
                maid.getUUID(), base, standY, column.newBlocks(), target);
        if (maid.distanceToSqr(Vec3.atBottomCenterOf(base)) <= 2.0) {
            enter(Stage.BUILD);
        } else {
            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(base, speed, 1));
            enter(Stage.WALK);
        }
    }

    private void enter(Stage next) {
        if (next != stage && job != null) MaidBuilder.LOGGER.debug("Maid {} scaffold stage {} -> {} after {} ticks", maidId, stage, next, stageTicks);
        stage = next;
        stageTicks = 0;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return stage != Stage.DONE && job != null;
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        if (job == null) return;
        stageTicks++;
        switch (stage) {
            case WALK -> walk(maid, gameTime);
            case BUILD -> build(level, maid, gameTime);
            case CLIMB -> climb(maid, gameTime);
            case WORK -> work(level, maid, gameTime);
            case DESCEND -> descend(maid);
            case DONE -> {
            }
        }
    }

    private void walk(EntityMaid maid, long gameTime) {
        if (nearBase(maid)) {
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            enter(Stage.BUILD);
        } else if (stageTicks > WALK_TIMEOUT || stageTicks > 5 && maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty()) {
            MaidBuilder.LOGGER.debug("Maid {} could not walk to the scaffolding spot {}", maid.getUUID(), base);
            failClaimed(maid, gameTime);
            enter(Stage.DONE);
        }
    }

    private boolean nearBase(EntityMaid maid) {
        return Math.abs(maid.getY() - base.getY()) <= 1.0
                && maid.position().subtract(Vec3.atBottomCenterOf(base)).horizontalDistanceSqr() <= 1.6 * 1.6;
    }

    /** Stacks one scaffold every few ticks, bottom up, like a player clicking the column's foot. */
    private void build(ServerLevel level, EntityMaid maid, long gameTime) {
        if (job.isFinished()) {
            enter(Stage.DONE);
            return;
        }
        if (stageTicks % PLACE_INTERVAL != 0) return;
        maid.getLookControl().setLookAt(Vec3.atCenterOf(base));
        for (int y = base.getY(); y < standY; y++) {
            BlockPos cell = base.atY(y);
            if (level.getBlockState(cell).is(Blocks.SCAFFOLDING)) continue;
            if (!level.getBlockState(cell).canBeReplaced()) {
                failClaimed(maid, gameTime); // something got in the way since planning
                enter(Stage.DONE);
                return;
            }
            if (MaidInventory.count(maid, Items.SCAFFOLDING) < 1) {
                releaseClaimed(maid, gameTime + WAIT_FOR_SCAFFOLDING);
                enter(Stage.DONE);
                return;
            }
            MaidInventory.consume(maid, List.of(new BuildJob.Requirement(Items.SCAFFOLDING, 1)));
            level.setBlock(cell, ReachPlanner.scaffoldState(level, cell), Block.UPDATE_ALL);
            job.addScaffold(cell);
            maid.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, cell, SoundEvents.SCAFFOLDING_PLACE, SoundSource.BLOCKS, 1.0f, 0.8f);
            return;
        }
        enter(Stage.CLIMB);
    }

    /**
     * TLM only lets a maid climb while she follows a path with a vertical segment, so the motion is
     * driven here directly (as TLM's own climb task does): walk into the column's foot, then rise
     * centred in it until she stands on top.
     */
    private void climb(EntityMaid maid, long gameTime) {
        holdStill(maid);
        if (onTop(maid)) {
            enter(Stage.WORK);
            return;
        }
        maid.resetFallDistance();
        if (ReachPlanner.overColumn(maid, base) && maid.getY() >= base.getY() - 0.5) {
            if (maid.getY() < standY) maid.setDeltaMovement(towardCentre(maid, 0.25));
        } else {
            Vec3 foot = Vec3.atBottomCenterOf(base);
            maid.getMoveControl().setWantedPosition(foot.x, foot.y, foot.z, speed);
        }
        if (stageTicks > 60 + 10 * (standY - base.getY())) {
            MaidBuilder.LOGGER.debug("Maid {} could not climb the scaffolding at {} (stand y {}): at {}",
                    maid.getUUID(), base, standY, maid.position());
            failClaimed(maid, gameTime);
            enter(Stage.DESCEND);
        }
    }

    /** Vertical speed {@code vy} plus a small pull towards the column's centre line. */
    private Vec3 towardCentre(EntityMaid maid, double vy) {
        return new Vec3((base.getX() + 0.5 - maid.getX()) * 0.3, vy, (base.getZ() + 0.5 - maid.getZ()) * 0.3);
    }

    /** On top: build what is in reach (the claimed block first), then come down. */
    private void work(ServerLevel level, EntityMaid maid, long gameTime) {
        holdStill(maid);
        if (!onTop(maid) || stageTicks > WORK_TIMEOUT || job.isFinished()) {
            job.release(maid.getUUID());
            enter(Stage.DESCEND);
            return;
        }
        if (stageTicks % WORK_INTERVAL != 0) return;
        UUID id = maid.getUUID();
        int index = job.claimOf(id);
        if (index >= 0 && job.status(index) == BuildJob.PENDING && ReachPlanner.inReach(maid, job.pos(index))) {
            BuilderPlaceTask.placeStep(level, maid, job, index);
            job.release(id);
            return;
        }
        job.release(id);
        Map<net.minecraft.world.item.Item, Integer> inventory = MaidInventory.count(maid);
        AABB self = maid.getBoundingBox();
        int next = job.findNext(gameTime, i -> {
            BlockPos pos = job.pos(i);
            return ReachPlanner.inReach(maid, pos) && !self.intersects(new AABB(pos))
                    && !pos.equals(base.atY(standY - 1)) && MaidInventory.has(inventory, job.requirements(i));
        }, MaidBuilderConfig.MAX_SCAN_PER_SEARCH.get());
        if (next >= 0 && job.claim(next, id)) {
            maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(job.pos(next)));
        } else {
            enter(Stage.DESCEND);
        }
    }

    /** Sneaking makes scaffolding let her through; the fall is slowed so she lands softly. */
    private void descend(EntityMaid maid) {
        holdStill(maid);
        maid.setShiftKeyDown(true);
        maid.resetFallDistance();
        boolean over = ReachPlanner.overColumn(maid, base);
        if (over && !maid.onGround()) maid.setDeltaMovement(towardCentre(maid, -0.25));
        boolean down = maid.onGround() && (maid.getY() < base.getY() + 0.5 || !over);
        if (down || stageTicks > 60 + 10 * (standY - base.getY())) {
            maid.setShiftKeyDown(false);
            enter(Stage.DONE);
        }
    }

    private boolean onTop(EntityMaid maid) {
        return maid.onGround() && maid.getY() >= standY - 0.05 && ReachPlanner.overColumn(maid, base);
    }

    /** Keep the path follower and other walk requests from pulling her off the column. */
    private static void holdStill(EntityMaid maid) {
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    private void failClaimed(EntityMaid maid, long gameTime) {
        int index = job.claimOf(maid.getUUID());
        if (index >= 0) job.recordAttempt(index, gameTime);
        job.release(maid.getUUID());
    }

    private void releaseClaimed(EntityMaid maid, long until) {
        int index = job.claimOf(maid.getUUID());
        if (index >= 0) session.defer(index, until);
        job.release(maid.getUUID());
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.setShiftKeyDown(false);
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        if (job != null) {
            job.release(maid.getUUID());
            job.releaseColumns(maid.getUUID());
        }
        job = null;
        stage = Stage.DONE;
        session.busy = false;
    }

    private static boolean containersHaveScaffolding(ServerLevel level, EntityMaid maid, BuildJob job) {
        double range = MaidBuilderConfig.MATERIAL_SOURCE_RANGE.get();
        for (Map.Entry<BlockPos, IItemHandler> e : MaterialContainers.distinct(level, job.materialSources()).entrySet()) {
            if (maid.distanceToSqr(Vec3.atCenterOf(e.getKey())) > range * range) continue;
            if (MaterialContainers.containsAny(e.getValue(), Map.of(Items.SCAFFOLDING, 1))) return true;
        }
        return false;
    }

    private void askForScaffolding(ServerLevel level, EntityMaid maid, BlockPos target, long gameTime) {
        if (session.lastNotify != Long.MIN_VALUE && gameTime - session.lastNotify < NOTIFY_INTERVAL) return;
        session.lastNotify = gameTime;
        LivingEntity owner = maid.getOwner();
        if (owner instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.translatable("message.maidbuilder.maid.needs_scaffolding",
                    maid.getDisplayName(), target.toShortString()));
        }
    }

    static Optional<BuildJob> job(ServerLevel level, EntityMaid maid) {
        UUID id = BuilderMaidData.jobOf(maid);
        if (id == null) return Optional.empty();
        return BuildJobManager.loaded(level.getServer(), id).filter(j -> j.dimension().equals(level.dimension()));
    }
}
