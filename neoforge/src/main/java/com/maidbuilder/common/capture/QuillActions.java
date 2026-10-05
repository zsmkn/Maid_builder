package com.maidbuilder.common.capture;

import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.init.ModDataComponents;
import com.maidbuilder.init.ModItems;
import com.maidbuilder.item.CaptureArea;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Server side of the Blueprint Quill.
 * <ul>
 *   <li>Click a block with nothing (or a finished area) marked: if it is part of a building, the
 *       whole building is selected and waits to be confirmed; on terrain or a lone block it becomes
 *       the first corner.</li>
 *   <li>With one corner marked, a block or the air marks the second one.</li>
 *   <li>A waiting building is confirmed by resizing it or by clicking the air (which also opens the
 *       save screen); sneak + click turns it back into "first corner = the clicked block".</li>
 *   <li>Resize keys move the face of the box the player points at; with one corner marked they
 *       change how far ahead a click into the air marks the corner.</li>
 * </ul>
 */
public final class QuillActions {
    /** Blocks the building search visits at most; beyond that the player marks the corners by hand. */
    public static final int MAX_DETECT_BLOCKS = 65536;

    private QuillActions() {
    }

    @Nullable
    public static ItemStack heldQuill(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(ModItems.BLUEPRINT_QUILL.get())) return stack;
        }
        return null;
    }

    @Nullable
    private static CaptureArea area(ServerPlayer player, ItemStack stack) {
        CaptureArea area = stack.get(ModDataComponents.CAPTURE_AREA.get());
        return area != null && area.dimension().equals(player.level().dimension()) ? area : null;
    }

    private static int airDistance(ItemStack stack) {
        CaptureArea area = stack.get(ModDataComponents.CAPTURE_AREA.get());
        return area == null ? CaptureArea.DEFAULT_AIR_DISTANCE : area.airDistance();
    }

    private static void set(ItemStack stack, CaptureArea area) {
        stack.set(ModDataComponents.CAPTURE_AREA.get(), area);
    }

    public static void clickBlock(ServerPlayer player, ItemStack stack, BlockPos pos) {
        CaptureArea area = area(player, stack);
        if (area != null && !area.complete()) {
            secondCorner(player, stack, area, pos);
            return;
        }
        ServerLevel level = player.serverLevel();
        int distance = airDistance(stack);
        StructureDetector.Result found = StructureDetector.detect(level, pos, MAX_DETECT_BLOCKS, MaidBuilderConfig.MAX_CAPTURE_VOLUME.get());
        if (found.outcome() == StructureDetector.Outcome.FOUND) {
            set(stack, new CaptureArea(level.dimension(), found.min(), Optional.of(found.max()), Optional.of(pos.immutable()), distance));
            BlockPos size = stack.get(ModDataComponents.CAPTURE_AREA.get()).size();
            player.displayClientMessage(Component.translatable("message.maidbuilder.quill.auto", found.blocks(),
                    size(size)), true);
            return;
        }
        set(stack, CaptureArea.corner(level.dimension(), pos, distance));
        player.displayClientMessage(Component.translatable(found.outcome() == StructureDetector.Outcome.TOO_BIG
                ? "message.maidbuilder.quill.too_big" : "message.maidbuilder.quill.first", pos.toShortString()), true);
    }

    public static void clickAir(ServerPlayer player, ItemStack stack) {
        CaptureArea stored = stack.get(ModDataComponents.CAPTURE_AREA.get());
        CaptureArea area = area(player, stack);
        if (stored != null && stored.complete() && area == null) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.quill.other_dimension"), true);
        } else if (area == null) {
            BlockPos pos = CaptureArea.airPoint(player, airDistance(stack));
            set(stack, CaptureArea.corner(player.level().dimension(), pos, airDistance(stack)));
            player.displayClientMessage(Component.translatable("message.maidbuilder.quill.first", pos.toShortString()), true);
        } else if (!area.complete()) {
            secondCorner(player, stack, area, CaptureArea.airPoint(player, area.airDistance()));
        } else if (area.autoPending()) {
            // confirmed; the client opens the save screen
            set(stack, CaptureArea.box(area.dimension(), area.min(), area.max(), area.airDistance()));
        }
    }

    /** Sneak + click: a waiting building falls back to its clicked block as the first corner; otherwise clear. */
    public static void sneakClick(ServerPlayer player, ItemStack stack) {
        CaptureArea area = area(player, stack);
        if (area != null && area.autoPending()) {
            BlockPos anchor = area.anchor().get();
            set(stack, CaptureArea.corner(area.dimension(), anchor, area.airDistance()));
            player.displayClientMessage(Component.translatable("message.maidbuilder.quill.manual", anchor.toShortString()), true);
            return;
        }
        stack.remove(ModDataComponents.CAPTURE_AREA.get());
        player.displayClientMessage(Component.translatable("message.maidbuilder.quill.cleared"), true);
    }

    /** A resize key: move the face pointed at, or with one corner marked, the air distance. */
    public static void adjust(ServerPlayer player, boolean grow, int amount) {
        ItemStack stack = heldQuill(player);
        if (stack == null) return;
        CaptureArea area = area(player, stack);
        if (area == null) return;
        int step = Math.max(1, Math.min(amount, 16)) * (grow ? 1 : -1);
        if (!area.complete()) {
            CaptureArea updated = area.withAirDistance(area.airDistance() + step);
            set(stack, updated);
            player.displayClientMessage(Component.translatable("message.maidbuilder.quill.distance", updated.airDistance()), true);
            return;
        }
        Direction face = area.faceFor(player);
        CaptureArea updated = area.resized(face, step);
        set(stack, updated);
        player.displayClientMessage(Component.translatable("message.maidbuilder.quill.resized",
                Component.translatable("direction.maidbuilder." + face.getSerializedName()), size(updated.size()), updated.volume()), true);
    }

    private static void secondCorner(ServerPlayer player, ItemStack stack, CaptureArea area, BlockPos pos) {
        CaptureArea updated = area.withSecond(pos);
        set(stack, updated);
        player.displayClientMessage(Component.translatable("message.maidbuilder.quill.second", pos.toShortString(), size(updated.size())), true);
    }

    private static String size(BlockPos size) {
        return size.getX() + "x" + size.getY() + "x" + size.getZ();
    }
}
