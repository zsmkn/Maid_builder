package com.maidbuilder.common.wand;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.job.ClearMode;
import com.maidbuilder.network.Payloads;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Tells clients how far new jobs clear the schematic's air, so the wand preview can outline the
 * blocks the maids would break. Sent on login and whenever the server setting changes.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class ClearSettingsSync {
    private static int sent = -1;

    private ClearSettingsSync() {
    }

    /** Clearing radius of jobs created now; 0 when they leave the schematic's air alone. */
    public static int defaultAirRadius() {
        return MaidBuilderConfig.CLEAR_MODE.get() == ClearMode.ALL ? MaidBuilderConfig.CLEAR_RADIUS.get() : 0;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int radius = defaultAirRadius();
        if (radius == sent) return;
        sent = radius;
        PacketDistributor.sendToAllPlayers(new Payloads.ClearSettings(radius));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, new Payloads.ClearSettings(defaultAirRadius()));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        sent = -1;
    }
}
