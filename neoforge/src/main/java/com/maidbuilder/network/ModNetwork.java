package com.maidbuilder.network;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.capture.CaptureActions;
import com.maidbuilder.common.capture.QuillActions;
import com.maidbuilder.common.territory.TerritoryReports;
import com.maidbuilder.common.wand.MaterialReports;
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

        registrar.playToServer(Payloads.RequestMaterialReport.TYPE, Payloads.RequestMaterialReport.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) MaterialReports.onRequest(player, msg.job().orElse(null), msg.open());
        });
        registrar.playToServer(Payloads.QuillAdjust.TYPE, Payloads.QuillAdjust.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) QuillActions.adjust(player, msg.grow(), msg.amount());
        });
        registrar.playToServer(Payloads.CaptureRequest.TYPE, Payloads.CaptureRequest.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) CaptureActions.onRequest(player, msg);
        });

        registrar.playToServer(TerritoryPayloads.RequestTerritoryReport.TYPE, TerritoryPayloads.RequestTerritoryReport.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) TerritoryReports.onRequest(player, msg.territory(), msg.open());
        });

        registrar.playToServer(TerritoryPayloads.TerritoryAction.TYPE, TerritoryPayloads.TerritoryAction.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) com.maidbuilder.common.territory.TerritoryActions.handle(player, msg);
        });
        registrar.playToServer(TerritoryPayloads.RequestTemplate.TYPE, TerritoryPayloads.RequestTemplate.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) com.maidbuilder.common.territory.TemplatePlacement.sendStructure(player, msg.id());
        });
        registrar.playToServer(TerritoryPayloads.PlaceTemplate.TYPE, TerritoryPayloads.PlaceTemplate.CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) {
                boolean ok = com.maidbuilder.common.territory.TemplatePlacement.place(player, msg) != null;
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new TerritoryPayloads.PlaceResult(ok));
            }
        });
        registrar.playToClient(TerritoryPayloads.TemplateStructure.TYPE, TerritoryPayloads.TemplateStructure.CODEC,
                (msg, ctx) -> com.maidbuilder.client.territory.BuildMode.onStructure(msg));
        registrar.playToClient(TerritoryPayloads.PlaceResult.TYPE, TerritoryPayloads.PlaceResult.CODEC,
                (msg, ctx) -> com.maidbuilder.client.territory.BuildMode.onPlaceResult(msg));
        registrar.playToClient(TerritoryPayloads.TerritoryList.TYPE, TerritoryPayloads.TerritoryList.CODEC,
                (msg, ctx) -> com.maidbuilder.client.territory.ClientTerritories.set(msg));
        registrar.playToClient(TerritoryPayloads.TerritoryReport.TYPE, TerritoryPayloads.TerritoryReport.CODEC,
                (msg, ctx) -> com.maidbuilder.client.territory.ClientTerritories.onReport(msg));
        registrar.playToClient(TerritoryPayloads.TemplateList.TYPE, TerritoryPayloads.TemplateList.CODEC,
                (msg, ctx) -> com.maidbuilder.client.territory.ClientTerritories.setTemplates(msg));
        registrar.playToClient(Payloads.RequestUpload.TYPE, Payloads.RequestUpload.CODEC,
                (msg, ctx) -> com.maidbuilder.client.ClientPayloadHandler.onRequestUpload(msg));
        registrar.playToClient(Payloads.JobStatus.TYPE, Payloads.JobStatus.CODEC,
                (msg, ctx) -> com.maidbuilder.client.ClientPayloadHandler.onJobStatus(msg));
        registrar.playToClient(Payloads.ClearSettings.TYPE, Payloads.ClearSettings.CODEC,
                (msg, ctx) -> com.maidbuilder.client.ClientPayloadHandler.onClearSettings(msg));
        registrar.playToClient(Payloads.MaterialReport.TYPE, Payloads.MaterialReport.CODEC,
                (msg, ctx) -> com.maidbuilder.client.ClientPayloadHandler.onMaterialReport(msg));
        registrar.playToClient(Payloads.DownloadChunk.TYPE, Payloads.DownloadChunk.CODEC,
                (msg, ctx) -> com.maidbuilder.client.ClientDownloads.onChunk(msg));
    }
}
