package com.maidbuilder.network;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.capture.CaptureActions;
import com.maidbuilder.common.wand.UploadManager;
import com.maidbuilder.common.wand.WandActions;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Payload registration. Handlers run on the main thread (NeoForge default). Client handlers are
 * lambdas so that {@code com.maidbuilder.client} classes are only loaded on the physical client.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ModNetwork {
    private ModNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(Payloads.SelectSchematic.TYPE, Payloads.SelectSchematic.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) WandActions.select(player, msg);
        });
        registrar.playToServer(Payloads.AdjustPlacement.TYPE, Payloads.AdjustPlacement.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) WandActions.adjust(player, msg.adjustment());
        });
        registrar.playToServer(Payloads.UploadChunk.TYPE, Payloads.UploadChunk.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) UploadManager.receive(player, msg);
        });

        registrar.playToServer(Payloads.CaptureRequest.TYPE, Payloads.CaptureRequest.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) CaptureActions.onRequest(player, msg);
        });

        registrar.playToClient(Payloads.RequestUpload.TYPE, Payloads.RequestUpload.CODEC,
                (msg, ctx) -> com.maidbuilder.client.ClientPayloadHandler.onRequestUpload(msg));
        registrar.playToClient(Payloads.JobStatus.TYPE, Payloads.JobStatus.CODEC,
                (msg, ctx) -> com.maidbuilder.client.ClientPayloadHandler.onJobStatus(msg));
        registrar.playToClient(Payloads.DownloadChunk.TYPE, Payloads.DownloadChunk.CODEC,
                (msg, ctx) -> com.maidbuilder.client.ClientDownloads.onChunk(msg));
    }
}
