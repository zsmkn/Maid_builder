package com.maidbuilder.core.plan;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.BlockStateData;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.transform.Placement;
import com.maidbuilder.core.transform.Rotation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildPlannerTest {
    private static Schematic hut() {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("hut", IntPos.ZERO, new IntPos(3, 3, 3));
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 3; z++)
                rb.set(x, 0, z, "minecraft:cobblestone");
        rb.set(0, 1, 0, "minecraft:sand");
        rb.set(1, 1, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
        rb.set(1, 2, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        rb.set(2, 1, 0, "minecraft:wall_torch[facing=north]");
        rb.set(2, 2, 2, "minecraft:oak_planks");
        rb.set(0, 1, 2, "minecraft:red_bed[facing=south,occupied=false,part=foot]");
        rb.set(0, 1, 1, "minecraft:red_bed[facing=south,occupied=false,part=head]");
        rb.set(1, 1, 1, "minecraft:water[level=0]");
        rb.set(1, 1, 2, "minecraft:oak_slab[type=double,waterlogged=true]");
        rb.set(2, 1, 1, "minecraft:fire[age=0]");
        return LitematicWriter.schematic("hut", 3955, List.of(rb.build()));
    }

    @Test
    void ordersByPhaseThenHeight() {
        BuildPlan plan = BuildPlanner.plan(hut(), Placement.at(new IntPos(10, 64, 10)));
        List<BuildStep> steps = plan.steps();

        // secondary halves, fluids and fire are dropped
        assertEquals(9 + 1 + 1 + 1 + 1 + 1 + 1, steps.size());
        assertEquals(1, plan.skippedFluids());
        assertEquals(1, plan.skippedUnbuildable());
        assertFalse(steps.stream().anyMatch(s -> BlockCategories.isSecondaryPart(s.schematicState())));

        for (int i = 1; i < steps.size(); i++) {
            BuildStep a = steps.get(i - 1), b = steps.get(i);
            assertTrue(a.phase().compareTo(b.phase()) < 0
                    || (a.phase() == b.phase() && a.worldPos().y() <= b.worldPos().y()), a + " before " + b);
        }
        // All 9 floor blocks come first, nearest to origin first.
        assertEquals(new IntPos(10, 64, 10), steps.getFirst().worldPos());
        assertTrue(steps.subList(0, 9).stream().allMatch(s -> s.schematicState().path().equals("cobblestone")));

        assertEquals(BuildPhase.GRAVITY_AND_FLUIDS, steps.getLast().phase());
        assertEquals("sand", steps.getLast().schematicState().path());
        BuildStep slab = steps.stream().filter(s -> s.schematicState().path().equals("oak_slab")).findFirst().orElseThrow();
        assertEquals("false", slab.worldState().get("waterlogged"));
    }

    @Test
    void placeFluidsOptionKeepsWater() {
        BuildPlan plan = BuildPlanner.plan(hut(), Placement.at(IntPos.ZERO), new BuildPlanner.Options(true));
        assertEquals(0, plan.skippedFluids());
        assertTrue(plan.steps().stream().anyMatch(s -> s.schematicState().path().equals("water")));
    }

    @Test
    void appliesPlacementTransform() {
        BuildPlan plan = BuildPlanner.plan(hut(), Placement.at(IntPos.ZERO).withRotation(Rotation.CLOCKWISE_90));
        BuildStep door = plan.steps().stream().filter(s -> s.schematicState().path().equals("oak_door")).findFirst().orElseThrow();
        assertEquals(new IntPos(0, 1, 1), door.worldPos());
        assertEquals("east", door.worldState().get("facing"));
        assertEquals("north", door.schematicState().get("facing"));
    }

    @Test
    void categorizesCommonBlocks() {
        assertEquals(BuildPhase.SOLID, BlockCategories.phaseOf(BlockStateData.of("minecraft:stone_bricks")));
        assertEquals(BuildPhase.SOLID, BlockCategories.phaseOf(BlockStateData.of("minecraft:oak_trapdoor")));
        assertEquals(BuildPhase.SOLID, BlockCategories.phaseOf(BlockStateData.of("minecraft:sea_lantern")));
        assertEquals(BuildPhase.SOLID, BlockCategories.phaseOf(BlockStateData.of("minecraft:crimson_stem")));
        assertEquals(BuildPhase.SOLID, BlockCategories.phaseOf(BlockStateData.of("minecraft:potted_poppy")));
        assertEquals(BuildPhase.ATTACHED, BlockCategories.phaseOf(BlockStateData.of("minecraft:bamboo_door")));
        assertEquals(BuildPhase.ATTACHED, BlockCategories.phaseOf(BlockStateData.of("minecraft:lantern")));
        assertEquals(BuildPhase.ATTACHED, BlockCategories.phaseOf(BlockStateData.of("minecraft:white_carpet")));
        assertEquals(BuildPhase.ATTACHED, BlockCategories.phaseOf(BlockStateData.of("minecraft:redstone_wire")));
        assertEquals(BuildPhase.ATTACHED, BlockCategories.phaseOf(BlockStateData.of("minecraft:poppy")));
        assertEquals(BuildPhase.GRAVITY_AND_FLUIDS, BlockCategories.phaseOf(BlockStateData.of("minecraft:lime_concrete_powder")));
    }

    /** 21x3x21 of air with one block in the middle of the second layer. */
    private static Schematic lonePost() {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("post", IntPos.ZERO, new IntPos(21, 3, 21));
        rb.set(10, 1, 10, "minecraft:stone");
        return LitematicWriter.schematic("post", 3955, List.of(rb.build()));
    }

    @Test
    void noClearStepsUnlessEnabled() {
        assertFalse(BuildPlanner.plan(lonePost(), Placement.at(IntPos.ZERO), new BuildPlanner.Options(true)).steps().stream()
                .anyMatch(s -> s.phase() == BuildPhase.CLEAR));
        assertEquals(BuildPlanner.plan(hut(), Placement.at(IntPos.ZERO)).steps(),
                BuildPlanner.plan(hut(), Placement.at(IntPos.ZERO), new BuildPlanner.Options(false, 0)).steps());
    }

    @Test
    void clearsAirWithinCircleOnSameLayerOnly() {
        BuildPlan plan = BuildPlanner.plan(lonePost(), Placement.at(new IntPos(100, 64, 100)), new BuildPlanner.Options(false, 5));
        List<BuildStep> clear = plan.steps().stream().filter(s -> s.phase() == BuildPhase.CLEAR).toList();
        // lattice points with dx^2 + dz^2 <= 25, minus the post itself
        assertEquals(80, clear.size());
        for (BuildStep s : clear) {
            assertEquals(65, s.worldPos().y(), "only the layer that has a block: " + s);
            int dx = s.worldPos().x() - 110, dz = s.worldPos().z() - 110;
            assertTrue(dx * dx + dz * dz <= 25, s.toString());
            assertTrue(s.schematicState().isAir() && s.worldState().isAir());
        }
        assertTrue(clear.stream().anyMatch(s -> s.worldPos().equals(new IntPos(115, 65, 110))));
        assertTrue(clear.stream().anyMatch(s -> s.worldPos().equals(new IntPos(113, 65, 114))));
        assertFalse(clear.stream().anyMatch(s -> s.worldPos().equals(new IntPos(114, 65, 114))), "corner of the square is outside the circle");
        assertEquals(1, plan.steps().stream().filter(s -> s.phase() != BuildPhase.CLEAR).count());
    }

    @Test
    void clearStepsComeFirstTopDownAndSkipBlocks() {
        BuildPlan plan = BuildPlanner.plan(hut(), Placement.at(IntPos.ZERO).withRotation(Rotation.CLOCKWISE_90),
                new BuildPlanner.Options(false, 5));
        List<BuildStep> steps = plan.steps();
        int firstBuild = 0;
        while (steps.get(firstBuild).phase() == BuildPhase.CLEAR) firstBuild++;
        assertTrue(firstBuild > 0);
        for (int i = firstBuild; i < steps.size(); i++) assertTrue(steps.get(i).phase() != BuildPhase.CLEAR);
        for (int i = 1; i < firstBuild; i++) {
            assertTrue(steps.get(i - 1).worldPos().y() >= steps.get(i).worldPos().y(), "top down");
        }
        // every cell of the 3x3x3 box that holds no block (incl. door top, bed head, skipped water and fire)
        java.util.Set<IntPos> cleared = new java.util.HashSet<>();
        steps.subList(0, firstBuild).forEach(s -> cleared.add(s.worldPos()));
        assertEquals(firstBuild, cleared.size(), "no duplicates");
        // the box holds 27 cells, 19 of them blocks (counting door top, bed head, water and fire)
        assertEquals(27 - 19, firstBuild);
        assertEquals(BuildPlanner.plan(hut(), Placement.at(IntPos.ZERO).withRotation(Rotation.CLOCKWISE_90)).size(),
                steps.size() - firstBuild, "building steps unchanged");
    }
}
