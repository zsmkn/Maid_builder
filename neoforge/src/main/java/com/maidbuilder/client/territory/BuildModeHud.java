package com.maidbuilder.client.territory;

import com.maidbuilder.client.MaidBuilderClientConfig;
import com.maidbuilder.client.WandKeys;
import com.maidbuilder.client.ui.HudCard;
import com.maidbuilder.client.ui.Ui;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Overlay in build mode, above the hotbar: template, position, whether it can go there, keys. */
public final class BuildModeHud {
    /** Room left under the card for the hotbar, the bars above it and the action bar text. */
    private static final int BOTTOM_MARGIN = 72;

    private BuildModeHud() {
    }

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (!BuildMode.active() || mc.player == null || mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()
                || !MaidBuilderClientConfig.SHOW_HUD.get()) return;
        var template = BuildMode.template();
        HudCard card = new HudCard(mc.font);
        int border = Ui.FRAME;
        card.text(Component.translatable("hud.maidbuilder.build.title", Component.translatable(template.def().nameKey())), Ui.AMBER);
        if (BuildMode.loading()) {
            card.text(Component.translatable("hud.maidbuilder.build.loading"), Ui.MUTED);
        } else {
            if (BuildMode.origin() != null) {
                card.text(Component.translatable("hud.maidbuilder.placement", BuildMode.origin().toShortString(),
                        BuildMode.rotation().getSerializedName(), BuildMode.mirror().getSerializedName()), Ui.MUTED);
            }
            Component problem = BuildMode.problem();
            if (BuildMode.waiting()) {
                card.text(Component.translatable("hud.maidbuilder.build.waiting"), Ui.MUTED);
            } else if (problem != null) {
                card.wrapped(problem, Ui.RED);
                border = Ui.RED;
            } else {
                card.text(Component.translatable("hud.maidbuilder.build.ok"), Ui.GREEN);
                border = Ui.GREEN;
            }
        }
        card.rule();
        card.keys(List.of(
                new HudCard.Key(WandKeys.ROTATE.getTranslatedKeyMessage(), Component.translatable("hud.maidbuilder.key.rotate")),
                new HudCard.Key(WandKeys.MIRROR.getTranslatedKeyMessage(), Component.translatable("hud.maidbuilder.key.mirror")),
                new HudCard.Key(List.of(WandKeys.UP.getTranslatedKeyMessage(), WandKeys.DOWN.getTranslatedKeyMessage()),
                        Component.translatable("hud.maidbuilder.key.height"))));
        card.keys(List.of(
                new HudCard.Key(WandKeys.MATERIALS.getTranslatedKeyMessage(), Component.translatable("hud.maidbuilder.key.materials")),
                new HudCard.Key(TerritoryKeys.BUILD_MODE.getTranslatedKeyMessage(), Component.translatable("hud.maidbuilder.key.leave"))));

        int x = (graphics.guiWidth() - card.width()) / 2;
        int y = graphics.guiHeight() - BOTTOM_MARGIN - card.height();
        if (y < graphics.guiHeight() / 2 + 16) {
            // a small window: down there it would cover what she is aiming at, so go to the corner
            x = 4;
            y = 4;
        }
        card.render(graphics, x, y, border);
    }
}
