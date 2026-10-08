package com.maidbuilder.network;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.capture.CaptureActions;
import com.maidbuilder.common.capture.QuillActions;
import com.maidbuilder.common.wand.MaterialReports;
import com.maidbuilder.common.wand.UploadManager;
import com.maidbuilder.common.wand.WandActions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Payload registration. Handlers run on the main thread. Client handlers go through
 * {@link DistExecutor} so that {@code com.maidbuilder.client} classes are only loaded on the
 * physical client.
 */
public final class ModNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            MaidBuilder.id("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static int nextId;

    private ModNetwork() {
    }

    public static void register() {
        toServer(Payloads.SelectSchematic.class, Payloads.SelectSchematic::encode, Payloads.SelectSchematic::decode,
                WandActions::select);
        toServer(Payloads.AdjustPlacement.class, Payloads.AdjustPlacement::encode, Payloads.AdjustPlacement::decode,
                (player, msg) -> WandActions.adjust(player, msg.adjustment()));
        toServer(Payloads.UploadChunk.class, Payloads.UploadChunk::encode, Payloads.UploadChunk::decode,
                UploadManager::receive);
        toServer(Payloads.RequestMaterialReport.class, Payloads.RequestMaterialReport::encode, Payloads.RequestMaterialReport::decode,
                (player, msg) -> MaterialReports.onRequest(player, msg.open()));
        toServer(Payloads.QuillAdjust.class, Payloads.QuillAdjust::encode, Payloads.QuillAdjust::decode,
                (player, msg) -> QuillActions.adjust(player, msg.grow(), msg.amount()));
        toServer(Payloads.CaptureRequest.class, Payloads.CaptureRequest::encode, Payloads.CaptureRequest::decode,
                CaptureActions::onRequest);

        toClient(Payloads.RequestUpload.class, Payloads.RequestUpload::encode, Payloads.RequestUpload::decode,
                msg -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.maidbuilder.client.ClientPayloadHandler.onRequestUpload(msg)));
        toClient(Payloads.JobStatus.class, Payloads.JobStatus::encode, Payloads.JobStatus::decode,
                msg -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.maidbuilder.client.ClientPayloadHandler.onJobStatus(msg)));
        toClient(Payloads.MaterialReport.class, Payloads.MaterialReport::encode, Payloads.MaterialReport::decode,
                msg -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.maidbuilder.client.ClientPayloadHandler.onMaterialReport(msg)));
        toClient(Payloads.DownloadChunk.class, Payloads.DownloadChunk::encode, Payloads.DownloadChunk::decode,
                msg -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.maidbuilder.client.ClientDownloads.onChunk(msg)));
    }

    private static <T> void toServer(Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
                                     BiConsumer<ServerPlayer, T> handler) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((T msg, Supplier<NetworkEvent.Context> ctx) -> {
                    ServerPlayer player = ctx.get().getSender();
                    if (player != null) handler.accept(player, msg);
                })
                .add();
    }

    private static <T> void toClient(Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder,
                                     Consumer<T> handler) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((T msg, Supplier<NetworkEvent.Context> ctx) -> handler.accept(msg))
                .add();
    }

    public static void sendToPlayer(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    public static void sendToServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }
}
