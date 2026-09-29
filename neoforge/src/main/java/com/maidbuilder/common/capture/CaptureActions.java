package com.maidbuilder.common.capture;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.init.ModDataComponents;
import com.maidbuilder.init.ModItems;
import com.maidbuilder.item.CaptureArea;
import com.maidbuilder.network.Payloads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of saving a blueprint: captures the area and streams the file to the requesting
 * player's client, which writes it into its own {@code schematics/} folder (on a dedicated server
 * that is the only folder the player can use; on single player it is the same folder anyway).
 */
public final class CaptureActions {
    /** Ticks between two captures by the same player. */
    private static final int COOLDOWN_TICKS = 40;
    private static final Map<UUID, Long> LAST_CAPTURE = new HashMap<>();

    private CaptureActions() {
    }

    /** The player confirmed the save screen of the Blueprint Quill they hold. */
    public static void onRequest(ServerPlayer player, Payloads.CaptureRequest request) {
        ItemStack quill = heldQuill(player);
        if (quill == null) return;
        CaptureArea area = quill.get(ModDataComponents.CAPTURE_AREA.get());
        if (area == null || !area.complete()) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.quill.need_corners"), true);
            return;
        }
        if (!area.dimension().equals(player.level().dimension())) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.quill.other_dimension"), true);
            return;
        }
        save(player, player.serverLevel(), area.min(), area.max(), request.name());
    }

    /** Captures {@code min..max} and sends it to the player as {@code <name>.litematic}. Shared with {@code /maidbuilder save}. */
    public static boolean save(ServerPlayer player, ServerLevel level, BlockPos min, BlockPos max, String rawName) {
        String name = SchematicStore.sanitizeFileName(rawName);
        if (name == null) {
            player.sendSystemMessage(Component.translatable("message.maidbuilder.capture.bad_name"));
            return false;
        }
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        int limit = MaidBuilderConfig.MAX_CAPTURE_VOLUME.get();
        if (volume > limit) {
            player.sendSystemMessage(Component.translatable("message.maidbuilder.capture.too_big", volume, limit));
            return false;
        }
        if (!level.hasChunksAt(min, max)) {
            player.sendSystemMessage(Component.translatable("message.maidbuilder.capture.not_loaded"));
            return false;
        }
        long now = level.getGameTime();
        Long last = LAST_CAPTURE.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS && now >= last) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.capture.wait"), true);
            return false;
        }
        LAST_CAPTURE.put(player.getUUID(), now);

        byte[] bytes;
        Schematic schematic;
        try {
            schematic = SchematicCapture.capture(level, min, max, name, player.getGameProfile().getName());
            bytes = SchematicCapture.toBytes(schematic);
        } catch (IOException | RuntimeException e) {
            MaidBuilder.LOGGER.error("Capturing {}..{} failed", min, max, e);
            player.sendSystemMessage(Component.translatable("message.maidbuilder.capture.failed", String.valueOf(e.getMessage())));
            return false;
        }
        MaidBuilder.LOGGER.debug("Captured {}..{} as {} ({} blocks, {} bytes) for {}", min, max, name,
                schematic.countNonAir(), bytes.length, player.getGameProfile().getName());
        send(player, name, bytes);
        return true;
    }

    private static void send(ServerPlayer player, String name, byte[] bytes) {
        int size = Payloads.DOWNLOAD_CHUNK_BYTES;
        int total = Math.max(1, (bytes.length + size - 1) / size);
        for (int i = 0; i < total; i++) {
            byte[] part = Arrays.copyOfRange(bytes, i * size, Math.min(bytes.length, (i + 1) * size));
            PacketDistributor.sendToPlayer(player, new Payloads.DownloadChunk(name, i, total, part));
        }
    }

    @Nullable
    public static ItemStack heldQuill(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(ModItems.BLUEPRINT_QUILL.get())) return stack;
        }
        return null;
    }

    public static void forget(UUID player) {
        LAST_CAPTURE.remove(player);
    }
}
