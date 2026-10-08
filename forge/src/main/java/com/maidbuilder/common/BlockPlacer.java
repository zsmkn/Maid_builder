package com.maidbuilder.common;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;

import java.util.ArrayList;
import java.util.List;

/** Places target states in the world, including the second half of two-block structures. */
public final class BlockPlacer {
    /** Exact copy like Litematica's paste: no neighbour updates, so shapes stay as in the schematic. */
    private static final int EXACT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public enum Result {
        PLACED,
        /** The block cannot survive here yet (missing support); retry later. */
        CANNOT_SURVIVE,
        /** Target or companion position is occupied by something that cannot be replaced. */
        OBSTRUCTED,
        /** A protection mod cancelled the placement event. */
        DENIED
    }

    private BlockPlacer() {
    }

    public record Part(BlockPos pos, BlockState state) {
    }

    /** The block itself plus the half that is placed together with it (door top, bed head...). */
    public static List<Part> partsOf(BlockPos pos, BlockState state) {
        List<Part> parts = new ArrayList<>(2);
        parts.add(new Part(pos, state));
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            parts.add(new Part(pos.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        } else if (state.hasProperty(BlockStateProperties.BED_PART) && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            parts.add(new Part(pos.relative(facing), state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD)));
        }
        return parts;
    }

    /** Admin paste: sets the state(s) unconditionally. */
    public static void placeExact(ServerLevel level, BlockPos pos, BlockState state) {
        for (Part part : partsOf(pos, state)) {
            level.setBlock(part.pos(), part.state(), EXACT_FLAGS);
        }
    }

    /**
     * Survival placement on behalf of an entity (a maid). The caller consumes the item only when
     * this returns {@link Result#PLACED}.
     */
    public static Result placeAsEntity(ServerLevel level, BlockPos pos, BlockState state, Entity placer) {
        List<Part> parts = partsOf(pos, state);
        for (Part part : parts) {
            if (!level.getBlockState(part.pos()).canBeReplaced()) return Result.OBSTRUCTED;
        }
        if (!state.canSurvive(level, pos)) return Result.CANNOT_SURVIVE;

        List<BlockSnapshot> snapshots = new ArrayList<>(parts.size());
        for (Part part : parts) {
            snapshots.add(BlockSnapshot.create(level.dimension(), level, part.pos()));
        }
        for (Part part : parts) {
            level.setBlock(part.pos(), part.state(), Block.UPDATE_ALL);
        }
        boolean cancelled = snapshots.size() > 1
                ? ForgeEventFactory.onMultiBlockPlace(placer, snapshots, Direction.UP)
                : ForgeEventFactory.onBlockPlace(placer, snapshots.get(0), Direction.UP);
        if (cancelled) {
            for (int i = snapshots.size() - 1; i >= 0; i--) {
                snapshots.get(i).restore(true, false);
            }
            return Result.DENIED;
        }
        level.gameEvent(placer, GameEvent.BLOCK_PLACE, pos);
        SoundType sound = state.getSoundType(level, pos, placer);
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        return Result.PLACED;
    }

    /** Whether the world already holds the target at {@code pos} (property-exact, ignoring shape-only updates). */
    public static boolean matches(BlockState inWorld, BlockState target) {
        return inWorld == target || inWorld.getBlock() == target.getBlock() && sameOrientation(inWorld, target);
    }

    /**
     * States of the same block count as done when they only differ in properties the game
     * recomputes from neighbours (fence connections, stair shapes, redstone power...).
     */
    private static boolean sameOrientation(BlockState a, BlockState b) {
        for (Property<?> property : ORIENTATION_PROPERTIES) {
            if (!sameValue(a, b, property)) return false;
        }
        return true;
    }

    private static final Property<?>[] ORIENTATION_PROPERTIES = {
            BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.HALF,
            BlockStateProperties.SLAB_TYPE, BlockStateProperties.ROTATION_16, BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.ATTACH_FACE, BlockStateProperties.BED_PART, BlockStateProperties.DOUBLE_BLOCK_HALF};

    private static <T extends Comparable<T>> boolean sameValue(BlockState a, BlockState b, Property<T> property) {
        return !a.hasProperty(property) || !b.hasProperty(property) || a.getValue(property).equals(b.getValue(property));
    }
}
