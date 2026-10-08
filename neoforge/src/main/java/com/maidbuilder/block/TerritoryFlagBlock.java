package com.maidbuilder.block;

import com.maidbuilder.common.territory.TerritoryManager;
import com.maidbuilder.common.territory.TerritoryReports;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Territory flag. Placing it ({@link com.maidbuilder.item.TerritoryFlagItem}) claims the square
 * around it; using it opens the territory screen; removing it stops the territory until a flag is
 * put back.
 */
public class TerritoryFlagBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<TerritoryFlagBlock> CODEC = simpleCodec(TerritoryFlagBlock::new);
    private static final VoxelShape POLE = Block.box(6.5, 0, 6.5, 9.5, 16, 9.5);
    private static final VoxelShape OUTLINE = Block.box(3, 0, 3, 13, 16, 13);

    public TerritoryFlagBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return POLE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) TerritoryReports.openAtFlag(serverPlayer, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            TerritoryManager.onFlagRemoved(serverLevel.getServer(), serverLevel.dimension(), pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
