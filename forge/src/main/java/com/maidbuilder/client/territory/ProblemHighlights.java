package com.maidbuilder.client.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.client.MaidBuilderClientConfig;
import com.maidbuilder.client.preview.GhostPreview;
import com.maidbuilder.client.preview.ShaderCompat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Outlines the blocks a job left for the player for a while after the player asked where they
 * are, seen through walls (they are often inside the building).
 */
@Mod.EventBusSubscriber(modid = MaidBuilder.MOD_ID, value = Dist.CLIENT)
public final class ProblemHighlights {
    private static final long SHOW_MILLIS = 60_000;
    private static final RenderType LINES_THROUGH_WALLS = Types.linesThroughWalls();

    /** 1.20.1: the render state shards are protected, so the render type is built from a subclass. */
    private abstract static class Types extends RenderType {
        private Types() {
            super("maidbuilder_dummy", DefaultVertexFormat.POSITION, VertexFormat.Mode.LINES, 0, false, false, () -> {
            }, () -> {
            });
        }

        static RenderType linesThroughWalls() {
            return RenderType.create("maidbuilder_lines_through_walls",
                    DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1536, false, false,
                    RenderType.CompositeState.builder()
                            .setShaderState(RENDERTYPE_LINES_SHADER)
                            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2)))
                            .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setOutputState(ITEM_ENTITY_TARGET)
                            .setWriteMaskState(COLOR_WRITE)
                            .setCullState(NO_CULL)
                            .setDepthTestState(NO_DEPTH_TEST)
                            .createCompositeState(false));
        }
    }

    private static List<BlockPos> positions = List.of();
    private static ResourceLocation dimension;
    private static long until;

    private ProblemHighlights() {
    }

    public static void show(ResourceLocation dim, List<BlockPos> blocks) {
        positions = List.copyOf(blocks);
        dimension = dim;
        until = Util.getMillis() + SHOW_MILLIS;
    }

    public static void clear() {
        positions = List.of();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        boolean shader = MaidBuilderClientConfig.SHADER_OVERLAY.get() && ShaderCompat.shaderPackInUse();
        RenderLevelStageEvent.Stage stage = shader ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        if (event.getStage() != stage || positions.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || Util.getMillis() > until) {
            positions = List.of();
            return;
        }
        if (!mc.level.dimension().location().equals(dimension)) return;

        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(LINES_THROUGH_WALLS);
        // 1.20.1: the camera rotation is on the event's pose stack (rebuilt for AFTER_LEVEL), as for the territory borders
        PoseStack pose = GhostPreview.viewPose(event);
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        // a slow pulse so the boxes stand out from other outlines
        float pulse = 0.6f + 0.4f * (float) Math.sin(Util.getMillis() / 250.0);
        for (BlockPos p : positions) {
            LevelRenderer.renderLineBox(pose, lines, p.getX() + 0.02, p.getY() + 0.02, p.getZ() + 0.02,
                    p.getX() + 0.98, p.getY() + 0.98, p.getZ() + 0.98, 1f, 0.25f, 0.2f, pulse);
        }
        pose.popPose();
        buffers.endBatch(LINES_THROUGH_WALLS);
    }
}
