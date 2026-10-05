package com.maidbuilder.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * What a Blueprint Quill has marked.
 *
 * @param second      the other corner; empty while only the first one is marked
 * @param anchor      the clicked block while an automatically detected building waits to be confirmed
 *                    (the box is then {@code first..second}); cancelling keeps it as the first corner
 * @param airDistance how far from the eyes a click into the air marks a corner
 */
public record CaptureArea(ResourceKey<Level> dimension, BlockPos first, Optional<BlockPos> second,
                          Optional<BlockPos> anchor, int airDistance) {
    public static final int DEFAULT_AIR_DISTANCE = 5;
    public static final int MIN_AIR_DISTANCE = 1;
    public static final int MAX_AIR_DISTANCE = 64;
    /** Faces are picked by pointing at them from up to this far away. */
    private static final double FACE_PICK_RANGE = 128;

    public static final Codec<CaptureArea> CODEC = RecordCodecBuilder.create(i -> i.group(
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(CaptureArea::dimension),
            BlockPos.CODEC.fieldOf("first").forGetter(CaptureArea::first),
            BlockPos.CODEC.optionalFieldOf("second").forGetter(CaptureArea::second),
            BlockPos.CODEC.optionalFieldOf("anchor").forGetter(CaptureArea::anchor),
            Codec.INT.optionalFieldOf("air_distance", DEFAULT_AIR_DISTANCE).forGetter(CaptureArea::airDistance)
    ).apply(i, CaptureArea::new));

    public static final StreamCodec<ByteBuf, CaptureArea> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), CaptureArea::dimension,
            BlockPos.STREAM_CODEC, CaptureArea::first,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), CaptureArea::second,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), CaptureArea::anchor,
            ByteBufCodecs.VAR_INT, CaptureArea::airDistance,
            CaptureArea::new);

    public CaptureArea {
        airDistance = Mth.clamp(airDistance, MIN_AIR_DISTANCE, MAX_AIR_DISTANCE);
    }

    /** Only the first corner. */
    public static CaptureArea corner(ResourceKey<Level> dimension, BlockPos first, int airDistance) {
        return new CaptureArea(dimension, first.immutable(), Optional.empty(), Optional.empty(), airDistance);
    }

    /** A confirmed box. */
    public static CaptureArea box(ResourceKey<Level> dimension, BlockPos a, BlockPos b, int airDistance) {
        return new CaptureArea(dimension, BlockPos.min(a, b), Optional.of(BlockPos.max(a, b)), Optional.empty(), airDistance);
    }

    public boolean complete() {
        return second.isPresent();
    }

    /** An automatically detected building waiting to be confirmed or cancelled. */
    public boolean autoPending() {
        return anchor.isPresent();
    }

    public CaptureArea withSecond(BlockPos pos) {
        return box(dimension, first, pos, airDistance);
    }

    public CaptureArea withAirDistance(int distance) {
        return new CaptureArea(dimension, first, second, anchor, distance);
    }

    public BlockPos min() {
        return second.map(s -> BlockPos.min(first, s)).orElse(first);
    }

    public BlockPos max() {
        return second.map(s -> BlockPos.max(first, s)).orElse(first);
    }

    public BlockPos size() {
        BlockPos min = min(), max = max();
        return new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
    }

    public long volume() {
        BlockPos s = size();
        return (long) s.getX() * s.getY() * s.getZ();
    }

    public AABB bounds() {
        return new AABB(Vec3.atLowerCornerOf(min()), Vec3.atLowerCornerOf(max()).add(1, 1, 1));
    }

    /** The block a click into the air marks: {@code distance} blocks ahead of the eyes. */
    public static BlockPos airPoint(Entity entity, int distance) {
        return BlockPos.containing(entity.getEyePosition().add(entity.getLookAngle().scale(distance)));
    }

    /**
     * The face of the box a resize acts on: the one the entity points at from outside, otherwise
     * (from inside, or pointing past it) the one in the direction it looks.
     */
    public Direction faceFor(Entity entity) {
        Vec3 eye = entity.getEyePosition(), look = entity.getLookAngle();
        AABB box = bounds();
        if (!box.contains(eye)) {
            BlockHitResult hit = AABB.clip(List.of(box), eye, eye.add(look.scale(FACE_PICK_RANGE)), BlockPos.ZERO);
            if (hit != null) return hit.getDirection();
        }
        return Direction.getNearest(look.x, look.y, look.z);
    }

    /** The box with {@code face} moved {@code amount} blocks outwards (negative: inwards, never below one block). */
    public CaptureArea resized(Direction face, int amount) {
        BlockPos min = min(), max = max();
        Direction.Axis axis = face.getAxis();
        if (face.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            int value = Math.max(min.get(axis), max.get(axis) + amount);
            max = with(max, axis, value);
        } else {
            int value = Math.min(max.get(axis), min.get(axis) - amount);
            min = with(min, axis, value);
        }
        return box(dimension, min, max, airDistance);
    }

    private static BlockPos with(BlockPos pos, Direction.Axis axis, int value) {
        return switch (axis) {
            case X -> new BlockPos(value, pos.getY(), pos.getZ());
            case Y -> new BlockPos(pos.getX(), value, pos.getZ());
            case Z -> new BlockPos(pos.getX(), pos.getY(), value);
        };
    }
}
