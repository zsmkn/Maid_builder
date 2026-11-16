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

    /**
     * How long a search that found no way is taken for granted. A failed search is the costly kind
     * (it looks at every spot she can walk to), and a maid who is stuck asks again every few seconds.
     */
    private static final long NO_WAY_TICKS = 20 * 30;

    @Nullable
    private List<BlockPos> route;
    private int index;
    private boolean planned;
    /** The goal the last failed search was for, the spots it showed to have no way there, and until when that holds. */
    @Nullable
    private BlockPos noWayGoal;
    private final java.util.Set<BlockPos> noWayFrom = new java.util.HashSet<>();
    private long noWayUntil;

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
        long now = level.getGameTime();
        if (goal.equals(noWayGoal) && now < noWayUntil && noWayFrom.contains(maid.blockPosition())) {
            route = null;
            return false;
        }
        noWayFrom.clear();
        noWayGoal = null;
        route = ReachPlanner.planWalk(level, maid, goal, within, noWayFrom);
        if (route == null) {
            noWayGoal = goal.immutable();
            noWayUntil = now + NO_WAY_TICKS;
            return false;
        }
        if (route.isEmpty()) {
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
