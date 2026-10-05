package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.init.ModDataComponents;
import com.maidbuilder.init.ModItems;
import com.maidbuilder.item.CaptureArea;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** While holding a Blueprint Quill: outline of the marked area and a small HUD. */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID, value = Dist.CLIENT)
public final class QuillClient {
    private QuillClient() {
    }

    @Nullable
    static ItemStack heldQuill(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(ModItems.BLUEPRINT_QUILL.get())) return stack;
        }
        return null;
    }

    /** Blocks one resize key press moves: 5 while Shift is held. */
    static int step() {
        return Screen.hasShiftDown() ? 5 : 1;
    }

    @Nullable
    private static CaptureArea marked(Minecraft mc, ItemStack quill) {
        CaptureArea area = quill.get(ModDataComponents.CAPTURE_AREA.get());
        return area == null || mc.level == null || !area.dimension().equals(mc.level.dimension()) ? null : area;
    }

    private static boolean lookingAtBlock(Minecraft mc) {
        return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
    }

    /** Where a right-click would put the next corner: the looked-at block, or a point in the air. */
    private static BlockPos nextCorner(Minecraft mc, @Nullable CaptureArea area) {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos();
        return CaptureArea.airPoint(mc.player, area == null ? CaptureArea.DEFAULT_AIR_DISTANCE : area.airDistance());
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ItemStack quill = heldQuill(mc.player);
        if (quill == null) return;
        CaptureArea area = marked(mc, quill);

        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = new PoseStack();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        if (area == null) {
            // where a click into the air would mark the first corner
            if (!lookingAtBlock(mc)) {
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(nextCorner(mc, null)).inflate(0.02), 1f, 1f, 1f, 0.7f);
            }
        } else if (!area.complete()) {
            BlockPos next = nextCorner(mc, area);
            LevelRenderer.renderLineBox(poseStack, lines, area.withSecond(next).bounds().inflate(0.01), 1f, 1f, 1f, 0.5f);
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(area.first()).inflate(0.02), 1f, 0.85f, 0.2f, 1f);
            if (!lookingAtBlock(mc)) LevelRenderer.renderLineBox(poseStack, lines, new AABB(next).inflate(0.02), 1f, 1f, 1f, 1f);
        } else {
            AABB box = area.bounds().inflate(0.01);
            if (area.autoPending()) {
                LevelRenderer.renderLineBox(poseStack, lines, box, 1f, 0.8f, 0.2f, 1f);
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(area.anchor().get()).inflate(0.02), 1f, 0.5f, 0.1f, 1f);
            } else {
                LevelRenderer.renderLineBox(poseStack, lines, box, 0.3f, 0.8f, 1f, 1f);
            }
            // the face the resize keys would move
            LevelRenderer.renderLineBox(poseStack, lines, face(area.bounds(), area.faceFor(mc.player)).inflate(0.03), 0.4f, 1f, 0.4f, 1f);
        }
        buffers.endBatch(RenderType.lines());
    }

    /** A flat box covering one face of {@code box}. */
    private static AABB face(AABB box, Direction face) {
        return switch (face) {
            case EAST -> new AABB(box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            case WEST -> new AABB(box.minX, box.minY, box.minZ, box.minX, box.maxY, box.maxZ);
            case UP -> new AABB(box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ);
            case DOWN -> new AABB(box.minX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ);
            case SOUTH -> new AABB(box.minX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ);
            case NORTH -> new AABB(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ);
        };
    }

    public static void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()
                || !MaidBuilderClientConfig.SHOW_HUD.get()) return;
        ItemStack quill = heldQuill(mc.player);
        if (quill == null || PreviewManager.heldWand(mc.player) != null) return;
        CaptureArea area = marked(mc, quill);
        Component grow = WandKeys.UP.getTranslatedKeyMessage(), shrink = WandKeys.DOWN.getTranslatedKeyMessage();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("item.maidbuilder.blueprint_quill").withStyle(ChatFormatting.GOLD));
        if (area == null) {
            lines.add(Component.translatable("hud.maidbuilder.quill.start"));
            lines.add(Component.translatable("hud.maidbuilder.quill.air", CaptureArea.DEFAULT_AIR_DISTANCE));
        } else if (!area.complete()) {
            lines.add(Component.translatable("tooltip.maidbuilder.quill.first", area.first().toShortString()));
            lines.add(Component.translatable("hud.maidbuilder.quill.second", area.airDistance(), grow, shrink));
            sizeLine(lines, area.withSecond(nextCorner(mc, area)));
            lines.add(Component.translatable("hud.maidbuilder.quill.keys_corner").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            Component face = Component.translatable("direction.maidbuilder." + area.faceFor(mc.player).getSerializedName());
            if (area.autoPending()) {
                lines.add(Component.translatable("hud.maidbuilder.quill.auto").withStyle(ChatFormatting.YELLOW));
            } else {
                lines.add(Component.translatable("tooltip.maidbuilder.quill.first", area.min().toShortString()));
                lines.add(Component.translatable("tooltip.maidbuilder.quill.second", area.max().toShortString()));
            }
            sizeLine(lines, area);
            lines.add(Component.translatable("hud.maidbuilder.quill.resize", grow, shrink, face).withStyle(ChatFormatting.GREEN));
            lines.add(Component.translatable(area.autoPending() ? "hud.maidbuilder.quill.keys_auto" : "hud.maidbuilder.quill.keys")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        Font font = mc.font;
        int width = 0;
        for (Component line : lines) width = Math.max(width, font.width(line));
        int x = 4, y = 4, lineHeight = font.lineHeight + 1;
        graphics.fill(x - 2, y - 2, x + width + 2, y + lines.size() * lineHeight + 1, 0x80000000);
        for (Component line : lines) {
            graphics.drawString(font, line, x, y, 0xFFFFFF, false);
            y += lineHeight;
        }
    }

    private static void sizeLine(List<Component> lines, CaptureArea area) {
        BlockPos s = area.size();
        lines.add(Component.translatable("hud.maidbuilder.quill.size", s.getX(), s.getY(), s.getZ(), area.volume())
                .withStyle(ChatFormatting.AQUA));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDownloads.clear();
    }
}
