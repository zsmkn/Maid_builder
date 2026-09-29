package com.maidbuilder.common;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.transform.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/** Conversions between core types and Minecraft types. */
public final class Convert {
    private Convert() {
    }

    public static BlockPos toBlockPos(IntPos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    public static IntPos toIntPos(BlockPos p) {
        return new IntPos(p.getX(), p.getY(), p.getZ());
    }

    public static Rotation toMc(com.maidbuilder.core.transform.Rotation r) {
        return switch (r) {
            case NONE -> Rotation.NONE;
            case CLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            case CLOCKWISE_180 -> Rotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    public static com.maidbuilder.core.transform.Rotation toCore(Rotation r) {
        return switch (r) {
            case NONE -> com.maidbuilder.core.transform.Rotation.NONE;
            case CLOCKWISE_90 -> com.maidbuilder.core.transform.Rotation.CLOCKWISE_90;
            case CLOCKWISE_180 -> com.maidbuilder.core.transform.Rotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> com.maidbuilder.core.transform.Rotation.COUNTERCLOCKWISE_90;
        };
    }

    public static Mirror toMc(com.maidbuilder.core.transform.Mirror m) {
        return switch (m) {
            case NONE -> Mirror.NONE;
            case LEFT_RIGHT -> Mirror.LEFT_RIGHT;
            case FRONT_BACK -> Mirror.FRONT_BACK;
        };
    }

    public static com.maidbuilder.core.transform.Mirror toCore(Mirror m) {
        return switch (m) {
            case NONE -> com.maidbuilder.core.transform.Mirror.NONE;
            case LEFT_RIGHT -> com.maidbuilder.core.transform.Mirror.LEFT_RIGHT;
            case FRONT_BACK -> com.maidbuilder.core.transform.Mirror.FRONT_BACK;
        };
    }

    public static Placement placement(BlockPos origin, Rotation rotation, Mirror mirror) {
        return new Placement(toIntPos(origin), toCore(rotation), toCore(mirror));
    }
}
