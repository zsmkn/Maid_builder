package com.maidbuilder.core.transform;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.BlockStateData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransformTest {
    private static BlockStateData s(String text) {
        return BlockStateData.parse(text);
    }

    private static final List<BlockStateData> SAMPLES = List.of(
            s("minecraft:oak_stairs[facing=east,half=top,shape=inner_left,waterlogged=false]"),
            s("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
            s("minecraft:oak_log[axis=x]"),
            s("minecraft:oak_sign[rotation=3,waterlogged=false]"),
            s("minecraft:oak_fence[east=true,north=true,south=false,waterlogged=false,west=false]"),
            s("minecraft:cobblestone_wall[east=low,north=tall,south=none,up=true,waterlogged=false,west=none]"),
            s("minecraft:rail[shape=south_east,waterlogged=false]"),
            s("minecraft:powered_rail[powered=false,shape=ascending_north,waterlogged=false]"),
            s("minecraft:chest[facing=west,type=left,waterlogged=false]"),
            s("minecraft:red_bed[facing=south,occupied=false,part=foot]"),
            s("minecraft:redstone_wire[east=side,north=up,power=0,south=none,west=none]"),
            s("minecraft:hopper[enabled=true,facing=down]"),
            s("minecraft:crafter[crafting=false,orientation=east_up,triggered=false]"),
            s("minecraft:stone"));

    @Test
    void positionRotationMatchesVanillaConvention() {
        IntPos rel = new IntPos(0, 5, -1); // one block north of the origin
        Placement p = Placement.at(new IntPos(100, 64, 200));
        assertEquals(new IntPos(100, 69, 199), p.toWorld(rel));
        assertEquals(new IntPos(101, 69, 200), p.withRotation(Rotation.CLOCKWISE_90).toWorld(rel)); // north -> east
        assertEquals(new IntPos(100, 69, 201), p.withRotation(Rotation.CLOCKWISE_180).toWorld(rel));
        assertEquals(new IntPos(99, 69, 200), p.withRotation(Rotation.COUNTERCLOCKWISE_90).toWorld(rel));
        assertEquals(new IntPos(100, 69, 201), p.withMirror(Mirror.LEFT_RIGHT).toWorld(rel));
        assertEquals(new IntPos(100, 69, 199), p.withMirror(Mirror.FRONT_BACK).toWorld(rel));
    }

    @Test
    void toSchematicInvertsToWorld() {
        IntPos rel = new IntPos(3, -2, 7);
        for (Rotation r : Rotation.values()) {
            for (Mirror m : Mirror.values()) {
                Placement p = new Placement(new IntPos(-10, 70, 5), r, m);
                assertEquals(rel, p.toSchematic(p.toWorld(rel)), r + " " + m);
            }
        }
    }

    @Test
    void facingAndStateRotationAgreeWithPositionRotation() {
        // A torch attached to the block north of it must still be attached after any transform.
        for (Rotation r : Rotation.values()) {
            for (Mirror m : Mirror.values()) {
                Placement p = new Placement(IntPos.ZERO, r, m);
                BlockStateData torch = p.transform(s("minecraft:wall_torch[facing=south]"));
                // wall torch facing south hangs on the block to its north: (0,0,-1) relative to torch at origin
                IntPos support = p.toWorld(new IntPos(0, 0, -1));
                Direction facing = Direction.byName(torch.get("facing"));
                assertEquals(support, new IntPos(-facing.dx, 0, -facing.dz), r + " " + m);
            }
        }
    }

    @Test
    void fourQuarterTurnsAreIdentity() {
        for (BlockStateData state : SAMPLES) {
            BlockStateData t = state;
            for (int i = 0; i < 4; i++) t = StateTransformer.rotate(t, Rotation.CLOCKWISE_90);
            assertEquals(state, t, state.toString());
        }
    }

    @Test
    void mirroringTwiceIsIdentity() {
        for (Mirror m : Mirror.values()) {
            for (BlockStateData state : SAMPLES) {
                assertEquals(state, StateTransformer.mirror(StateTransformer.mirror(state, m), m), m + " " + state);
            }
        }
    }

    @Test
    void rotatesProperties() {
        Rotation cw = Rotation.CLOCKWISE_90;
        assertEquals("south", StateTransformer.rotate(s("minecraft:oak_stairs[facing=east,half=top,shape=inner_left]"), cw).get("facing"));
        assertEquals("inner_left", StateTransformer.rotate(s("minecraft:oak_stairs[facing=east,half=top,shape=inner_left]"), cw).get("shape"));
        assertEquals("z", StateTransformer.rotate(s("minecraft:oak_log[axis=x]"), cw).get("axis"));
        assertEquals("y", StateTransformer.rotate(s("minecraft:oak_log[axis=y]"), cw).get("axis"));
        assertEquals("7", StateTransformer.rotate(s("minecraft:oak_sign[rotation=3]"), cw).get("rotation"));
        assertEquals("east_west", StateTransformer.rotate(s("minecraft:rail[shape=north_south]"), cw).get("shape"));
        assertEquals("south_west", StateTransformer.rotate(s("minecraft:rail[shape=south_east]"), cw).get("shape"));
        assertEquals("ascending_east", StateTransformer.rotate(s("minecraft:rail[shape=ascending_north]"), cw).get("shape"));
        assertEquals("down", StateTransformer.rotate(s("minecraft:hopper[facing=down]"), cw).get("facing"));
        assertEquals("south_up", StateTransformer.rotate(s("minecraft:crafter[orientation=east_up]"), cw).get("orientation"));

        BlockStateData fence = StateTransformer.rotate(s("minecraft:oak_fence[east=true,north=true,south=false,west=false]"), cw);
        assertEquals("true", fence.get("east"));   // from north
        assertEquals("true", fence.get("south"));  // from east
        assertEquals("false", fence.get("west"));
        assertEquals("false", fence.get("north"));
    }

    @Test
    void mirrorsProperties() {
        Mirror lr = Mirror.LEFT_RIGHT, fb = Mirror.FRONT_BACK;
        // Doors always swap hinge; facing flips only on the mirrored axis.
        BlockStateData door = s("minecraft:oak_door[facing=north,half=lower,hinge=left]");
        assertEquals("south", StateTransformer.mirror(door, lr).get("facing"));
        assertEquals("right", StateTransformer.mirror(door, lr).get("hinge"));
        assertEquals("north", StateTransformer.mirror(door, fb).get("facing"));
        assertEquals("right", StateTransformer.mirror(door, fb).get("hinge"));
        // Chests swap left/right.
        assertEquals("right", StateTransformer.mirror(s("minecraft:chest[facing=west,type=left]"), fb).get("type"));
        assertEquals("east", StateTransformer.mirror(s("minecraft:chest[facing=west,type=left]"), fb).get("facing"));
        // Slabs keep their type.
        assertEquals("top", StateTransformer.mirror(s("minecraft:oak_slab[type=top]"), fb).get("type"));
        // Stairs, vanilla rules.
        BlockStateData stairs = s("minecraft:oak_stairs[facing=north,half=bottom,shape=outer_left]");
        assertEquals("south", StateTransformer.mirror(stairs, lr).get("facing"));
        assertEquals("outer_right", StateTransformer.mirror(stairs, lr).get("shape"));
        assertEquals(stairs, StateTransformer.mirror(stairs, fb));
        // 16-step rotation: 0 = south, LEFT_RIGHT flips to north (8); FRONT_BACK keeps it.
        assertEquals("8", StateTransformer.mirror(s("minecraft:oak_sign[rotation=0]"), lr).get("rotation"));
        assertEquals("0", StateTransformer.mirror(s("minecraft:oak_sign[rotation=0]"), fb).get("rotation"));
        assertEquals("12", StateTransformer.mirror(s("minecraft:oak_sign[rotation=4]"), fb).get("rotation"));
        // Rails.
        assertEquals("north_east", StateTransformer.mirror(s("minecraft:rail[shape=south_east]"), lr).get("shape"));
        assertEquals("south_west", StateTransformer.mirror(s("minecraft:rail[shape=south_east]"), fb).get("shape"));
        // Connection properties.
        BlockStateData wire = StateTransformer.mirror(s("minecraft:redstone_wire[east=side,north=up,power=0,south=none,west=none]"), lr);
        assertEquals("up", wire.get("south"));
        assertEquals("none", wire.get("north"));
        assertEquals("side", wire.get("east"));
    }
}
