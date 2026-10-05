package com.maidbuilder.client;

import com.maidbuilder.client.preview.GhostPreview;
import com.maidbuilder.init.ModDataComponents;
import com.maidbuilder.item.WandPlacement;
import com.maidbuilder.network.Payloads;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Top-left overlay while holding a Blueprint Wand: placement, preview/job progress, missing materials. */
public final class WandHud {
    private static final int MAX_MISSING_LINES = 6;

    private WandHud() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()
                || !MaidBuilderClientConfig.SHOW_HUD.get()) return;
        ItemStack wand = PreviewManager.heldWand(mc.player);
        if (wand == null) return;
        WandPlacement placement = wand.get(ModDataComponents.WAND_PLACEMENT.get());
        UUID jobId = wand.get(ModDataComponents.BUILD_JOB.get());

        List<Component> lines = new ArrayList<>();
        if (placement == null) {
            lines.add(Component.translatable("hud.maidbuilder.no_schematic").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.translatable("hud.maidbuilder.schematic", placement.file()).withStyle(ChatFormatting.GOLD));
            lines.add(Component.translatable("hud.maidbuilder.placement", placement.origin().toShortString(),
                    placement.rotation().getSerializedName(), placement.mirror().getSerializedName()));
            if (PreviewManager.loadError() != null) {
                lines.add(Component.translatable("hud.maidbuilder.load_error").withStyle(ChatFormatting.RED));
            } else if (PreviewManager.fileChanged()) {
                lines.add(Component.translatable("hud.maidbuilder.file_changed").withStyle(ChatFormatting.YELLOW));
            }
        }

        Payloads.JobStatus status = jobId == null ? null : ClientPayloadHandler.status(jobId);
        if (status != null) {
            lines.add(Component.translatable("hud.maidbuilder.job", status.done(), status.total(), status.needsPlayer(),
                    status.failed(), status.sources(), status.maids()).withStyle(ChatFormatting.AQUA));
            if (!status.missing().isEmpty()) {
                lines.add(Component.translatable("hud.maidbuilder.missing").withStyle(ChatFormatting.RED));
                for (Payloads.ItemCount missing : status.missing().subList(0, Math.min(MAX_MISSING_LINES, status.missing().size()))) {
                    lines.add(Component.literal("  ").append(missing.item().getDescription()).append(" x" + missing.count()));
                }
            }
        } else {
            GhostPreview preview = PreviewManager.current();
            if (preview != null) {
                lines.add(Component.translatable("hud.maidbuilder.preview", preview.count(GhostPreview.MATCH), preview.total(),
                        preview.count(GhostPreview.MISSING), preview.count(GhostPreview.WRONG)));
            }
        }
        lines.add(Component.translatable(jobId == null ? "hud.maidbuilder.keys" : "hud.maidbuilder.keys_linked",
                WandKeys.ROTATE.getTranslatedKeyMessage(), WandKeys.MIRROR.getTranslatedKeyMessage(),
                WandKeys.MATERIALS.getTranslatedKeyMessage()).withStyle(ChatFormatting.DARK_GRAY));

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
}
