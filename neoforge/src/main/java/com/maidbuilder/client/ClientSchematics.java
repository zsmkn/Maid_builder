package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.schematic.SchematicReader;
import net.minecraft.Util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schematics in the player's own {@code schematics/} folder, loaded off the render thread.
 * Server config is not available on a multiplayer client, so fixed generous limits apply here.
 */
public final class ClientSchematics {
    private static final long MAX_VOLUME = 256L * 256 * 384;
    private static final long MAX_NBT_BYTES = 512L * 1024 * 1024;

    /** A loaded file: its bytes (for uploads), SHA-1 and parsed schematic. */
    public record Loaded(String file, String sha1, byte[] bytes, Schematic schematic, long modified) {
    }

    private static final Map<String, CompletableFuture<Loaded>> CACHE = new ConcurrentHashMap<>();

    private ClientSchematics() {
    }

    public static List<String> list() {
        return SchematicStore.listUserFiles();
    }

    public static Path folder() {
        return SchematicStore.userDir();
    }

    /** Loads (or returns the cached load of) a file; reloads when the file changed on disk. */
    public static CompletableFuture<Loaded> load(String file) {
        CompletableFuture<Loaded> cached = CACHE.get(file);
        if (cached != null && cached.isDone() && !cached.isCompletedExceptionally()) {
            try {
                Path path = SchematicStore.resolveUserFile(file);
                if (Files.getLastModifiedTime(path).toMillis() == cached.join().modified()) return cached;
            } catch (IOException e) {
                return cached; // file gone: keep showing what we have
            }
        } else if (cached != null && !cached.isDone()) {
            return cached;
        }
        CompletableFuture<Loaded> future = CompletableFuture.supplyAsync(() -> {
            try {
                Path path = SchematicStore.resolveUserFile(file);
                long modified = Files.getLastModifiedTime(path).toMillis();
                byte[] bytes = Files.readAllBytes(path);
                Schematic schematic = new SchematicReader(MAX_VOLUME, MAX_NBT_BYTES).read(new ByteArrayInputStream(bytes));
                return new Loaded(file, sha1(bytes), bytes, schematic, modified);
            } catch (IOException e) {
                MaidBuilder.LOGGER.warn("Cannot load schematic {}", file, e);
                throw new java.io.UncheckedIOException(e);
            }
        }, Util.backgroundExecutor());
        CACHE.put(file, future);
        return future;
    }

    public static void clear() {
        CACHE.clear();
    }

    static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
