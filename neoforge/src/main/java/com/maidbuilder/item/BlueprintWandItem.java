package com.maidbuilder.item;

import com.maidbuilder.common.MaterialContainers;
import com.maidbuilder.common.wand.WandActions;
import com.maidbuilder.init.ModDataComponents;
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

import java.util.List;
import java.util.UUID;

/**
 * Blueprint Wand.
 * <p>Not linked to a job (positioning):
 * <ul>
 *   <li>right-click air: choose a schematic (client screen)</li>
 *   <li>right-click a block: move the origin onto that face</li>
 *   <li>keys (R, M, arrows, PgUp/PgDn by default): rotate, mirror, nudge</li>
 *   <li>sneak + right-click: confirm, creating a build job (uploads the file if the server lacks it)</li>
 * </ul>
 * <p>Linked to a job:
 * <ul>
 *   <li>right-click a maid: she builds the job ({@link com.maidbuilder.common.maid.WandInteractHandler})</li>
 *   <li>right-click a container: add/remove it as a material container</li>
 *   <li>right-click elsewhere: progress summary; sneak + right-click: unlink to start a new placement</li>
 * </ul>
 */
public class BlueprintWandItem extends Item {
    public BlueprintWandItem(Properties properties) {
        super(properties);
    }

    private static boolean linked(ItemStack stack) {
        return stack.has(ModDataComponents.BUILD_JOB.get());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean sneaking = player.isSecondaryUseActive();
        if (player instanceof ServerPlayer serverPlayer) {
            if (linked(stack)) {
                if (sneaking) WandActions.unlink(serverPlayer, stack);
                else WandActions.showSummary(serverPlayer, stack);
            } else if (sneaking) {
                WandActions.confirm(serverPlayer, stack);
            }
        } else if (!linked(stack) && !sneaking) {
            com.maidbuilder.client.ClientAccess.openSchematicScreen();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** Runs before the clicked block's own interaction, so containers are not opened. */
    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !linked(stack) || player.isSecondaryUseActive()
                || !MaterialContainers.isContainer(context.getLevel(), context.getClickedPos())) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            WandActions.toggleMaterialSource(serverPlayer, stack, context.getClickedPos());
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();
        boolean sneaking = player.isSecondaryUseActive();
        if (player instanceof ServerPlayer serverPlayer) {
            if (linked(stack)) {
                if (sneaking) WandActions.unlink(serverPlayer, stack);
                else WandActions.showSummary(serverPlayer, stack);
            } else if (sneaking) {
                WandActions.confirm(serverPlayer, stack);
            } else if (stack.has(ModDataComponents.WAND_PLACEMENT.get())) {
                WandActions.setOrigin(serverPlayer, stack, context.getClickedPos().relative(context.getClickedFace()));
            }
        } else if (!linked(stack) && !sneaking && !stack.has(ModDataComponents.WAND_PLACEMENT.get())) {
            com.maidbuilder.client.ClientAccess.openSchematicScreen();
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        WandPlacement placement = stack.get(ModDataComponents.WAND_PLACEMENT.get());
        UUID jobId = stack.get(ModDataComponents.BUILD_JOB.get());
        if (placement != null) {
            tooltip.add(Component.translatable("tooltip.maidbuilder.wand.schematic", placement.file()).withStyle(ChatFormatting.GRAY));
        }
        if (jobId == null) {
            tooltip.add(Component.translatable("tooltip.maidbuilder.wand.unlinked").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("tooltip.maidbuilder.wand.usage_unlinked").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.maidbuilder.wand.linked", jobId.toString().substring(0, 8)).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("tooltip.maidbuilder.wand.usage").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return linked(stack);
    }
}
