package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The fallback for a walk the game's path finder cannot do in one go (the way round a building is
 * longer than its range): a route planned by {@link ReachPlanner#planWalk}, handed to the path
 * finder {@link #STEP} steps at a time. Planned at most once per walk ({@link #reset}).
 */
final class RouteFollower {
    /** Steps handed to the game's path finder at a time (well inside its range). */
    private static final int STEP = 20;

    @Nullable
    private List<BlockPos> route;
    private int index;
    private boolean planned;

    void reset() {
        route = null;
        planned = false;
    }

    boolean planned() {
        return planned;
    }

    /** Plans the way to within {@code within} blocks of {@code goal} and starts her along it; false if there is none. */
    boolean plan(ServerLevel level, EntityMaid maid, BlockPos goal, double within, float speed) {
        planned = true;
        route = ReachPlanner.planWalk(level, maid, goal, within);
        if (route == null || route.isEmpty()) {
            route = null;
            return false;
        }
        index = -1;
        next(maid, speed);
        return true;
    }

    /** At the current waypoint with more to go: walks on to the next one. */
    boolean advance(EntityMaid maid, float speed) {
        if (route == null || index >= route.size() - 1 || maid.blockPosition().distManhattan(route.get(index)) > 2) return false;
        next(maid, speed);
        return true;
    }

    /** The walk target to (re)set: the current waypoint, or null when not following a route. */
    @Nullable
    WalkTarget current(float speed) {
        return route == null ? null : new WalkTarget(route.get(index), speed, 0);
    }

    private void next(EntityMaid maid, float speed) {
        index = Math.min(route.size() - 1, index + STEP);
        maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(route.get(index), speed, 0));
    }
}
