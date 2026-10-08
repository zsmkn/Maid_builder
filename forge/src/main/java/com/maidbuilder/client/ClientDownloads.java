package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.network.Payloads;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Receives captured blueprints from the server and writes them into the player's own
 * {@code schematics/} folder. Only plain {@code <name>.litematic} files directly in that folder
 * are ever written, and an existing file is only replaced when the player chose to overwrite it
 * in the save screen; otherwise a free name is picked.
 */
public final class ClientDownloads {
    private static final long MAX_BYTES = 256L * 1024 * 1024;

    private static final class Pending {
        final byte[][] parts;
        int received;

        Pending(int total) {
            this.parts = new byte[total][];
        }
    }

    private static final Map<String, Pending> PENDING = new HashMap<>();
    private static final Set<String> OVERWRITE = new HashSet<>();

    private ClientDownloads() {
    }

    /** The player confirmed replacing {@code name} in the save screen. */
    public static void allowOverwrite(String name) {
        OVERWRITE.add(name);
    }

    public static void onChunk(Payloads.DownloadChunk chunk) {
        String name = SchematicStore.sanitizeFileName(chunk.name());
        int total = chunk.total();
        if (name == null || !name.equals(chunk.name()) || total <= 0 || total > MAX_BYTES / Payloads.DOWNLOAD_CHUNK_BYTES
                || chunk.index() < 0 || chunk.index() >= total) {
            MaidBuilder.LOGGER.warn("Ignoring malformed blueprint download {}", chunk.name());
            return;
        }
        Pending pending = PENDING.get(name);
        if (pending == null || pending.parts.length != total) {
            pending = new Pending(total);
            PENDING.put(name, pending);
        }
        if (pending.parts[chunk.index()] == null) {
            pending.parts[chunk.index()] = chunk.data();
            pending.received++;
        }
        if (pending.received < total) return;
        PENDING.remove(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : pending.parts) out.writeBytes(part);
        boolean overwrite = OVERWRITE.remove(name);
        byte[] bytes = out.toByteArray();
        Util.ioPool().execute(() -> write(name, bytes, overwrite));
    }

    private static void write(String name, byte[] bytes, boolean overwrite) {
        Minecraft mc = Minecraft.getInstance();
        try {
            Path file = SchematicStore.newUserFile(name);
            for (int n = 2; !overwrite && Files.exists(file); n++) file = SchematicStore.newUserFile(name + "_" + n);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            String saved = file.getFileName().toString();
            mc.execute(() -> {
                ClientSchematics.clear();
                if (mc.player != null) mc.player.displayClientMessage(Component.translatable("message.maidbuilder.capture.saved", saved), false);
            });
        } catch (IOException e) {
            MaidBuilder.LOGGER.error("Cannot save blueprint {}", name, e);
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable("message.maidbuilder.capture.failed", String.valueOf(e.getMessage())), false);
                }
            });
        }
    }

    public static void clear() {
        PENDING.clear();
        OVERWRITE.clear();
    }
}
