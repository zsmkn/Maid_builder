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
import net.minecraft.core.BlockPos;
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
    private static ItemStack heldQuill(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(ModItems.BLUEPRINT_QUILL.get())) return stack;
        }
        return null;
    }

    /** The marked area, or while only the first corner is set, the area up to the looked-at block. */
    @Nullable
    private static CaptureArea shownArea(Minecraft mc, ItemStack quill) {
        CaptureArea area = quill.get(ModDataComponents.CAPTURE_AREA.get());
        if (area == null || mc.level == null || !area.dimension().equals(mc.level.dimension())) return null;
        if (!area.complete() && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return new CaptureArea(area.dimension(), area.first(), java.util.Optional.of(hit.getBlockPos()));
        }
        return area;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ItemStack quill = heldQuill(mc.player);
        if (quill == null) return;
        CaptureArea marked = quill.get(ModDataComponents.CAPTURE_AREA.get());
        CaptureArea shown = shownArea(mc, quill);
        if (marked == null || shown == null) return;

        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = new PoseStack();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        AABB box = new AABB(Vec3.atLowerCornerOf(shown.min()), Vec3.atLowerCornerOf(shown.max()).add(1, 1, 1)).inflate(0.01);
        if (marked.complete()) {
            LevelRenderer.renderLineBox(poseStack, lines, box, 0.3f, 0.8f, 1f, 1f);
        } else {
            LevelRenderer.renderLineBox(poseStack, lines, box, 1f, 1f, 1f, 0.5f);
        }
        LevelRenderer.renderLineBox(poseStack, lines, new AABB(marked.first()).inflate(0.02), 1f, 0.85f, 0.2f, 1f);
        marked.second().ifPresent(p -> LevelRenderer.renderLineBox(poseStack, lines, new AABB(p).inflate(0.02), 1f, 0.5f, 0.1f, 1f));
        buffers.endBatch(RenderType.lines());
    }

    public static void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
        ItemStack quill = heldQuill(mc.player);
        if (quill == null || PreviewManager.heldWand(mc.player) != null) return;
        CaptureArea marked = quill.get(ModDataComponents.CAPTURE_AREA.get());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("item.maidbuilder.blueprint_quill").withStyle(ChatFormatting.GOLD));
        if (marked == null) {
            lines.add(Component.translatable("hud.maidbuilder.quill.first"));
        } else {
            lines.add(Component.translatable("tooltip.maidbuilder.quill.first", marked.first().toShortString()));
            if (marked.complete()) {
                lines.add(Component.translatable("tooltip.maidbuilder.quill.second", marked.second().get().toShortString()));
            } else {
                lines.add(Component.translatable("hud.maidbuilder.quill.second"));
            }
            CaptureArea shown = shownArea(mc, quill);
            if (shown != null && shown.complete()) {
                BlockPos s = shown.size();
                lines.add(Component.translatable("hud.maidbuilder.quill.size", s.getX(), s.getY(), s.getZ(), shown.volume())
                        .withStyle(ChatFormatting.AQUA));
            }
        }
        lines.add(Component.translatable("hud.maidbuilder.quill.keys").withStyle(ChatFormatting.DARK_GRAY));

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

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDownloads.clear();
    }
}
