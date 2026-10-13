package com.maidbuilder.client.territory;

import com.maidbuilder.client.MaidBuilderClientConfig;
import com.maidbuilder.client.WandKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Top-left overlay in build mode: template, position, whether it can go there, keys. */
public final class BuildModeHud {
    private BuildModeHud() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (!BuildMode.active() || mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()
                || !MaidBuilderClientConfig.SHOW_HUD.get()) return;
        var template = BuildMode.template();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("hud.maidbuilder.build.title", Component.translatable(template.def().nameKey()))
                .withStyle(ChatFormatting.GOLD));
        if (BuildMode.loading()) {
            lines.add(Component.translatable("hud.maidbuilder.build.loading").withStyle(ChatFormatting.GRAY));
        } else {
            if (BuildMode.origin() != null) {
                lines.add(Component.translatable("hud.maidbuilder.placement", BuildMode.origin().toShortString(),
                        BuildMode.rotation().getSerializedName(), BuildMode.mirror().getSerializedName()));
            }
            Component problem = BuildMode.problem();
            if (BuildMode.waiting()) {
                lines.add(Component.translatable("hud.maidbuilder.build.waiting").withStyle(ChatFormatting.GRAY));
            } else if (problem != null) {
                lines.add(problem.copy().withStyle(ChatFormatting.RED));
            } else {
                lines.add(Component.translatable("hud.maidbuilder.build.ok").withStyle(ChatFormatting.GREEN));
            }
        }
        lines.add(Component.translatable("hud.maidbuilder.build.keys", WandKeys.ROTATE.getTranslatedKeyMessage(),
                WandKeys.MIRROR.getTranslatedKeyMessage(), WandKeys.UP.getTranslatedKeyMessage(), WandKeys.DOWN.getTranslatedKeyMessage())
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("hud.maidbuilder.build.keys2", WandKeys.MATERIALS.getTranslatedKeyMessage(),
                TerritoryKeys.BUILD_MODE.getTranslatedKeyMessage()).withStyle(ChatFormatting.GRAY));

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
