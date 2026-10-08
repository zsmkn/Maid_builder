package com.maidbuilder.client.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.client.MaidBuilderClientConfig;
import com.maidbuilder.client.preview.ShaderCompat;
import com.maidbuilder.init.ModItems;
import com.maidbuilder.network.TerritoryPayloads;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;

/**
 * Draws territory borders: a band of lines around each nearby territory while the player holds a
 * territory flag, is in build mode, or has toggled borders on with the border key.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID, value = Dist.CLIENT)
public final class TerritoryBorderRenderer {
    private static final double VIEW_DISTANCE = 256;
    private static final int POST_SPACING = 8;
    private static boolean toggled;

    private TerritoryBorderRenderer() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        while (TerritoryKeys.BORDER.consumeClick()) toggled = !toggled;
    }

    public static boolean visible(LocalPlayer player) {
        return toggled || player.isHolding(ModItems.TERRITORY_FLAG.get()) || BuildMode.active();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        boolean shader = MaidBuilderClientConfig.SHADER_OVERLAY.get() && ShaderCompat.shaderPackInUse();
        RenderLevelStageEvent.Stage stage = shader ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        if (event.getStage() != stage) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !visible(player)) return;

        Vec3 cam = event.getCamera().getPosition();
        boolean afterLevel = event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL;
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        if (afterLevel) {
            modelViewStack.pushMatrix();
            modelViewStack.mul(event.getModelViewMatrix());
            RenderSystem.applyModelViewMatrix();
        }
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack pose = new PoseStack();
        pose.translate(-cam.x, -cam.y, -cam.z);
        String dim = mc.level.dimension().location().toString();
        // a band around the player's height; the territory itself has no height limit
        double y0 = Math.floor(player.getY()) - 4, y1 = y0 + 12;
        for (TerritoryPayloads.TerritoryInfo t : ClientTerritories.all()) {
            if (!t.dimension().toString().equals(dim)) continue;
            double minX = t.flag().getX() - t.radius(), maxX = t.flag().getX() + t.radius() + 1;
            double minZ = t.flag().getZ() - t.radius(), maxZ = t.flag().getZ() + t.radius() + 1;
            double dx = Math.max(Math.max(minX - cam.x, cam.x - maxX), 0), dz = Math.max(Math.max(minZ - cam.z, cam.z - maxZ), 0);
            if (dx * dx + dz * dz > VIEW_DISTANCE * VIEW_DISTANCE) continue;
            float r, g, b;
            if (!t.active()) {
                r = 0.6f; g = 0.6f; b = 0.6f;
            } else if (t.owner().equals(player.getUUID())) {
                r = 0.3f; g = 1f; b = 0.4f;
            } else {
                r = 1f; g = 0.6f; b = 0.2f;
            }
            drawBand(pose, lines, minX, minZ, maxX, maxZ, y0, y1, r, g, b);
            for (TerritoryPayloads.Box box : BuildMode.active() ? t.boxes() : java.util.List.<TerritoryPayloads.Box>of()) {
                net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, lines, box.min().getX(), box.min().getY(), box.min().getZ(),
                        box.max().getX() + 1, box.max().getY() + 1, box.max().getZ() + 1, 0.9f, 0.9f, 0.4f, 0.7f);
            }
        }
        buffers.endBatch(RenderType.lines());
        if (afterLevel) {
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** Two horizontal rings and vertical posts along the edges. */
    private static void drawBand(PoseStack pose, VertexConsumer lines, double minX, double minZ, double maxX, double maxZ,
                                 double y0, double y1, float r, float g, float b) {
        for (double y : new double[]{y0, y1}) {
            line(pose, lines, minX, y, minZ, maxX, y, minZ, r, g, b);
            line(pose, lines, maxX, y, minZ, maxX, y, maxZ, r, g, b);
            line(pose, lines, maxX, y, maxZ, minX, y, maxZ, r, g, b);
            line(pose, lines, minX, y, maxZ, minX, y, minZ, r, g, b);
        }
        for (double x = minX; x <= maxX; x += POST_SPACING) {
            line(pose, lines, x, y0, minZ, x, y1, minZ, r, g, b);
            line(pose, lines, x, y0, maxZ, x, y1, maxZ, r, g, b);
        }
        for (double z = minZ; z <= maxZ; z += POST_SPACING) {
            line(pose, lines, minX, y0, z, minX, y1, z, r, g, b);
            line(pose, lines, maxX, y0, z, maxX, y1, z, r, g, b);
        }
    }

    private static void line(PoseStack pose, VertexConsumer lines, double x0, double y0, double z0, double x1, double y1, double z1,
                             float r, float g, float b) {
        float nx = (float) (x1 - x0), ny = (float) (y1 - y0), nz = (float) (z1 - z0);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) return;
        nx /= len;
        ny /= len;
        nz /= len;
        PoseStack.Pose last = pose.last();
        lines.addVertex(last, (float) x0, (float) y0, (float) z0).setColor(r, g, b, 0.9f).setNormal(last, nx, ny, nz);
        lines.addVertex(last, (float) x1, (float) y1, (float) z1).setColor(r, g, b, 0.9f).setNormal(last, nx, ny, nz);
    }
}
