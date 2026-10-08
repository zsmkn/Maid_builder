package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.Convert;
import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.territory.TerritoryRules;
import com.maidbuilder.core.transform.Placement;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.UUID;

/** Build mode on the server: sending template schematics and turning a confirmed placement into a queued job. */
public final class TemplatePlacement {
    private TemplatePlacement() {
    }

    public static void sendStructure(ServerPlayer player, ResourceLocation id) {
        TemplateRegistry.Entry entry = TemplateRegistry.entry(id);
        if (entry != null) PacketDistributor.sendToPlayer(player, new TerritoryPayloads.TemplateStructure(id, entry.sha1(), entry.bytes()));
    }

    /** The world box (inclusive) a template occupies when placed so: {@code [min, max]}. */
    public static BlockPos[] bounds(Schematic schematic, BlockPos origin, Rotation rotation, Mirror mirror) {
        Placement placement = Convert.placement(origin, rotation, mirror);
        BlockPos a = Convert.toBlockPos(placement.toWorld(schematic.minCorner()));
        BlockPos b = Convert.toBlockPos(placement.toWorld(schematic.maxCorner()));
        return new BlockPos[]{BlockPos.min(a, b), BlockPos.max(a, b)};
    }

    static boolean contains(BlockPos[] box, BlockPos pos) {
        return pos.getX() >= box[0].getX() && pos.getX() <= box[1].getX() && pos.getY() >= box[0].getY() && pos.getY() <= box[1].getY()
                && pos.getZ() >= box[0].getZ() && pos.getZ() <= box[1].getZ();
    }

    /** Why the placement is not allowed, or null if it is. */
    @Nullable
    public static Component validate(ServerLevel level, Territory territory, TemplateRegistry.Entry entry, BlockPos origin,
                                     Rotation rotation, Mirror mirror) {
        if (!territory.isActive()) return Component.translatable("message.maidbuilder.build.inactive");
        if (!territory.dimension().equals(level.dimension())) return Component.translatable("message.maidbuilder.build.outside");
        if (territory.level() < entry.def().requiredLevel()) {
            return Component.translatable("message.maidbuilder.build.locked", entry.def().requiredLevel());
        }
        BlockPos[] box = bounds(entry.schematic(), origin, rotation, mirror);
        if (!TerritoryRules.insideBox(territory.flagPos().getX(), territory.flagPos().getZ(), territory.radius(),
                box[0].getX(), box[0].getZ(), box[1].getX(), box[1].getZ())) {
            return Component.translatable("message.maidbuilder.build.outside");
        }
        if (box[0].getY() < level.getMinBuildHeight() || box[1].getY() >= level.getMaxBuildHeight()) {
            return Component.translatable("message.maidbuilder.build.height");
        }
        if (contains(box, territory.flagPos())) return Component.translatable("message.maidbuilder.build.flag");
        if (TerritoryBoxes.overlaps(level.getServer(), territory, box[0], box[1])) {
            return Component.translatable("message.maidbuilder.build.overlap");
        }
        return null;
    }

    /** Handles a build mode confirm; returns the queued job or null (the player is told why). */
    @Nullable
    public static BuildJob place(ServerPlayer player, TerritoryPayloads.PlaceTemplate msg) {
        Territory territory = TerritoryManager.data(player.server).get(msg.territory());
        if (territory == null || !TerritoryManager.mayManage(player, territory)) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.build.not_yours"), false);
            return null;
        }
        TemplateRegistry.Entry entry = TemplateRegistry.entry(msg.template());
        if (entry == null) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.build.unknown", msg.template().toString()), false);
            return null;
        }
        Component error = validate(player.serverLevel(), territory, entry, msg.origin(), msg.rotation(), msg.mirror());
        if (error != null) {
            player.displayClientMessage(error, false);
            return null;
        }
        try {
            BuildJob job = createJob(player.server, player.getUUID(), player.getGameProfile().getName(), territory, entry,
                    msg.origin(), msg.rotation(), msg.mirror(), null);
            player.displayClientMessage(Component.translatable("message.maidbuilder.build.queued",
                    Component.translatable(entry.def().nameKey()), territory.jobQueue().size()), false);
            return job;
        } catch (IOException e) {
            MaidBuilder.LOGGER.error("Cannot create a job for template {}", msg.template(), e);
            player.displayClientMessage(Component.translatable("command.maidbuilder.file_error", msg.template().toString(), e.getMessage()), false);
            return null;
        }
    }

    /**
     * Creates a job building the template and puts it at the end of the territory's queue.
     *
     * @param building the building it repairs, or null for a new building
     */
    public static BuildJob createJob(MinecraftServer server, UUID owner, String ownerName, Territory territory, TemplateRegistry.Entry entry,
                                     BlockPos origin, Rotation rotation, Mirror mirror, @Nullable UUID building) throws IOException {
        String hash = SchematicStore.storeBytes(server, entry.bytes());
        BuildJob job = new BuildJob(UUID.randomUUID(), owner, ownerName, entry.def().id().toString(), hash, territory.dimension(),
                origin, rotation, mirror, MaidBuilderConfig.PLACE_FLUIDS.get(), null);
        job.useDefaultClearing();
        if (building != null) job.limitClearingToReplace(); // a repair must not clear what was added inside
        job.ensureLoaded(server);
        BuildJobManager.data(server).add(job);
        job.setTerritory(territory.id(), entry.def().id().toString(), building);
        territory.enqueue(job.id());
        return job;
    }
}
