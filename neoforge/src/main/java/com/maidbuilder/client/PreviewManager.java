package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.client.preview.GhostPreview;
import com.maidbuilder.init.ModDataComponents;
import com.maidbuilder.init.ModItems;
import com.maidbuilder.item.WandPlacement;
import com.maidbuilder.network.Payloads;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * Keeps a {@link GhostPreview} in sync with the Blueprint Wand the player holds, forwards wand
 * keys to the server and draws the preview.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID, value = Dist.CLIENT)
public final class PreviewManager {
    /** Target blocks compared with the world per client tick. */
    private static final int SCAN_BUDGET = 8192;

    @Nullable
    private static GhostPreview current;
    @Nullable
    private static WandPlacement pendingKey;
    @Nullable
    private static String loadError;
    /** Placement whose file could not be loaded; not retried until the placement changes. */
    @Nullable
    private static WandPlacement failedKey;
    private static boolean fileChanged;

    private PreviewManager() {
    }

    @Nullable
    public static ItemStack heldWand(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(ModItems.BLUEPRINT_WAND.get())) return stack;
        }
        return null;
    }

    @Nullable
    public static GhostPreview current() {
        return current;
    }

    @Nullable
    public static String loadError() {
        return loadError;
    }

    public static boolean fileChanged() {
        return fileChanged;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            clear();
            return;
        }
        if (ClientSelfTest.tick(mc)) return;
        ItemStack wand = heldWand(player);
        WandPlacement placement = wand == null ? null : wand.get(ModDataComponents.WAND_PLACEMENT.get());
        handleKeys(wand, placement);
        if (placement == null) {
            clear();
            return;
        }
        if ((current == null || !current.placement.equals(placement)) && !placement.equals(failedKey)) {
            requestPreview(placement);
        }
        if (current != null) current.tick(mc.level, SCAN_BUDGET);
    }

    private static void handleKeys(@Nullable ItemStack wand, @Nullable WandPlacement placement) {
        for (KeyMapping key : WandKeys.ALL) {
            while (key.consumeClick()) {
                if (wand != null && placement != null && !wand.has(ModDataComponents.BUILD_JOB.get())) {
                    PacketDistributor.sendToServer(new Payloads.AdjustPlacement(WandKeys.ACTIONS.get(key)));
                }
            }
        }
    }

    private static void requestPreview(WandPlacement placement) {
        if (placement.equals(pendingKey)) return;
        pendingKey = placement;
        Minecraft mc = Minecraft.getInstance();
        ClientSchematics.load(placement.file())
                .thenApplyAsync(loaded -> {
                    fileChanged = !loaded.sha1().equals(placement.sha1());
                    return GhostPreview.create(loaded.schematic(), placement);
                }, Util.backgroundExecutor())
                .whenCompleteAsync((preview, error) -> {
                    if (!placement.equals(pendingKey)) {
                        if (preview != null) preview.close();
                        return;
                    }
                    pendingKey = null;
                    if (preview == null) {
                        loadError = placement.file();
                        failedKey = placement;
                        if (current != null) {
                            current.close();
                            current = null;
                        }
                        return;
                    }
                    loadError = null;
                    failedKey = null;
                    if (current != null) current.close();
                    current = preview;
                }, mc);
    }

    private static void clear() {
        pendingKey = null;
        loadError = null;
        failedKey = null;
        if (current != null) {
            current.close();
            current = null;
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        GhostPreview preview = ClientSelfTest.preview != null ? ClientSelfTest.preview : current;
        if (preview != null) preview.render(event);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        ClientPayloadHandler.clear();
        ClientSchematics.clear();
    }
}
