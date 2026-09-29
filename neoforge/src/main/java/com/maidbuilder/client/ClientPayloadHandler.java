package com.maidbuilder.client;

import com.maidbuilder.network.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Arrays;

/** Handlers for clientbound payloads (main thread). */
public final class ClientPayloadHandler {
    @Nullable
    private static Payloads.JobStatus lastStatus;
    private static long lastStatusTime;

    private ClientPayloadHandler() {
    }

    /** The server lacks the schematic being confirmed: send it in chunks if we have that exact file. */
    public static void onRequestUpload(Payloads.RequestUpload request) {
        ClientSchematics.load(request.file()).whenComplete((loaded, error) -> Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (loaded == null || !loaded.sha1().equals(request.sha1())) {
                if (player != null) player.displayClientMessage(Component.translatable("message.maidbuilder.upload.missing", request.file()), false);
                return;
            }
            byte[] bytes = loaded.bytes();
            int size = Payloads.UPLOAD_CHUNK_BYTES;
            int total = Math.max(1, (bytes.length + size - 1) / size);
            for (int i = 0; i < total; i++) {
                byte[] part = Arrays.copyOfRange(bytes, i * size, Math.min(bytes.length, (i + 1) * size));
                PacketDistributor.sendToServer(new Payloads.UploadChunk(request.sha1(), i, total, part));
            }
        }));
    }

    public static void onJobStatus(Payloads.JobStatus status) {
        lastStatus = status;
        lastStatusTime = System.currentTimeMillis();
    }

    /** Latest status for the given job, if received within the last few seconds. */
    @Nullable
    public static Payloads.JobStatus status(java.util.UUID job) {
        if (lastStatus == null || !lastStatus.job().equals(job) || System.currentTimeMillis() - lastStatusTime > 5000) return null;
        return lastStatus;
    }

    public static void clear() {
        lastStatus = null;
    }
}
