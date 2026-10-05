package com.maidbuilder.core.schematic;

import com.maidbuilder.core.nbt.NbtCompound;
import com.maidbuilder.core.nbt.NbtIo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Reads any supported schematic file, telling the format from its contents rather than its name:
 * Litematica {@code .litematic} or vanilla structure {@code .nbt}.
 */
public final class SchematicReader {
    /** File name extensions of the supported formats, lower case. */
    public static final List<String> EXTENSIONS = List.of(".litematic", ".nbt");

    private final long maxVolume;
    private final long maxNbtBytes;

    /**
     * @param maxVolume   upper bound on the total volume (guards untrusted uploads)
     * @param maxNbtBytes upper bound on the decoded NBT size
     */
    public SchematicReader(long maxVolume, long maxNbtBytes) {
        this.maxVolume = maxVolume;
        this.maxNbtBytes = maxNbtBytes;
    }

    public static boolean isSchematicFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    public Schematic read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        }
    }

    public Schematic read(InputStream in) throws IOException {
        return read(NbtIo.read(in, maxNbtBytes));
    }

    public Schematic read(NbtCompound root) throws SchematicFormatException {
        if (StructureReader.isStructure(root)) return new StructureReader(maxVolume).read(root);
        if (root.contains("Version") || root.contains("Regions")) return new LitematicReader(maxVolume, maxNbtBytes).read(root);
        throw new SchematicFormatException("Neither a litematic nor a structure file");
    }
}
