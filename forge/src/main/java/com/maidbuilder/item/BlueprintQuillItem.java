package com.maidbuilder.item;

import com.maidbuilder.common.capture.QuillActions;
import com.maidbuilder.init.ModItemData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Blueprint Quill: captures part of the world into a {@code .litematic} in the player's
 * {@code schematics/} folder (kept apart from the Blueprint Wand, which builds). The rules are in
 * {@link QuillActions}:
 * <ul>
 *   <li>right-click a building block: select the whole building (confirm by resizing or by
 *       right-clicking the air; sneak + right-click picks corners by hand instead)</li>
 *   <li>right-click terrain, or the air: mark a corner (the air: a few blocks ahead)</li>
 *   <li>resize keys: move the face of the box pointed at; with one corner, the air distance</li>
 *   <li>right-click the air with an area marked: name and save the blueprint (client screen)</li>
 *   <li>sneak + right-click: clear the selection</li>
 * </ul>
 */
public class BlueprintQuillItem extends Item {
    public BlueprintQuillItem(Properties properties) {
        super(properties);
    }

    /** Runs before the clicked block's own interaction, so doors and chests are marked, not opened. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) {
            if (player.isSecondaryUseActive()) QuillActions.sneakClick(serverPlayer, stack);
            else QuillActions.clickBlock(serverPlayer, stack, context.getClickedPos());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            if (player.isSecondaryUseActive()) QuillActions.sneakClick(serverPlayer, stack);
            else QuillActions.clickAir(serverPlayer, stack);
        } else if (!player.isSecondaryUseActive()) {
            // A marked area (also one waiting for confirmation, which this click confirms) is saved.
            CaptureArea area = ModItemData.CAPTURE_AREA.get(stack);
            if (area != null && area.complete() && area.dimension().equals(level.dimension())) {
                com.maidbuilder.client.ClientAccess.openCaptureScreen(area);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        CaptureArea area = ModItemData.CAPTURE_AREA.get(stack);
        if (area != null) {
            tooltip.add(Component.translatable("tooltip.maidbuilder.quill.first", area.first().toShortString()).withStyle(ChatFormatting.GRAY));
            area.second().ifPresent(s -> tooltip.add(Component.translatable("tooltip.maidbuilder.quill.second", s.toShortString())
                    .withStyle(ChatFormatting.GRAY)));
        }
        tooltip.add(Component.translatable("tooltip.maidbuilder.quill.usage").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        CaptureArea area = ModItemData.CAPTURE_AREA.get(stack);
        return area != null && area.complete();
    }
}
