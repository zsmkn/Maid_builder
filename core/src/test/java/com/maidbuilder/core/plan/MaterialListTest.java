package com.maidbuilder.core.plan;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.BlockStateData;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.transform.Placement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialListTest {
    @Test
    void countsItemsWithSpecialCases() {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("r", IntPos.ZERO, new IntPos(8, 1, 1));
        rb.set(0, 0, 0, "minecraft:oak_slab[type=double,waterlogged=false]");
        rb.set(1, 0, 0, "minecraft:oak_slab[type=bottom,waterlogged=false]");
        rb.set(2, 0, 0, "minecraft:wall_torch[facing=north]");
        rb.set(3, 0, 0, "minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]");
        rb.set(4, 0, 0, "minecraft:potted_poppy");
        rb.set(5, 0, 0, "minecraft:sea_pickle[pickles=3,waterlogged=false]");
        rb.set(6, 0, 0, "minecraft:red_candle[candles=4,lit=false,waterlogged=false]");
        rb.set(7, 0, 0, "minecraft:oak_wall_sign[facing=north,waterlogged=false]");
        BuildPlan plan = BuildPlanner.plan(LitematicWriter.schematic("m", 3955, List.of(rb.build())), Placement.at(IntPos.ZERO));

        MaterialList list = MaterialList.of(plan);
        assertEquals(3, list.get("minecraft:oak_slab"));
        assertEquals(1, list.get("minecraft:torch"));
        assertEquals(1, list.get("minecraft:redstone"));
        assertEquals(1, list.get("minecraft:flower_pot"));
        assertEquals(1, list.get("minecraft:poppy"));
        assertEquals(3, list.get("minecraft:sea_pickle"));
        assertEquals(4, list.get("minecraft:red_candle"));
        assertEquals(1, list.get("minecraft:oak_sign"));
        assertEquals(15, list.totalItems());
        assertEquals("minecraft:red_candle", list.sortedByCount().getFirst().getKey());
    }

    @Test
    void itemForHandlesWallVariants() {
        assertEquals("minecraft:soul_torch", MaterialRules.itemFor(BlockStateData.of("minecraft:soul_wall_torch")));
        assertEquals("minecraft:redstone_torch", MaterialRules.itemFor(BlockStateData.of("minecraft:redstone_wall_torch")));
        assertEquals("minecraft:skeleton_skull", MaterialRules.itemFor(BlockStateData.of("minecraft:skeleton_wall_skull")));
        assertEquals("minecraft:stone", MaterialRules.itemFor(BlockStateData.of("minecraft:stone")));
    }
}
