package com.maidbuilder.common.territory;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.schematic.SchematicReader;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Building templates from data packs: definitions in {@code maidbuilder/templates/*.json}, their
 * schematics in {@code maidbuilder/structures/<path>.litematic} or {@code .nbt}. A template whose
 * schematic is missing or unreadable is left out (logged).
 */
public final class TemplateRegistry extends SimplePreparableReloadListener<Map<ResourceLocation, TemplateRegistry.Entry>> {
    private static final FileToIdConverter DEFINITIONS = FileToIdConverter.json("maidbuilder/templates");
    /** Templates are small buildings; refuse anything absurd. */
    private static final long MAX_VOLUME = 256L * 256 * 256;
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    /** Largest schematic file sent to a client in one payload. */
    public static final int MAX_STRUCTURE_BYTES = 1000 * 1024;

    /**
     * A loaded template.
     *
     * @param bytes the schematic file as stored for build jobs and sent to clients
     */
    public record Entry(TemplateDef def, byte[] bytes, String sha1, Schematic schematic) {
        public IntPos size() {
            IntPos min = schematic.minCorner(), max = schematic.maxCorner();
            return new IntPos(max.x() - min.x() + 1, max.y() - min.y() + 1, max.z() - min.z() + 1);
        }
    }

    private static volatile Map<ResourceLocation, Entry> entries = Map.of();
    private static final Map<ResourceLocation, Entry> TEST_ENTRIES = new LinkedHashMap<>();

    @Override
    protected Map<ResourceLocation, Entry> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, Entry> result = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Resource> file : DEFINITIONS.listMatchingResources(manager).entrySet()) {
            ResourceLocation id = DEFINITIONS.fileToId(file.getKey());
            try (Reader reader = file.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                Optional<TemplateDef.Json> parsed = TemplateDef.JSON.parse(JsonOps.INSTANCE, json)
                        .resultOrPartial(error -> MaidBuilder.LOGGER.error("Bad template {}: {}", id, error));
                if (parsed.isEmpty()) continue;
                TemplateDef def = TemplateDef.of(id, parsed.get());
                byte[] bytes = readStructure(manager, def.structure());
                if (bytes == null) {
                    MaidBuilder.LOGGER.error("Template {}: structure {} not found", id, def.structure());
                    continue;
                }
                if (bytes.length > MAX_STRUCTURE_BYTES) {
                    MaidBuilder.LOGGER.error("Template {}: structure file is larger than {} bytes", id, MAX_STRUCTURE_BYTES);
                    continue;
                }
                Schematic schematic = new SchematicReader(MAX_VOLUME, MAX_NBT_BYTES).read(new ByteArrayInputStream(bytes));
                result.put(id, new Entry(def, bytes, sha1(bytes), schematic));
            } catch (IOException | RuntimeException e) {
                MaidBuilder.LOGGER.error("Cannot load template {}", id, e);
            }
        }
        return result;
    }

    @Nullable
    private static byte[] readStructure(ResourceManager manager, ResourceLocation structure) throws IOException {
        for (String ext : SchematicReader.EXTENSIONS) {
            ResourceLocation file = structure.withPath("maidbuilder/structures/" + structure.getPath() + ext);
            Optional<Resource> resource = manager.getResource(file);
            if (resource.isPresent()) {
                try (InputStream in = resource.get().open()) {
                    return in.readAllBytes();
                }
            }
        }
        return null;
    }

    @Override
    protected void apply(Map<ResourceLocation, Entry> prepared, ResourceManager manager, ProfilerFiller profiler) {
        entries = Collections.unmodifiableMap(prepared);
        MaidBuilder.LOGGER.info("Loaded {} building templates", prepared.size());
    }

    @Nullable
    public static Entry entry(ResourceLocation id) {
        Entry e = entries.get(id);
        if (e == null) {
            synchronized (TEST_ENTRIES) {
                e = TEST_ENTRIES.get(id);
            }
        }
        return e;
    }

    @Nullable
    public static Entry entry(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : entry(rl);
    }

    @Nullable
    public static TemplateDef get(String id) {
        Entry e = entry(id);
        return e == null ? null : e.def();
    }

    /** All templates, data pack ones first. */
    public static Collection<Entry> all() {
        Map<ResourceLocation, Entry> all = new LinkedHashMap<>(entries);
        synchronized (TEST_ENTRIES) {
            TEST_ENTRIES.forEach(all::putIfAbsent);
        }
        return all.values();
    }

    /** Adds a template built in code (game tests); kept across data pack reloads. */
    public static Entry registerForTest(TemplateDef def, Schematic schematic) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LitematicWriter.write(schematic, out);
            byte[] bytes = out.toByteArray();
            Entry entry = new Entry(def, bytes, sha1(bytes), schematic);
            synchronized (TEST_ENTRIES) {
                TEST_ENTRIES.put(def.id(), entry);
            }
            return entry;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
