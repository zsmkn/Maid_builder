package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import com.maidbuilder.network.ModNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps every client's copy of the territory list (borders, build mode checks) and the template
 * catalog up to date: sent on login, after a data pack reload, and at the end of any tick in
 * which a territory changed.
 */
@Mod.EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class TerritorySync {
    private static int sentVersion = Integer.MIN_VALUE;
    private static int sentRadius = -1;

    private TerritorySync() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        TerritoryData data = TerritoryManager.data(server);
        // a level table or config change can change radii without touching the data
        int radius = TerritoryLevels.maxRadius() * 1000 + TerritoryLevels.table().maxLevel();
        if (data.version() == sentVersion && radius == sentRadius) return;
        sentVersion = data.version();
        sentRadius = radius;
        ModNetwork.sendToAll(list(server));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) ModNetwork.sendToPlayer(player, list(player.server));
    }

    /** Also fires for each player joining (after the login event), so the catalog arrives with the data packs. */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        TerritoryPayloads.TemplateList templates = templates();
        // 1.20.1: no player means everyone (a /reload)
        if (event.getPlayer() != null) ModNetwork.sendToPlayer(event.getPlayer(), templates);
        else event.getPlayerList().getPlayers().forEach(p -> ModNetwork.sendToPlayer(p, templates));
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
