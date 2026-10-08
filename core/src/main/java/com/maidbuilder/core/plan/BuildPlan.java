package com.maidbuilder.core.plan;

import java.util.List;

/**
 * Ordered build steps plus counts of blocks the planner dropped.
 *
 * @param steps       blocks to place, in order
 * @param skippedFluids      fluid blocks skipped because fluids are disabled
 * @param skippedUnbuildable blocks that cannot be obtained or placed (fire, portals, pistons heads...)
 */
public record BuildPlan(List<BuildStep> steps, int skippedFluids, int skippedUnbuildable) {
    public BuildPlan {
        steps = List.copyOf(steps);
    }

    public int size() {
        return steps.size();
    }
}
