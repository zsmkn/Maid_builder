package com.maidbuilder.common.wand;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.job.ClearMode;
import com.maidbuilder.network.Payloads;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import com.maidbuilder.network.ModNetwork;

/**
 * Tells clients how far new jobs clear the schematic's air, so the wand preview can outline the
 * blocks the maids would break. Sent on login and whenever the server setting changes.
 */
@Mod.EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class ClearSettingsSync {
    private static int sent = -1;

    private ClearSettingsSync() {
    }

    /** Clearing radius of jobs created now; 0 when they leave the schematic's air alone. */
    public static int defaultAirRadius() {
        return MaidBuilderConfig.CLEAR_MODE.get() == ClearMode.ALL ? MaidBuilderConfig.CLEAR_RADIUS.get() : 0;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int radius = defaultAirRadius();
        if (radius == sent) return;
        sent = radius;
        ModNetwork.sendToAll(new Payloads.ClearSettings(radius));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ModNetwork.sendToPlayer(player, new Payloads.ClearSettings(defaultAirRadius()));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        sent = -1;
    }
}
