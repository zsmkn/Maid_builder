package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps every client's copy of the territory list (borders, build mode checks) and the template
 * catalog up to date: sent on login, after a data pack reload, and at the end of any tick in
 * which a territory changed.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class TerritorySync {
    private static int sentVersion = Integer.MIN_VALUE;
    private static int sentRadius = -1;

    private TerritorySync() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        TerritoryData data = TerritoryManager.data(server);
        // a level table or config change can change radii without touching the data
        int radius = TerritoryLevels.maxRadius() * 1000 + TerritoryLevels.table().maxLevel();
        if (data.version() == sentVersion && radius == sentRadius) return;
        sentVersion = data.version();
        sentRadius = radius;
        PacketDistributor.sendToAllPlayers(list(server));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PacketDistributor.sendToPlayer(player, list(player.server));
    }

    /** Also fires for each player joining (after the login event), so the catalog arrives with the data packs. */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        TerritoryPayloads.TemplateList templates = templates();
        event.getRelevantPlayers().forEach(p -> PacketDistributor.sendToPlayer(p, templates));
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        sentVersion = Integer.MIN_VALUE;
        sentRadius = -1;
    }

    public static TerritoryPayloads.TerritoryList list(MinecraftServer server) {
        List<TerritoryPayloads.TerritoryInfo> list = new ArrayList<>();
        for (Territory t : TerritoryManager.data(server).all()) {
            list.add(new TerritoryPayloads.TerritoryInfo(t.id(), t.owner(), t.ownerName(), t.dimension().location(), t.flagPos(),
                    t.radius(), t.level(), t.isActive(), TerritoryBoxes.of(server, t)));
        }
        return new TerritoryPayloads.TerritoryList(list);
    }

    public static TerritoryPayloads.TemplateList templates() {
        List<TerritoryPayloads.TemplateInfo> list = new ArrayList<>();
        for (TemplateRegistry.Entry e : TemplateRegistry.all()) {
            var size = e.size();
            list.add(new TerritoryPayloads.TemplateInfo(e.def(), size.x(), size.y(), size.z(), (int) e.schematic().countNonAir()));
        }
        return new TerritoryPayloads.TemplateList(list);
    }
}
