package com.maidbuilder.common;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Breaks wrong blocks on behalf of an entity (a maid), the counterpart of {@link BlockPlacer}. */
public final class BlockBreaker {
    /** Blocks maids never break, on top of block entities and unbreakable blocks. */
    public static final TagKey<Block> NEVER_BREAK = TagKey.create(Registries.BLOCK, MaidBuilder.id("never_break"));
    private static final String FAKE_PLAYER_NAME = "[MaidBuilder]";

    public enum Result {
        BROKEN,
        /** Not a block maids break (container, unbreakable, too hard, tagged): left for the player. */
        PROTECTED,
        /** A protection mod cancelled the break event. */
        DENIED
    }

    private BlockBreaker() {
    }

    /** Nothing that has to be broken: air, or a fluid (fluids are not drained). */
    public static boolean isCleared(BlockState state) {
        return state.isAir() || state.getBlock() instanceof LiquidBlock;
    }

    /** The upper half of a door or tall plant is broken through its lower half, so the item is not lost. */
    public static BlockPos primaryPos(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                && level.getBlockState(pos.below()).is(state.getBlock())) {
            return pos.below();
        }
        return pos;
    }

    /**
     * Whether this is a kind of block maids break, leaving out the hardness limit (a server setting the
     * client does not know); used by the wand preview to outline what clearing would break.
     */
    public static boolean breakableKind(BlockGetter level, BlockPos pos, BlockState state) {
        return !isCleared(state) && !state.hasBlockEntity() && !state.is(NEVER_BREAK) && state.getDestroySpeed(level, pos) >= 0;
    }

    /** Whether a maid may break this block at all (protection events aside). */
    public static boolean breakable(ServerLevel level, BlockPos pos, BlockState state) {
        return breakableKind(level, pos, state) && state.getDestroySpeed(level, pos) <= MaidBuilderConfig.MAX_BREAK_HARDNESS.get();
    }

    /** How long breaking takes, in ticks: a moment for plants, about a second for stone. */
    public static int breakTicks(ServerLevel level, BlockPos pos, BlockState state) {
        float hardness = Math.max(0, state.getDestroySpeed(level, pos));
        return Math.min(60, 2 + Math.round(hardness * 10));
    }

    /**
     * Breaks the block like a player would, firing both the living-entity and the player break
     * events (the latter through a fake player carrying the owner's id, which is what most protection
     * mods check). Drops are computed with {@code tool} and handed to {@code drops}.
     */
    public static Result breakAsEntity(ServerLevel level, BlockPos pos, LivingEntity breaker, UUID owner, ItemStack tool,
                                       Consumer<ItemStack> drops) {
        BlockState state = level.getBlockState(pos);
        if (!breakable(level, pos, state)) return Result.PROTECTED;
        if (!state.getBlock().canEntityDestroy(state, level, pos, breaker) || !EventHooks.onEntityDestroyBlock(breaker, pos, state)) {
            return Result.DENIED;
        }
        var player = FakePlayerFactory.get(level, new GameProfile(owner, FAKE_PLAYER_NAME));
        if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player)).isCanceled()) return Result.DENIED;

        List<ItemStack> loot = Block.getDrops(state, level, pos, null, breaker, tool);
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
        if (!level.setBlock(pos, level.getFluidState(pos).createLegacyBlock(), Block.UPDATE_ALL)) return Result.PROTECTED;
        level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(breaker, state));
        loot.forEach(drops);
        state.spawnAfterBreak(level, pos, tool, true);
        return Result.BROKEN;
    }
}
