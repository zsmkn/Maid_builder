package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.client.preview.GhostPreview;
import com.maidbuilder.client.preview.ShaderCompat;
import com.maidbuilder.init.ModItemData;
import com.maidbuilder.init.ModItems;
import com.maidbuilder.item.WandPlacement;
import com.maidbuilder.network.Payloads;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import com.maidbuilder.network.ModNetwork;

import javax.annotation.Nullable;

/**
 * Keeps a {@link GhostPreview} in sync with the Blueprint Wand the player holds, forwards wand
 * keys to the server and draws the preview.
 */
@Mod.EventBusSubscriber(modid = MaidBuilder.MOD_ID, value = Dist.CLIENT)
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
        return ClientSelfTest.preview != null ? ClientSelfTest.preview : current;
    }

    @Nullable
    public static String loadError() {
        return loadError;
    }

    public static boolean fileChanged() {
        return fileChanged;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            clear();
            return;
        }
        ClientNetSelfTest.tick(mc);
        if (ClientSelfTest.tick(mc)) return;
        ItemStack wand = heldWand(player);
        WandPlacement placement = wand == null ? null : ModItemData.WAND_PLACEMENT.get(wand);
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

    /** Wand keys move the placement; held with the Blueprint Quill instead, up/down resize its box. */
    private static void handleKeys(@Nullable ItemStack wand, @Nullable WandPlacement placement) {
        LocalPlayer player = Minecraft.getInstance().player;
        boolean quill = wand == null && player != null && QuillClient.heldQuill(player) != null;
        for (KeyMapping key : WandKeys.ALL) {
            while (key.consumeClick()) {
                if (wand != null && placement != null && !ModItemData.BUILD_JOB.has(wand)) {
                    ModNetwork.sendToServer(new Payloads.AdjustPlacement(WandKeys.ACTIONS.get(key)));
                } else if (quill && (key == WandKeys.UP || key == WandKeys.DOWN)) {
                    ModNetwork.sendToServer(new Payloads.QuillAdjust(key == WandKeys.UP, QuillClient.step()));
                }
            }
        }
        while (WandKeys.MATERIALS.consumeClick()) {
            if (wand != null) openMaterials(wand, placement);
        }
    }

    /** Linked wand: ask the server for the job's list; otherwise list the previewed placement. */
    private static void openMaterials(ItemStack wand, @Nullable WandPlacement placement) {
        Minecraft mc = Minecraft.getInstance();
        if (ModItemData.BUILD_JOB.has(wand)) {
            ModNetwork.sendToServer(new Payloads.RequestMaterialReport(true));
        } else if (placement == null) {
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("message.maidbuilder.wand.select_first"), true);
        } else {
            mc.setScreen(MaterialListScreen.forPreview());
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

    /**
     * Normally drawn with the translucent blocks. With a shader pack the pack would light and blend
     * the ghosts like real blocks, so they are drawn after its final pass (AFTER_LEVEL comes after
     * Oculus has composited the frame but before the depth buffer is cleared for the hand).
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = MaidBuilderClientConfig.SHADER_OVERLAY.get() && ShaderCompat.shaderPackInUse()
                ? RenderLevelStageEvent.Stage.AFTER_LEVEL : RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        if (event.getStage() != stage) return;
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
