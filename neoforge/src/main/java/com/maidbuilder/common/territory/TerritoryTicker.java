package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.UUID;

/**
 * Once a second: retires finished jobs from the territory queues (a finished template job becomes
 * a registered building).
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class TerritoryTicker {
    private static final int INTERVAL = 20;
    /** A resident that has not reported in for this long (unloaded, or gone) no longer counts. */
    static final long RESIDENT_TIMEOUT = 20 * 60 * 10;

    private TerritoryTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % INTERVAL != 0) return;
        long now = server.overworld().getGameTime();
        for (Territory territory : List.copyOf(TerritoryManager.data(server).all())) {
            updateQueue(server, territory);
            territory.expireResidents(now, RESIDENT_TIMEOUT);
        }
    }

    /** Drops deleted jobs from the queue and completes finished ones whose scaffolding is down. */
    public static void updateQueue(MinecraftServer server, Territory territory) {
        for (UUID id : List.copyOf(territory.jobQueue())) {
            BuildJob job = BuildJobManager.data(server).get(id);
            if (job == null) {
                territory.dequeue(id);
            } else if (job.isComplete() && !job.isCancelled() && job.scaffolds().isEmpty()) {
                completeJob(server, territory, job);
            }
        }
    }

    static void completeJob(MinecraftServer server, Territory territory, BuildJob job) {
        territory.dequeue(job.id());
        // a blueprint wand job stays (its wand still links to it); a template job is done for good
        if (job.templateId() == null) return;
        BuildJobManager.data(server).remove(job.id());
        if (job.buildingId() != null) {
            RegisteredBuilding repaired = territory.building(job.buildingId());
            if (repaired != null && !BuildingIntegrity.checkNow(server, territory, repaired)) BuildingIntegrity.recheckSoon(repaired.id());
            return;
        }
        RegisteredBuilding building = register(server, territory, job);
        BuildingIntegrity.checkNow(server, territory, building);
        ServerPlayer owner = server.getPlayerList().getPlayer(territory.owner());
        if (owner != null) {
            owner.sendSystemMessage(Component.translatable("message.maidbuilder.territory.building_done",
                    Component.translatable(nameKey(job.templateId())), territory.prosperity()));
        }
    }

    /** Registers the finished template job as a building of the territory. */
    static RegisteredBuilding register(MinecraftServer server, Territory territory, BuildJob job) {
        BlockPos min = job.minCorner(), max = job.maxCorner();
        TemplateRegistry.Entry entry = TemplateRegistry.entry(job.templateId());
        if (entry != null) {
            BlockPos[] box = TemplatePlacement.bounds(entry.schematic(), job.origin(), job.rotation(), job.mirror());
            min = box[0];
            max = box[1];
        }
        RegisteredBuilding building = new RegisteredBuilding(UUID.randomUUID(), job.templateId(), job.schematicHash(), job.origin(),
                job.rotation(), job.mirror(), min, max);
        territory.addBuilding(building);
        return building;
    }

    static String nameKey(String templateId) {
        TemplateDef def = TemplateRegistry.get(templateId);
        return def != null ? def.nameKey() : templateId;
    }
}
