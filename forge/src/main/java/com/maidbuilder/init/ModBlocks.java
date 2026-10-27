package com.maidbuilder.init;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.block.TerritoryFlagBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MaidBuilder.MOD_ID);

    public static final RegistryObject<TerritoryFlagBlock> TERRITORY_FLAG = BLOCKS.register("territory_flag",
            () -> new TerritoryFlagBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0f)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)));

    private ModBlocks() {
    }
}
