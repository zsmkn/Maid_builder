package com.maidbuilder.common.wand;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.network.Payloads;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reassembles schematics uploaded in chunks (serverbound payloads are limited to ~32 KB).
 * Only uploads the server asked for are accepted, one per player, bounded by
 * {@code maxUploadBytes}, and verified against the announced SHA-1 before being stored.
 */
public final class UploadManager {
    private static final class Pending {
        final String sha1;
        final String file;
        byte[][] chunks;
        int received;
        long bytes;

        Pending(String sha1, String file) {
            this.sha1 = sha1;
            this.file = file;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private UploadManager() {
    }

    static void expect(ServerPlayer player, String sha1, String file) {
        PENDING.put(player.getUUID(), new Pending(sha1, file));
    }

    public static void forget(UUID player) {
        PENDING.remove(player);
    }

    public static void receive(ServerPlayer player, Payloads.UploadChunk chunk) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null || !pending.sha1.equals(chunk.sha1())) return;
        int max = MaidBuilderConfig.MAX_UPLOAD_BYTES.get();
        int maxChunks = (max + Payloads.UPLOAD_CHUNK_BYTES - 1) / Payloads.UPLOAD_CHUNK_BYTES;
        if (pending.chunks == null) {
            if (chunk.total() <= 0 || chunk.total() > maxChunks) {
                fail(player, pending, "too large");
                return;
            }
            pending.chunks = new byte[chunk.total()][];
        }
        if (chunk.total() != pending.chunks.length || chunk.index() < 0 || chunk.index() >= pending.chunks.length
                || pending.chunks[chunk.index()] != null) {
            fail(player, pending, "malformed upload");
            return;
        }
        pending.bytes += chunk.data().length;
        if (pending.bytes > max) {
            fail(player, pending, "too large");
            return;
        }
        pending.chunks[chunk.index()] = chunk.data();
        pending.received++;
        if (pending.received < pending.chunks.length) return;

        PENDING.remove(player.getUUID());
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) pending.bytes);
        for (byte[] part : pending.chunks) out.writeBytes(part);
        byte[] bytes = out.toByteArray();
        try {
            if (!sha1(bytes).equals(pending.sha1)) throw new IOException("checksum mismatch");
            SchematicStore.storeBytes(player.server, bytes);
        } catch (IOException e) {
            MaidBuilder.LOGGER.warn("Rejected schematic upload {} from {}", pending.file, player.getGameProfile().getName(), e);
            player.sendSystemMessage(Component.translatable("message.maidbuilder.upload.failed", pending.file, e.getMessage()));
            return;
        }
        player.displayClientMessage(Component.translatable("message.maidbuilder.upload.done", pending.file), true);
        WandActions.onUploadComplete(player, pending.sha1);
    }

    private static void fail(ServerPlayer player, Pending pending, String reason) {
        PENDING.remove(player.getUUID());
        player.sendSystemMessage(Component.translatable("message.maidbuilder.upload.failed", pending.file, reason));
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
