package com.maidbuilder.common.wand;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.MaterialContainers;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.init.ModDataComponents;
import com.maidbuilder.network.Payloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every two seconds, sends the progress of the linked job (and what is missing even counting the
 * material containers) to each player holding a linked Blueprint Wand, for the HUD.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class JobStatusSync {
    private static final int INTERVAL_TICKS = 40;
    private static final int MAX_MISSING_ENTRIES = 12;

    private JobStatusSync() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % INTERVAL_TICKS != 0) return;
        Map<UUID, Payloads.JobStatus> computed = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ItemStack wand = WandActions.heldWand(player);
            UUID jobId = wand == null ? null : wand.get(ModDataComponents.BUILD_JOB.get());
            if (jobId == null) continue;
            Payloads.JobStatus status = computed.computeIfAbsent(jobId,
                    id -> BuildJobManager.loaded(server, id).map(job -> status(server, job)).orElse(null));
            if (status != null) PacketDistributor.sendToPlayer(player, status);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UploadManager.forget(event.getEntity().getUUID());
        WandActions.forget(event.getEntity().getUUID());
        MaterialReports.forget(event.getEntity().getUUID());
        com.maidbuilder.common.capture.CaptureActions.forget(event.getEntity().getUUID());
    }

    public static Payloads.JobStatus status(MinecraftServer server, BuildJob job) {
        Map<Item, Integer> available = new HashMap<>();
        ServerLevel level = server.getLevel(job.dimension());
        if (level != null) {
            for (IItemHandler handler : MaterialContainers.distinct(level, job.materialSources()).values()) {
                MaterialContainers.count(handler, available);
            }
        }
        List<Payloads.ItemCount> missing = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : job.remainingMaterials().entrySet()) {
            int short_ = e.getValue() - available.getOrDefault(e.getKey(), 0);
            if (short_ > 0) missing.add(new Payloads.ItemCount(e.getKey(), short_));
        }
        missing.sort((a, b) -> Integer.compare(b.count(), a.count()));
        if (missing.size() > MAX_MISSING_ENTRIES) missing = new ArrayList<>(missing.subList(0, MAX_MISSING_ENTRIES));
        return new Payloads.JobStatus(job.id(), job.schematicName(), job.count(BuildJob.DONE), job.size(),
                job.count(BuildJob.NEEDS_PLAYER), job.count(BuildJob.FAILED), job.materialSources().size(),
                job.activeWorkers(server.overworld().getGameTime()), missing);
    }
}
