package com.maidbuilder.item;

import com.maidbuilder.init.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;

/**
 * Blueprint Quill: captures part of the world into a {@code .litematic} in the player's
 * {@code schematics/} folder (kept apart from the Blueprint Wand, which builds).
 * <ul>
 *   <li>right-click a block: mark the first corner, then the second (a third click starts over)</li>
 *   <li>right-click air with both corners marked: name and save the blueprint (client screen)</li>
 *   <li>sneak + right-click: clear the selection</li>
 * </ul>
 */
public class BlueprintQuillItem extends Item {
    public BlueprintQuillItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();
        if (player.isSecondaryUseActive()) {
            clear(player, stack);
        } else if (!context.getLevel().isClientSide) {
            mark(player, stack, context.getClickedPos());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            clear(player, stack);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        CaptureArea area = stack.get(ModDataComponents.CAPTURE_AREA.get());
        if (area == null || !area.complete()) {
            if (!level.isClientSide) player.displayClientMessage(Component.translatable("message.maidbuilder.quill.need_corners"), true);
        } else if (!area.dimension().equals(level.dimension())) {
            if (!level.isClientSide) player.displayClientMessage(Component.translatable("message.maidbuilder.quill.other_dimension"), true);
        } else if (level.isClientSide) {
            com.maidbuilder.client.ClientAccess.openCaptureScreen(area);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static void mark(Player player, ItemStack stack, BlockPos pos) {
        CaptureArea area = stack.get(ModDataComponents.CAPTURE_AREA.get());
        Level level = player.level();
        CaptureArea updated;
        if (area == null || area.complete() || !area.dimension().equals(level.dimension())) {
            updated = new CaptureArea(level.dimension(), pos.immutable(), Optional.empty());
            player.displayClientMessage(Component.translatable("message.maidbuilder.quill.first", pos.toShortString()), true);
        } else {
            updated = new CaptureArea(area.dimension(), area.first(), Optional.of(pos.immutable()));
            BlockPos size = updated.size();
            player.displayClientMessage(Component.translatable("message.maidbuilder.quill.second", pos.toShortString(),
                    size.getX() + "x" + size.getY() + "x" + size.getZ()), true);
        }
        stack.set(ModDataComponents.CAPTURE_AREA.get(), updated);
    }

    private static void clear(Player player, ItemStack stack) {
        if (player.level().isClientSide) return;
        stack.remove(ModDataComponents.CAPTURE_AREA.get());
        player.displayClientMessage(Component.translatable("message.maidbuilder.quill.cleared"), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        CaptureArea area = stack.get(ModDataComponents.CAPTURE_AREA.get());
        if (area != null) {
            tooltip.add(Component.translatable("tooltip.maidbuilder.quill.first", area.first().toShortString()).withStyle(ChatFormatting.GRAY));
            area.second().ifPresent(s -> tooltip.add(Component.translatable("tooltip.maidbuilder.quill.second", s.toShortString())
                    .withStyle(ChatFormatting.GRAY)));
        }
        tooltip.add(Component.translatable("tooltip.maidbuilder.quill.usage").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        CaptureArea area = stack.get(ModDataComponents.CAPTURE_AREA.get());
        return area != null && area.complete();
    }
}
