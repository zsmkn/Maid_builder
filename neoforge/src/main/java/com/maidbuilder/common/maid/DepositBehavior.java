package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import com.maidbuilder.common.territory.WorkplaceActions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Walks to the nearest warehouse container with room for her products, puts them in, and repeats
 * with the next container until nothing is left (or every container is full). Gives up after
 * {@link #MAX_TICKS}. Ending only clears the depositing flag; {@link WorkplaceHandler} switches the
 * task back outside the brain tick.
 */
public class DepositBehavior extends Behavior<EntityMaid> {
    private static final int MAX_TICKS = 20 * 60;
    private static final double REACH_SQR = 3.0 * 3.0;
    /** Where each depositing maid is heading, for {@link WorkplaceHandler}'s range override. */
    static final Map<EntityMaid, BlockPos> TARGETS = new WeakHashMap<>();

    private final float speed;
    private final Set<BlockPos> full = new HashSet<>();
    @Nullable
    private BlockPos target;
    private int ticks;
    private int moved;

    public DepositBehavior(float speed) {
        super(ImmutableMap.of(), MAX_TICKS);
        this.speed = speed;
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return WorkplaceMaidData.of(maid).depositing();
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        ticks = 0;
        moved = 0;
        full.clear();
        target = null;
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return WorkplaceMaidData.of(maid).depositing();
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        ticks++;
        if (target == null) {
            if (!WorkplaceActions.hasProducts(maid)) {
                finish(maid, moved > 0 ? "message.maidbuilder.deposit.done" : "message.maidbuilder.deposit.nothing");
                return;
            }
            target = WorkplaceActions.nearestWarehouseContainer(level, maid, full);
            if (target == null) {
                finish(maid, full.isEmpty() ? "message.maidbuilder.deposit.no_warehouse" : "message.maidbuilder.deposit.full");
                return;
            }
            TARGETS.put(maid, target);
        }
        if (maid.distanceToSqr(Vec3.atCenterOf(target)) <= REACH_SQR) {
            int n = WorkplaceActions.depositInto(level, maid, target);
            if (n > 0) {
                moved += n;
                maid.swing(InteractionHand.MAIN_HAND);
            }
            // whatever is left did not fit: try the next container
            if (WorkplaceActions.hasProducts(maid)) full.add(target);
            target = null;
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        } else if (ticks % 20 == 1 || maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isEmpty()) {
            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, speed, 1));
            maid.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(target));
        }
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        TARGETS.remove(maid);
        target = null;
        // ran out of time
        if (WorkplaceMaidData.of(maid).depositing()) finish(maid, "message.maidbuilder.deposit.timeout");
    }

    private void finish(EntityMaid maid, String message) {
        TARGETS.remove(maid);
        WorkplaceMaidData.set(maid, WorkplaceMaidData.of(maid).withDepositing(false));
        WorkplaceActions.notifyOwner(maid, Component.translatable(message, maid.getDisplayName(), moved));
    }
}
