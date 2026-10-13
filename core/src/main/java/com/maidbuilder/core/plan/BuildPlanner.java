package com.maidbuilder.core.plan;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.BlockStateData;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.transform.Placement;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a placed schematic into an ordered list of {@link BuildStep}s:
 * pass by pass ({@link BuildPhase}), bottom to top within a pass (top to bottom for
 * {@link BuildPhase#CLEAR}), and nearest to the placement origin first within a layer.
 * The result is computed once per job, never per tick.
 */
public final class BuildPlanner {
    /**
     * @param placeFluids  whether fluid sources and waterlogged states are built
     * @param clearRadius  0 to leave the schematic's air alone; otherwise air cells within this horizontal
     *                     distance (a circle) of a schematic block on the same layer become {@link BuildPhase#CLEAR} steps
     */
    public record Options(boolean placeFluids, int clearRadius) {
        public static final Options DEFAULT = new Options(false);

        public Options(boolean placeFluids) {
            this(placeFluids, 0);
        }
    }

    private BuildPlanner() {
    }

    public static BuildPlan plan(Schematic schematic, Placement placement, Options options) {
        // Later regions overwrite earlier ones at the same world position.
        Map<IntPos, BuildStep> byPos = new LinkedHashMap<>();
        int[] skippedFluids = {0};
        int[] skippedUnbuildable = {0};
        Footprint footprint = options.clearRadius() > 0 ? Footprint.of(schematic, placement) : null;

        schematic.forEachBlock((schematicPos, state, localPos) -> {
            IntPos worldPos = placement.toWorld(schematicPos);
            if (footprint != null) footprint.addBlock(worldPos, !BlockCategories.isUnbuildable(state));
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

        List<BuildStep> steps = new ArrayList<>(byPos.values());
        if (footprint != null) {
            BitSet inRange = footprint.dilate(options.clearRadius());
            BitSet seen = new BitSet();
            schematic.forEachAir(schematicPos -> {
                IntPos worldPos = placement.toWorld(schematicPos);
                int index = footprint.index(worldPos);
                if (!inRange.get(index) || footprint.isBlock(index) || seen.get(index)) return;
                seen.set(index);
                steps.add(new BuildStep(worldPos, schematicPos, BlockStateData.AIR, BlockStateData.AIR, BuildPhase.CLEAR));
            });
        }

        IntPos origin = placement.origin();
        steps.sort(Comparator.comparing(BuildStep::phase)
                .thenComparingInt(s -> s.phase() == BuildPhase.CLEAR ? -s.worldPos().y() : s.worldPos().y())
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

    /** Which cells of the placed schematic's bounding box hold blocks, as bit sets indexed y/z/x. */
    private static final class Footprint {
        /** Bit sets are int-indexed; larger boxes are not cleared at all. */
        private static final long MAX_VOLUME = 1L << 30;

        private final IntPos min;
        private final int sizeX, sizeY, sizeZ;
        /** Any non-air schematic block: never cleared. */
        private final BitSet blocks = new BitSet();
        /** Blocks of the building itself, which the clearing radius is measured from. */
        private final BitSet anchors = new BitSet();

        private Footprint(IntPos min, IntPos max) {
            this.min = min;
            sizeX = max.x() - min.x() + 1;
            sizeY = max.y() - min.y() + 1;
            sizeZ = max.z() - min.z() + 1;
        }

        static Footprint of(Schematic schematic, Placement placement) {
            // Mirroring and rotating about the y axis keep the box axis-aligned, so two corners suffice.
            IntPos a = placement.toWorld(schematic.minCorner()), b = placement.toWorld(schematic.maxCorner());
            IntPos min = IntPos.min(a, b), max = IntPos.max(a, b);
            long volume = (long) (max.x() - min.x() + 1) * (max.y() - min.y() + 1) * (max.z() - min.z() + 1);
            return volume > MAX_VOLUME ? null : new Footprint(min, max);
        }

        int index(IntPos world) {
            return ((world.y() - min.y()) * sizeZ + (world.z() - min.z())) * sizeX + (world.x() - min.x());
        }

        void addBlock(IntPos world, boolean anchor) {
            int i = index(world);
            blocks.set(i);
            if (anchor) anchors.set(i);
        }

        boolean isBlock(int index) {
            return blocks.get(index);
        }

        /**
         * Cells within {@code radius} (horizontal, Euclidean) of an anchor block on the same layer.
         * Per row the distance to the nearest anchor along x is found in two sweeps; a cell is in
         * range if some row within {@code radius} along z has an anchor at {@code dx² + dz² <= r²}.
         */
        BitSet dilate(int radius) {
            BitSet result = new BitSet();
            int r2 = radius * radius;
            int far = radius + 1;
            int[] rowDist = new int[sizeX * sizeZ];
            for (int y = 0; y < sizeY; y++) {
                int layer = y * sizeZ * sizeX;
                if (anchors.nextSetBit(layer) < 0 || anchors.nextSetBit(layer) >= layer + sizeZ * sizeX) continue;
                for (int z = 0; z < sizeZ; z++) {
                    int row = z * sizeX;
                    int d = far;
                    for (int x = 0; x < sizeX; x++) {
                        d = anchors.get(layer + row + x) ? 0 : Math.min(d + 1, far);
                        rowDist[row + x] = d;
                    }
                    d = far;
                    for (int x = sizeX - 1; x >= 0; x--) {
                        d = anchors.get(layer + row + x) ? 0 : Math.min(d + 1, far);
                        rowDist[row + x] = Math.min(rowDist[row + x], d);
                    }
                }
                for (int z = 0; z < sizeZ; z++) {
                    for (int x = 0; x < sizeX; x++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            int zz = z + dz;
                            if (zz < 0 || zz >= sizeZ) continue;
                            int dx = rowDist[zz * sizeX + x];
                            if (dx * dx + dz * dz <= r2) {
                                result.set(layer + z * sizeX + x);
                                break;
                            }
                        }
                    }
                }
            }
            return result;
        }
    }
}
