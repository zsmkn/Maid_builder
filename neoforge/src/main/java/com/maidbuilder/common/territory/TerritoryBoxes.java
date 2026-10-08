package com.maidbuilder.common.territory;

import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The space taken by a territory's buildings and queued new buildings, which a new template must not overlap. */
public final class TerritoryBoxes {
    private TerritoryBoxes() {
    }

    public static List<TerritoryPayloads.Box> of(MinecraftServer server, Territory territory) {
        List<TerritoryPayloads.Box> boxes = new ArrayList<>();
        for (RegisteredBuilding b : territory.buildings()) boxes.add(new TerritoryPayloads.Box(b.min(), b.max(), false));
        for (UUID id : territory.jobQueue()) {
            // repairs stay within their building; plain schematics are not buildings
            BuildJob stored = BuildJobManager.data(server).get(id);
            if (stored == null || stored.templateId() == null || stored.buildingId() != null || stored.isCancelled()) continue;
            BuildJob job = BuildJobManager.loaded(server, id).orElse(null);
            if (job == null) continue;
            boxes.add(new TerritoryPayloads.Box(job.minCorner(), job.maxCorner(), true));
        }
        return boxes;
    }

    /** Whether the inclusive box [lo, hi] overlaps any building or planned building of the territory. */
    public static boolean overlaps(MinecraftServer server, Territory territory, BlockPos lo, BlockPos hi) {
        for (TerritoryPayloads.Box box : of(server, territory)) if (box.intersects(lo, hi)) return true;
        return false;
    }
}
