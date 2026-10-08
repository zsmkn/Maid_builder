package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.client.preview.GhostPreview;
import com.maidbuilder.client.preview.ShaderCompat;
import com.maidbuilder.client.territory.BuildMode;
import com.maidbuilder.client.territory.TerritoryKeys;
import com.maidbuilder.init.ModDataComponents;
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
    private static int pendingClearRadius;
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
        if (ClientSelfTest.tick(mc) && !BuildMode.active()) return;
        while (TerritoryKeys.BUILD_MODE.consumeClick()) {
            if (mc.screen == null) BuildMode.onBuildKey(mc);
        }
        boolean building = BuildMode.active();
        ItemStack wand = building ? null : heldWand(player);
        WandPlacement placement;
        if (building) {
            BuildMode.tick(mc);
            placement = BuildMode.placement();
        } else {
            placement = wand == null ? null : wand.get(ModDataComponents.WAND_PLACEMENT.get());
        }
        handleKeys(wand, placement);
        if (placement == null) {
            clear();
            return;
        }
        int clearRadius = clearRadius(wand, placement);
        if ((current == null || !current.placement.equals(placement) || current.clearRadius != clearRadius) && !placement.equals(failedKey)) {
            requestPreview(placement, clearRadius);
        }
        if (current != null) {
            current.setInvalid(building && BuildMode.problem() != null);
            current.tick(mc.level, SCAN_BUDGET);
        }
    }

    /** Wand keys move the placement; held with the Blueprint Quill instead, up/down resize its box. */
    private static void handleKeys(@Nullable ItemStack wand, @Nullable WandPlacement placement) {
        LocalPlayer player = Minecraft.getInstance().player;
        boolean quill = wand == null && player != null && QuillClient.heldQuill(player) != null;
        for (KeyMapping key : WandKeys.ALL) {
            while (key.consumeClick()) {
                if (BuildMode.active()) {
                    BuildMode.adjust(key);
                } else if (wand != null && placement != null && !wand.has(ModDataComponents.BUILD_JOB.get())) {
                    PacketDistributor.sendToServer(new Payloads.AdjustPlacement(WandKeys.ACTIONS.get(key)));
                } else if (quill && (key == WandKeys.UP || key == WandKeys.DOWN)) {
                    PacketDistributor.sendToServer(new Payloads.QuillAdjust(key == WandKeys.UP, QuillClient.step()));
                }
            }
        }
        while (WandKeys.MATERIALS.consumeClick()) {
            if (BuildMode.active()) {
                if (current != null) Minecraft.getInstance().setScreen(MaterialListScreen.forPreview());
            } else if (wand != null) {
                openMaterials(wand, placement);
            }
        }
    }

    /** Linked wand: ask the server for the job's list; otherwise list the previewed placement. */
    private static void openMaterials(ItemStack wand, @Nullable WandPlacement placement) {
        Minecraft mc = Minecraft.getInstance();
        if (wand.has(ModDataComponents.BUILD_JOB.get())) {
            PacketDistributor.sendToServer(new Payloads.RequestMaterialReport(java.util.Optional.empty(), true));
        } else if (placement == null) {
            if (mc.player != null) mc.player.displayClientMessage(Component.translatable("message.maidbuilder.wand.select_first"), true);
        } else {
            mc.setScreen(MaterialListScreen.forPreview());
        }
    }

    /**
     * How far the previewed placement would clear the schematic's air: a linked wand's job says so
     * itself; anything else would become a new job with the server's setting.
     */
    private static int clearRadius(@Nullable ItemStack wand, WandPlacement placement) {
        java.util.UUID job = wand == null ? null : wand.get(ModDataComponents.BUILD_JOB.get());
        if (job == null) return ClientPayloadHandler.defaultAirRadius();
        Payloads.JobStatus status = ClientPayloadHandler.status(job);
        if (status != null) return status.clearRadius();
        // the job's status arrives a moment after the wand is taken in hand: keep what is shown
        return current != null && current.placement.equals(placement) ? current.clearRadius : 0;
    }

    private static void requestPreview(WandPlacement placement, int clearRadius) {
        if (placement.equals(pendingKey) && clearRadius == pendingClearRadius) return;
        boolean template = placement.file().startsWith(BuildMode.FILE_PREFIX);
        // a template follows the crosshair: build one preview at a time, the latest position wins
        if (template && pendingKey != null) return;
        ClientSchematics.Loaded templateFile = template ? BuildMode.loaded() : null;
        if (template && templateFile == null) return;
        pendingKey = placement;
        pendingClearRadius = clearRadius;
        Minecraft mc = Minecraft.getInstance();
        (template ? java.util.concurrent.CompletableFuture.completedFuture(templateFile) : ClientSchematics.load(placement.file()))
                .thenApplyAsync(loaded -> {
                    fileChanged = !loaded.sha1().equals(placement.sha1());
                    return GhostPreview.create(loaded.schematic(), placement, clearRadius);
                }, Util.backgroundExecutor())
                .whenCompleteAsync((preview, error) -> {
                    if (!placement.equals(pendingKey) && !template) {
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
     * Iris has composited the frame but before the depth buffer is cleared for the hand).
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
        com.maidbuilder.client.territory.ClientTerritories.clear();
        BuildMode.stop();
    }
}
