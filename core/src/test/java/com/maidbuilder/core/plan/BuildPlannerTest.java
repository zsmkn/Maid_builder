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
}
