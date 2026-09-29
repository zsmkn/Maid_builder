package com.maidbuilder.common.job;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.SchematicStore;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/** Entry point for looking up jobs from commands and maid behaviours. */
public final class BuildJobManager {
    private BuildJobManager() {
    }

    public static BuildJobData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(BuildJobData.FACTORY, BuildJobData.NAME);
    }

    /** Finds a job and makes sure its plan is loaded; empty if missing or its schematic is unreadable. */
    public static Optional<BuildJob> loaded(MinecraftServer server, UUID id) {
        BuildJob job = data(server).get(id);
        if (job == null) return Optional.empty();
        try {
            job.ensureLoaded(server);
            return Optional.of(job);
        } catch (IOException e) {
            MaidBuilder.LOGGER.error("Cannot load schematic of build job {}", job.shortId(), e);
            return Optional.empty();
        }
    }

    /** Finds a job by full UUID or by a unique prefix of it. */
    public static Optional<BuildJob> byIdPrefix(MinecraftServer server, String prefix) {
        String p = prefix.toLowerCase(java.util.Locale.ROOT);
        BuildJob found = null;
        for (BuildJob job : data(server).all()) {
            if (job.id().toString().startsWith(p)) {
                if (found != null) return Optional.empty();
                found = job;
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * Cancels a job: its maids stop building. Without scaffolding up it is deleted right away
     * (returns true); otherwise the maids take the scaffolding down first and the last one deletes it.
     */
    public static boolean cancel(MinecraftServer server, BuildJob job) {
        job.cancel();
        if (job.scaffolds().isEmpty()) {
            data(server).remove(job.id());
            return true;
        }
        return false;
    }

    public static void clearRuntimeCaches() {
        SchematicStore.clearCache();
    }
}
