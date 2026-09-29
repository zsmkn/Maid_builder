package com.maidbuilder.core.transform;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.BlockStateData;

/**
 * Where and how a schematic is placed in the world. Schematic-relative positions are first
 * mirrored, then rotated around the schematic origin, then translated to {@link #origin()} -
 * the same order vanilla structure templates use.
 */
public record Placement(IntPos origin, Rotation rotation, Mirror mirror) {
    public static Placement at(IntPos origin) {
        return new Placement(origin, Rotation.NONE, Mirror.NONE);
    }

    public Placement withRotation(Rotation r) {
        return new Placement(origin, r, mirror);
    }

    public Placement withMirror(Mirror m) {
        return new Placement(origin, rotation, m);
    }

    public Placement withOrigin(IntPos o) {
        return new Placement(o, rotation, mirror);
    }

    public IntPos toWorld(IntPos rel) {
        int x = rel.x(), z = rel.z();
        switch (mirror) {
            case LEFT_RIGHT -> z = -z;
            case FRONT_BACK -> x = -x;
            default -> {
            }
        }
        int rx, rz;
        switch (rotation) {
            case CLOCKWISE_90 -> { rx = -z; rz = x; }
            case CLOCKWISE_180 -> { rx = -x; rz = -z; }
            case COUNTERCLOCKWISE_90 -> { rx = z; rz = -x; }
            default -> { rx = x; rz = z; }
        }
        return new IntPos(origin.x() + rx, origin.y() + rel.y(), origin.z() + rz);
    }

    /** Inverse of {@link #toWorld(IntPos)}. */
    public IntPos toSchematic(IntPos world) {
        int x = world.x() - origin.x(), z = world.z() - origin.z();
        int ux, uz;
        switch (rotation) {
            case CLOCKWISE_90 -> { ux = z; uz = -x; }
            case CLOCKWISE_180 -> { ux = -x; uz = -z; }
            case COUNTERCLOCKWISE_90 -> { ux = -z; uz = x; }
            default -> { ux = x; uz = z; }
        }
        switch (mirror) {
            case LEFT_RIGHT -> uz = -uz;
            case FRONT_BACK -> ux = -ux;
            default -> {
            }
        }
        return new IntPos(ux, world.y() - origin.y(), uz);
    }

    public BlockStateData transform(BlockStateData state) {
        return StateTransformer.rotate(StateTransformer.mirror(state, mirror), rotation);
    }
}
