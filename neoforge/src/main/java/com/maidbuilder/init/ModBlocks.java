package com.maidbuilder.init;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.block.TerritoryFlagBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MaidBuilder.MOD_ID);

    public static final DeferredBlock<TerritoryFlagBlock> TERRITORY_FLAG = BLOCKS.register("territory_flag",
            () -> new TerritoryFlagBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)));

    private ModBlocks() {
    }
}
