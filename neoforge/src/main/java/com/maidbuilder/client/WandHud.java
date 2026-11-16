package com.maidbuilder.client;

import com.maidbuilder.client.preview.GhostPreview;
import com.maidbuilder.client.ui.HudCard;
import com.maidbuilder.client.ui.Ui;
import com.maidbuilder.init.ModDataComponents;
import com.maidbuilder.item.WandPlacement;
import com.maidbuilder.network.Payloads;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Top-left overlay while holding a Blueprint Wand: placement, preview/job progress, missing materials. */
public final class WandHud {
    private static final int MAX_MISSING = 8;

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

        HudCard card = new HudCard(mc.font);
        int border = Ui.FRAME;
        if (placement == null) {
            card.wrapped(Component.translatable("hud.maidbuilder.no_schematic"), Ui.MUTED);
        } else {
            card.text(Component.literal(placement.file()), Ui.AMBER);
            card.text(Component.translatable("hud.maidbuilder.placement", placement.origin().toShortString(),
                    placement.rotation().getSerializedName(), placement.mirror().getSerializedName()), Ui.MUTED);
            if (PreviewManager.loadError() != null) {
                card.wrapped(Component.translatable("hud.maidbuilder.load_error"), Ui.RED);
                border = Ui.RED;
            } else if (PreviewManager.fileChanged()) {
                card.wrapped(Component.translatable("hud.maidbuilder.file_changed"), Ui.AMBER);
            }
        }

        Payloads.JobStatus status = jobId == null ? null : ClientPayloadHandler.status(jobId);
        if (status != null) {
            boolean complete = status.total() > 0 && status.done() >= status.total();
            card.bar(status.total() <= 0 ? 0 : (double) status.done() / status.total(), status.done() + " / " + status.total(),
                    complete ? Ui.GREEN : Ui.FRAME);
            card.text(Component.translatable("hud.maidbuilder.team", status.maids(), status.sources()), Ui.MUTED);
            if (status.needsPlayer() > 0 || status.failed() > 0) {
                card.text(Component.translatable("hud.maidbuilder.issues", status.needsPlayer(), status.failed()), Ui.AMBER);
                border = Ui.AMBER;
            }
            if (status.toClear() > 0) card.text(Component.translatable("hud.maidbuilder.clearing", status.toClear()), Ui.AMBER);
            if (!status.missing().isEmpty()) {
                List<HudCard.Count> counts = new ArrayList<>();
                for (Payloads.ItemCount missing : status.missing().subList(0, Math.min(MAX_MISSING, status.missing().size()))) {
                    counts.add(new HudCard.Count(missing.item(), missing.count()));
                }
                card.rule();
                card.items(Component.translatable("hud.maidbuilder.missing_short"), Ui.RED, counts);
            }
        } else {
            GhostPreview preview = PreviewManager.current();
            if (preview != null) {
                int total = preview.total(), match = preview.count(GhostPreview.MATCH);
                card.bar(total <= 0 ? 0 : (double) match / total, match + " / " + total, Ui.FRAME);
                card.text(Component.translatable("hud.maidbuilder.preview", match, total,
                        preview.count(GhostPreview.MISSING), preview.count(GhostPreview.WRONG)), Ui.MUTED);
                if (preview.count(GhostPreview.TO_CLEAR) > 0) {
                    card.text(Component.translatable("hud.maidbuilder.preview_clear", preview.count(GhostPreview.TO_CLEAR)), Ui.AMBER);
                }
            }
        }

        card.rule();
        HudCard.Key materials = new HudCard.Key(WandKeys.MATERIALS.getTranslatedKeyMessage(), Component.translatable("hud.maidbuilder.key.materials"));
        if (jobId == null) {
            card.keys(List.of(
                    new HudCard.Key(WandKeys.ROTATE.getTranslatedKeyMessage(), Component.translatable("hud.maidbuilder.key.rotate")),
                    new HudCard.Key(WandKeys.MIRROR.getTranslatedKeyMessage(), Component.translatable("hud.maidbuilder.key.mirror")),
                    materials));
            card.wrapped(Component.translatable("hud.maidbuilder.keys_more"), Ui.MUTED);
        } else {
            card.keys(List.of(materials));
            card.wrapped(Component.translatable("hud.maidbuilder.keys_linked_more"), Ui.MUTED);
        }
        card.render(graphics, 4, 4, border);
    }
}
