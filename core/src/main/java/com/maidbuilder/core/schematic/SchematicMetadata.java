package com.maidbuilder.core.schematic;

import com.maidbuilder.core.math.IntPos;

/** The {@code Metadata} compound of a litematic file. Values are informational only. */
public record SchematicMetadata(String name, String author, String description, IntPos enclosingSize,
                                int totalBlocks, int totalVolume, int regionCount,
                                long timeCreated, long timeModified) {
}
