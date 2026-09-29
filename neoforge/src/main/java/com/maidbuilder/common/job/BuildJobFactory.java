package com.maidbuilder.common.job;

import com.maidbuilder.MaidBuilderConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.IOException;
import java.util.UUID;

/** Creates build jobs from a schematic already in the world store. */
public final class BuildJobFactory {
    private BuildJobFactory() {
    }

    public static BuildJob create(ServerPlayer owner, String schematicName, String hash, BlockPos origin,
                                  Rotation rotation, Mirror mirror) throws IOException {
        BuildJob job = new BuildJob(UUID.randomUUID(), owner.getUUID(), owner.getGameProfile().getName(), schematicName,
                hash, owner.serverLevel().dimension(), origin, rotation, mirror, MaidBuilderConfig.PLACE_FLUIDS.get(), null);
        job.ensureLoaded(owner.server);
        BuildJobManager.data(owner.server).add(job);
        return job;
    }
}
