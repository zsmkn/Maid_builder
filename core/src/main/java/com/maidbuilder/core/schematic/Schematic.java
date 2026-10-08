package com.maidbuilder.core.schematic;

import com.maidbuilder.core.math.IntPos;

import java.util.List;

/**
 * A parsed schematic made of one or more regions. Coordinates are relative to the schematic origin.
 *
 * @param formatVersion        litematic {@code Version}
 * @param formatSubVersion     litematic {@code SubVersion} (0 if absent)
 * @param minecraftDataVersion data version the palette was saved with; states may need DataFixer upgrades
 */
public record Schematic(int formatVersion, int formatSubVersion, int minecraftDataVersion,
                        SchematicMetadata metadata, List<SchematicRegion> regions) {
    public Schematic {
        regions = List.copyOf(regions);
    }

    public IntPos minCorner() {
        IntPos min = null;
        for (SchematicRegion r : regions) min = min == null ? r.minCorner() : IntPos.min(min, r.minCorner());
        return min == null ? IntPos.ZERO : min;
    }

    public IntPos maxCorner() {
        IntPos max = null;
        for (SchematicRegion r : regions) max = max == null ? r.maxCorner() : IntPos.max(max, r.maxCorner());
        return max == null ? IntPos.ZERO : max;
    }

    public long countNonAir() {
        long total = 0;
        for (SchematicRegion r : regions) total += r.countNonAir();
        return total;
    }

    /** Visits non-air blocks of all regions. Later regions overwrite earlier ones when they overlap. */
    public void forEachBlock(SchematicRegion.BlockVisitor visitor) {
        for (SchematicRegion r : regions) r.forEachBlock(visitor);
    }

    /** Visits the cells of all regions that are explicitly air (structure voids are left out). */
    public void forEachAir(SchematicRegion.AirVisitor visitor) {
        for (SchematicRegion r : regions) r.forEachAir(visitor);
    }
}
