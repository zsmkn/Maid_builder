package com.maidbuilder.item;

import com.maidbuilder.common.territory.TerritoryManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

import java.util.List;

/** Places a territory flag only where the territory rules allow it, and then claims the territory. */
public class TerritoryFlagItem extends BlockItem {
    public TerritoryFlagItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return super.place(context);
        BlockPos pos = context.getClickedPos();
        TerritoryManager.PlacementCheck check = TerritoryManager.check(player.server, player.getUUID(), player.level().dimension(), pos);
        if (!check.ok()) {
            player.displayClientMessage(check.error(), false);
            return InteractionResult.FAIL;
        }
        InteractionResult result = super.place(context);
        if (result.consumesAction() && player.level().getBlockState(pos).is(getBlock())) {
            TerritoryManager.place(player.server, player, player.level().dimension(), pos, check);
        }
        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.maidbuilder.territory_flag").withStyle(ChatFormatting.GRAY));
    }
}
