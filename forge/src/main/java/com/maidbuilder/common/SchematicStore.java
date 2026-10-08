package com.maidbuilder.common;

import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.schematic.SchematicReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Locates and loads schematics ({@code .litematic} or vanilla structure {@code .nbt}; the format is
 * told from the contents).
 * <ul>
 *   <li>User files: {@code <game dir>/schematics/} - the same folder Litematica uses, so on
 *       single player the player's existing files are available directly.</li>
 *   <li>Job copies: {@code <world>/maidbuilder/schematics/<sha1>.litematic} (whatever the actual
 *       format), so a job keeps working even if the user file is edited or deleted.</li>
 * </ul>
 */
public final class SchematicStore {
    private static final LevelResource STORE = new LevelResource("maidbuilder");
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final String EXTENSION = ".litematic";

    private record CacheKey(Path path, long modified, long size) {
    }

    private static final Map<CacheKey, Schematic> CACHE = new ConcurrentHashMap<>();

    private SchematicStore() {
    }

    public static Path userDir() {
        return FMLPaths.GAMEDIR.get().resolve("schematics");
    }

    public static Path storeDir(MinecraftServer server) {
        return server.getWorldPath(STORE).resolve("schematics");
    }

    /** Schematic files under the user folder, as '/'-separated relative names. */
    public static List<String> listUserFiles() {
        Path dir = userDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.walk(dir, 8)) {
            return files.filter(p -> Files.isRegularFile(p) && SchematicReader.isSchematicFile(p.getFileName().toString()))
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Longest file name (without extension) a captured blueprint may get. */
    public static final int MAX_NAME_LENGTH = 64;

    /**
     * Turns user input into a plain file name for a new blueprint (no folders, no characters
     * Windows rejects, no leading dot); null if nothing usable is left.
     */
    @javax.annotation.Nullable
    public static String sanitizeFileName(String input) {
        String name = input.strip();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(EXTENSION)) name = name.substring(0, name.length() - EXTENSION.length());
        StringBuilder sb = new StringBuilder();
        name.codePoints().forEach(cp -> sb.appendCodePoint(cp < 32 || "<>:\"/\\|?*".indexOf(cp) >= 0 ? '_' : cp));
        name = sb.toString().strip();
        while (name.startsWith(".")) name = name.substring(1);
        while (name.endsWith(".") || name.endsWith(" ")) name = name.substring(0, name.length() - 1);
        if (name.length() > MAX_NAME_LENGTH) name = name.substring(0, MAX_NAME_LENGTH);
        return name.isEmpty() ? null : name;
    }

    /** Where a new blueprint with this (sanitized) name goes in the user folder. */
    public static Path newUserFile(String sanitizedName) {
        return userDir().resolve(sanitizedName + EXTENSION);
    }

    /** Resolves a user-supplied relative name, refusing anything outside the schematics folder. */
    public static Path resolveUserFile(String name) throws IOException {
        Path dir = userDir().toAbsolutePath().normalize();
        // Without an extension, take the first supported format that exists ("house" -> house.litematic or house.nbt).
        List<String> candidates = SchematicReader.isSchematicFile(name) ? List.of(name)
                : SchematicReader.EXTENSIONS.stream().map(ext -> name + ext).toList();
        for (String fileName : candidates) {
            Path file = dir.resolve(fileName).normalize();
            if (!file.startsWith(dir)) throw new IOException("Path escapes the schematics folder: " + name);
            if (Files.isRegularFile(file)) return file;
        }
        throw new IOException("No such schematic: " + candidates.get(0));
    }

    public static Schematic load(Path file) throws IOException {
        CacheKey key = new CacheKey(file.toAbsolutePath().normalize(), Files.getLastModifiedTime(file).toMillis(), Files.size(file));
        Schematic cached = CACHE.get(key);
        if (cached != null) return cached;
        Schematic schematic = reader().read(file);
        CACHE.put(key, schematic);
        return schematic;
    }

    /** Copies a user file into the world store and returns its SHA-1 id. */
    public static String storeCopy(MinecraftServer server, Path file) throws IOException {
        String hash = sha1(file);
        Path target = storeDir(server).resolve(hash + EXTENSION);
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(hash + ".tmp");
            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return hash;
    }

    /** Stores raw schematic bytes (e.g. an upload) after validating them; returns the SHA-1 id. */
    public static String storeBytes(MinecraftServer server, byte[] bytes) throws IOException {
        reader().read(new java.io.ByteArrayInputStream(bytes));
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Path target = storeDir(server).resolve(hash + EXTENSION);
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(hash + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return hash;
    }

    public static boolean hasStored(MinecraftServer server, String hash) {
        return SHA1.matcher(hash).matches() && Files.isRegularFile(storeDir(server).resolve(hash + EXTENSION));
    }

    public static Schematic loadStored(MinecraftServer server, String hash) throws IOException {
        if (!SHA1.matcher(hash).matches()) throw new IOException("Invalid schematic id " + hash);
        return load(storeDir(server).resolve(hash + EXTENSION));
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static SchematicReader reader() {
        return new SchematicReader(MaidBuilderConfig.MAX_SCHEMATIC_VOLUME.get(), 512L * 1024 * 1024);
    }

    public static String sha1(Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-1"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
