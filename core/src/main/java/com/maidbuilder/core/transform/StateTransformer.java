package com.maidbuilder.core.transform;

import com.maidbuilder.core.schematic.BlockStateData;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Rotates / mirrors block states by their property strings, following the conventions of
 * vanilla blocks. The mod side prefers the game's own {@code BlockState.rotate/mirror}
 * (which also covers modded blocks); this class exists so that core logic, previews and
 * tests can work without Minecraft on the classpath.
 */
public final class StateTransformer {
    private static final Direction[] HORIZONTALS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Set<String> STAIR_SHAPES = Set.of("straight", "inner_left", "inner_right", "outer_left", "outer_right");
    private static final int ROTATION_STEPS = 16;

    private StateTransformer() {
    }

    public static BlockStateData rotate(BlockStateData state, Rotation rotation) {
        if (rotation == Rotation.NONE || state.properties().isEmpty()) return state;
        Map<String, String> p = new TreeMap<>(state.properties());

        mapDirection(p, "facing", rotation::rotate);
        mapOrientation(p, rotation::rotate);
        if (rotation.quarterTurns % 2 == 1) {
            String axis = p.get("axis");
            if ("x".equals(axis)) p.put("axis", "z");
            else if ("z".equals(axis)) p.put("axis", "x");
        }
        mapSixteenth(p, r -> rotation.rotate(r, ROTATION_STEPS));
        permuteHorizontalKeys(state.properties(), p, rotation::rotate);
        mapRailShape(p, rotation::rotate);
        return state.withProperties(p);
    }

    public static BlockStateData mirror(BlockStateData state, Mirror mirror) {
        if (mirror == Mirror.NONE || state.properties().isEmpty()) return state;
        Map<String, String> p = new TreeMap<>(state.properties());
        Direction facing = Direction.byName(p.getOrDefault("facing", ""));

        if (facing != null && isStairShape(p.get("shape"))) {
            mirrorStairs(p, facing, mirror);
        } else {
            mapDirection(p, "facing", mirror::mirror);
        }
        mapOrientation(p, mirror::mirror);
        if (p.containsKey("hinge")) {
            p.put("hinge", "left".equals(p.get("hinge")) ? "right" : "left");
        }
        String type = p.get("type");
        if (("left".equals(type) || "right".equals(type)) && p.containsKey("facing")) {
            p.put("type", "left".equals(type) ? "right" : "left"); // double chests
        }
        mapSixteenth(p, r -> mirror.mirror(r, ROTATION_STEPS));
        permuteHorizontalKeys(state.properties(), p, mirror::mirror);
        mapRailShape(p, mirror::mirror);
        return state.withProperties(p);
    }

    /**
     * Stairs follow vanilla exactly: only when the stair faces along the mirrored axis is it
     * turned around; LEFT_RIGHT then swaps all left/right shapes, FRONT_BACK only the outer ones.
     * Stair shapes are recomputed by the game from neighbours anyway once placed.
     */
    private static void mirrorStairs(Map<String, String> p, Direction facing, Mirror mirror) {
        if (!mirror.flips(facing)) return;
        p.put("facing", facing.opposite().serialized());
        String shape = p.get("shape");
        boolean swap = shape.startsWith("outer_") || (mirror == Mirror.LEFT_RIGHT && shape.startsWith("inner_"));
        if (swap) {
            p.put("shape", shape.endsWith("_left") ? shape.replace("_left", "_right") : shape.replace("_right", "_left"));
        }
    }

    private static boolean isStairShape(String shape) {
        return shape != null && STAIR_SHAPES.contains(shape);
    }

    private static void mapDirection(Map<String, String> p, String key, java.util.function.UnaryOperator<Direction> fn) {
        Direction d = Direction.byName(p.getOrDefault(key, ""));
        if (d != null) p.put(key, fn.apply(d).serialized());
    }

    /** Jigsaw / crafter {@code orientation=<front>_<top>}. */
    private static void mapOrientation(Map<String, String> p, java.util.function.UnaryOperator<Direction> fn) {
        String o = p.get("orientation");
        if (o == null) return;
        int sep = o.indexOf('_');
        if (sep < 0) return;
        Direction front = Direction.byName(o.substring(0, sep));
        Direction top = Direction.byName(o.substring(sep + 1));
        if (front != null && top != null) {
            p.put("orientation", fn.apply(front).serialized() + "_" + fn.apply(top).serialized());
        }
    }

    private static void mapSixteenth(Map<String, String> p, java.util.function.IntUnaryOperator fn) {
        String r = p.get("rotation");
        if (r == null) return;
        try {
            p.put("rotation", Integer.toString(fn.applyAsInt(Integer.parseInt(r))));
        } catch (NumberFormatException ignored) {
            // not a 16-step rotation property
        }
    }

    /** Fences, panes, walls, redstone wire, vines, tripwire, mushroom blocks... */
    private static void permuteHorizontalKeys(Map<String, String> original, Map<String, String> p,
                                              java.util.function.UnaryOperator<Direction> fn) {
        for (Direction d : HORIZONTALS) {
            if (!original.containsKey(d.serialized())) return;
        }
        for (Direction d : HORIZONTALS) {
            p.put(fn.apply(d).serialized(), original.get(d.serialized()));
        }
    }

    private static void mapRailShape(Map<String, String> p, java.util.function.UnaryOperator<Direction> fn) {
        String shape = p.get("shape");
        if (shape == null || isStairShape(shape)) return;
        if (shape.startsWith("ascending_")) {
            Direction d = Direction.byName(shape.substring("ascending_".length()));
            if (d != null) p.put("shape", "ascending_" + fn.apply(d).serialized());
            return;
        }
        int sep = shape.indexOf('_');
        if (sep < 0) return;
        Direction a = Direction.byName(shape.substring(0, sep));
        Direction b = Direction.byName(shape.substring(sep + 1));
        if (a == null || b == null || !a.isHorizontal() || !b.isHorizontal()) return;
        Direction na = fn.apply(a), nb = fn.apply(b);
        p.put("shape", railShapeName(na, nb));
    }

    private static String railShapeName(Direction a, Direction b) {
        if (a.dx == 0 && b.dx == 0) return "north_south";
        if (a.dz == 0 && b.dz == 0) return "east_west";
        Direction ns = a.dz != 0 ? a : b;
        Direction ew = a.dx != 0 ? a : b;
        return ns.serialized() + "_" + ew.serialized();
    }
}
