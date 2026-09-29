package com.maidbuilder.core.plan;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.BlockStateData;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.transform.Placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a placed schematic into an ordered list of {@link BuildStep}s:
 * pass by pass ({@link BuildPhase}), bottom to top within a pass, and nearest to the
 * placement origin first within a layer. The result is computed once per job, never per tick.
 */
public final class BuildPlanner {
    public record Options(boolean placeFluids) {
        public static final Options DEFAULT = new Options(false);
    }

    private BuildPlanner() {
    }

    public static BuildPlan plan(Schematic schematic, Placement placement, Options options) {
        // Later regions overwrite earlier ones at the same world position.
        Map<IntPos, BuildStep> byPos = new LinkedHashMap<>();
        int[] skippedFluids = {0};
        int[] skippedUnbuildable = {0};

        schematic.forEachBlock((schematicPos, state, localPos) -> {
            IntPos worldPos = placement.toWorld(schematicPos);
            if (BlockCategories.isSecondaryPart(state)) {
                byPos.remove(worldPos);
                return;
            }
            if (BlockCategories.isUnbuildable(state)) {
                byPos.remove(worldPos);
                skippedUnbuildable[0]++;
                return;
            }
            BlockStateData source = state;
            if (!options.placeFluids()) {
                if (BlockCategories.isFluid(state)) {
                    byPos.remove(worldPos);
                    skippedFluids[0]++;
                    return;
                }
                if ("true".equals(state.get("waterlogged"))) {
                    source = state.with("waterlogged", "false");
                }
            }
            BlockStateData worldState = placement.transform(source);
            byPos.put(worldPos, new BuildStep(worldPos, schematicPos, source, worldState, BlockCategories.phaseOf(source)));
        });

        IntPos origin = placement.origin();
        List<BuildStep> steps = new ArrayList<>(byPos.values());
        steps.sort(Comparator.comparing(BuildStep::phase)
                .thenComparingInt(s -> s.worldPos().y())
                .thenComparingLong(s -> horizontalDistSqr(s.worldPos(), origin))
                .thenComparing(BuildStep::worldPos));
        return new BuildPlan(steps, skippedFluids[0], skippedUnbuildable[0]);
    }

    public static BuildPlan plan(Schematic schematic, Placement placement) {
        return plan(schematic, placement, Options.DEFAULT);
    }

    private static long horizontalDistSqr(IntPos a, IntPos b) {
        long dx = a.x() - b.x(), dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }
}
