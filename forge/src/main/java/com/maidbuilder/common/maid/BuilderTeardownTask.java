package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Once the job is complete, the maid walks to each scaffolding column the builders put up and
 * takes it down from its foot (as breaking a column's bottom brings down the whole column, and
 * with it the scaffolding hanging from it), keeping the scaffolding. A column she cannot reach
 * after a few tries is left to the owner.
 */
public class BuilderTeardownTask extends Behavior<EntityMaid> {
    private static final int CHECK_INTERVAL = 40;
    private static final int MAX_RUN_TICKS = 20 * 30;
    private static final int MAX_FAILURES = 3;

    private final BuilderSession session;
    private final float speed;
    private final Map<Long, Integer> failures = new HashMap<>();
    private int cooldown;
    @Nullable
    private BuildJob job;
    @Nullable
    private List<BlockPos> column;
    private int ticks;

    public BuilderTeardownTask(BuilderSession session, float speed) {
        super(ImmutableMap.of(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT), MAX_RUN_TICKS);
        this.session = session;
        this.speed = speed;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        if (--cooldown > 0 || !session.idle()) return false;
        cooldown = CHECK_INTERVAL;
        Optional<BuildJob> found = BuilderScaffoldTask.job(level, maid);
        if (found.isEmpty() || !found.get().isFinished() || found.get().scaffolds().isEmpty()) return false;
        BuildJob j = found.get();
        if (ReachPlanner.columnAt(maid, j) != null) return false; // the scaffold task gets her down first

        // scaffolding that is gone already (broken, or fell when its support went) needs no trip
        for (BlockPos pos : List.copyOf(j.scaffolds())) {
            if (level.isLoaded(pos) && !level.getBlockState(pos).is(Blocks.SCAFFOLDING)) j.removeScaffold(pos);
        }
        if (j.scaffolds().isEmpty()) return false;
        List<Map.Entry<Long, List<BlockPos>>> columns = new ArrayList<>(j.scaffoldColumns().entrySet());
        // hanging scaffolding comes down with the column it hangs from; only if none is left are hanging ones visited
        if (columns.stream().anyMatch(e -> ReachPlanner.grounded(level, e.getValue().get(0)))) {
            columns.removeIf(e -> !ReachPlanner.grounded(level, e.getValue().get(0)));
        }
        columns.sort(Comparator.comparingDouble(e -> maid.distanceToSqr(Vec3.atBottomCenterOf(e.getValue().get(0)))));
        for (Map.Entry<Long, List<BlockPos>> e : columns) {
            if (j.claimColumn(e.getKey(), maid.getUUID())) {
                job = j;
                column = e.getValue();
                return true;
            }
        }
        return false;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        session.busy = true;
        ticks = 0;
        if (column != null && !nearFoot(maid)) {
            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(column.get(0), speed, 1));
        }
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return column != null && job != null;
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        if (column == null || job == null) return;
        ticks++;
        if (nearFoot(maid)) {
            takeDown(level, maid, job, column);
            column = null;
        } else if (ticks > 5 && maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty()) {
            long key = BuildJob.columnKey(column.get(0));
            int n = failures.merge(key, 1, Integer::sum);
            MaidBuilder.LOGGER.debug("Maid {} could not reach scaffolding at {} ({} tries)", maid.getUUID(), column.get(0), n);
            if (n >= MAX_FAILURES) {
                for (BlockPos pos : column) job.removeScaffold(pos);
                failures.remove(key);
                notifyOwner(level, job, Component.translatable("message.maidbuilder.scaffold.left",
                        maid.getDisplayName(), column.get(0).toShortString()));
            }
            column = null;
        }
    }

    private boolean nearFoot(EntityMaid maid) {
        return column != null && ReachPlanner.inReach(maid, column.get(0));
    }

    private void takeDown(ServerLevel level, EntityMaid maid, BuildJob job, List<BlockPos> column) {
        int taken = 0;
        for (int i = column.size() - 1; i >= 0; i--) job.removeScaffold(column.get(i));
        // what hung from this column goes too, farthest first, before the column itself (so nothing falls)
        List<BlockPos> loose = unsupported(level, job);
        List<BlockPos> remove = new ArrayList<>(loose);
        for (BlockPos pos : remove) job.removeScaffold(pos);
        for (int i = column.size() - 1; i >= 0; i--) remove.add(column.get(i));
        for (BlockPos pos : remove) {
            if (level.getBlockState(pos).is(Blocks.SCAFFOLDING)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                taken++;
            }
        }
        if (taken > 0) {
            maid.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, column.get(0), SoundEvents.SCAFFOLDING_BREAK, SoundSource.BLOCKS, 1.0f, 1.0f);
            MaidInventory.give(maid, new ItemStack(Items.SCAFFOLDING, taken));
        }
        MaidBuilder.LOGGER.debug("Maid {} took down {} scaffolding at {}", maid.getUUID(), taken, column.get(0));
        if (job.scaffolds().isEmpty()) {
            if (job.isCancelled()) {
                BuildJobManager.data(level.getServer()).remove(job.id());
                notifyOwner(level, job, Component.translatable("message.maidbuilder.job.cancel_done", job.schematicName()));
            } else {
                notifyOwner(level, job, Component.translatable("message.maidbuilder.scaffold.cleared", job.schematicName()));
            }
        }
    }

    /**
     * The job's scaffolding that nothing holds up any more: as in the game, a scaffold stands on
     * something solid or on a held scaffold, or hangs at most {@link ReachPlanner#MAX_HANG} blocks
     * sideways from one. Farthest from support first.
     */
    private static List<BlockPos> unsupported(ServerLevel level, BuildJob job) {
        Set<BlockPos> cells = job.scaffolds();
        Map<BlockPos, Integer> hang = new HashMap<>();
        java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
        List<BlockPos> sorted = new ArrayList<>(cells);
        sorted.sort(Comparator.comparingInt(BlockPos::getY));
        for (BlockPos pos : sorted) {
            if (!cells.contains(pos.below()) && ReachPlanner.grounded(level, pos)) {
                hang.put(pos, 0);
                open.add(pos);
            }
        }
        // breadth first over "up" (same distance) and "sideways" (+1) until nothing improves
        while (!open.isEmpty()) {
            BlockPos pos = open.poll();
            int d = hang.get(pos);
            BlockPos up = pos.above();
            if (cells.contains(up) && hang.getOrDefault(up, Integer.MAX_VALUE) > d) {
                hang.put(up, d);
                open.addFirst(up);
            }
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos side = pos.relative(dir);
                if (d + 1 <= ReachPlanner.MAX_HANG && cells.contains(side) && hang.getOrDefault(side, Integer.MAX_VALUE) > d + 1) {
                    hang.put(side, d + 1);
                    open.add(side);
                }
            }
        }
        List<BlockPos> loose = new ArrayList<>();
        for (BlockPos pos : cells) if (!hang.containsKey(pos)) loose.add(pos);
        // top down, so a scaffold is never removed from under another one of the list
        loose.sort(Comparator.comparingInt((BlockPos p) -> -p.getY()));
        return loose;
    }

    private static void notifyOwner(ServerLevel level, BuildJob job, Component message) {
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(job.owner());
        if (owner != null) owner.sendSystemMessage(message);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        if (job != null) job.releaseColumns(maid.getUUID());
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        job = null;
        column = null;
        cooldown = 0;
        session.busy = false;
    }
}
