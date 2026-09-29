package com.maidbuilder.network;

import com.maidbuilder.MaidBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.UUID;

/** All custom payloads of the mod. Handlers live in {@link ModNetwork}. */
public final class Payloads {
    /** Serverbound custom payloads are limited to 32767 bytes; keep chunks well below that. */
    public static final int UPLOAD_CHUNK_BYTES = 30_000;
    /** Clientbound custom payloads may be up to 1 MiB. */
    public static final int DOWNLOAD_CHUNK_BYTES = 256 * 1024;

    private Payloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(MaidBuilder.id(path));
    }

    /** Client -> server: the player picked a schematic in the selection screen. */
    public record SelectSchematic(String file, String sha1, BlockPos origin) implements CustomPacketPayload {
        public static final Type<SelectSchematic> TYPE = payloadType("select_schematic");
        public static final StreamCodec<ByteBuf, SelectSchematic> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1024), SelectSchematic::file,
                ByteBufCodecs.stringUtf8(40), SelectSchematic::sha1,
                BlockPos.STREAM_CODEC, SelectSchematic::origin,
                SelectSchematic::new);

        @Override
        public Type<SelectSchematic> type() {
            return TYPE;
        }
    }

    public enum Adjustment {
        ROTATE, MIRROR, UP, DOWN, FORWARD, BACK, LEFT, RIGHT
    }

    /** Client -> server: a wand key was pressed. */
    public record AdjustPlacement(Adjustment adjustment) implements CustomPacketPayload {
        public static final Type<AdjustPlacement> TYPE = payloadType("adjust_placement");
        public static final StreamCodec<ByteBuf, AdjustPlacement> CODEC = ByteBufCodecs.VAR_INT.map(
                i -> new AdjustPlacement(Adjustment.values()[Math.floorMod(i, Adjustment.values().length)]),
                p -> p.adjustment().ordinal());

        @Override
        public Type<AdjustPlacement> type() {
            return TYPE;
        }
    }

    /** Server -> client: the server does not have this schematic; please upload it. */
    public record RequestUpload(String file, String sha1) implements CustomPacketPayload {
        public static final Type<RequestUpload> TYPE = payloadType("request_upload");
        public static final StreamCodec<ByteBuf, RequestUpload> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RequestUpload::file,
                ByteBufCodecs.STRING_UTF8, RequestUpload::sha1,
                RequestUpload::new);

        @Override
        public Type<RequestUpload> type() {
            return TYPE;
        }
    }

    /** Client -> server: one piece of a schematic upload. */
    public record UploadChunk(String sha1, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<UploadChunk> TYPE = payloadType("upload_chunk");
        public static final StreamCodec<ByteBuf, UploadChunk> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(40), UploadChunk::sha1,
                ByteBufCodecs.VAR_INT, UploadChunk::index,
                ByteBufCodecs.VAR_INT, UploadChunk::total,
                ByteBufCodecs.byteArray(UPLOAD_CHUNK_BYTES), UploadChunk::data,
                UploadChunk::new);

        @Override
        public Type<UploadChunk> type() {
            return TYPE;
        }
    }

    public record ItemCount(Item item, int count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ItemCount> CODEC = StreamCodec.composite(
                ByteBufCodecs.registry(Registries.ITEM), ItemCount::item,
                ByteBufCodecs.VAR_INT, ItemCount::count,
                ItemCount::new);
    }

    /**
     * Server -> client: progress of the job linked to the wand the player holds.
     *
     * @param missing items still needed that are neither in the material containers nor in the maids' reach
     */
    public record JobStatus(UUID job, String name, int done, int total, int needsPlayer, int failed,
                            int sources, int maids, List<ItemCount> missing) implements CustomPacketPayload {
        public static final Type<JobStatus> TYPE = payloadType("job_status");
        public static final StreamCodec<RegistryFriendlyByteBuf, JobStatus> CODEC = StreamCodec.of(
                (buf, s) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, s.job());
                    ByteBufCodecs.STRING_UTF8.encode(buf, s.name());
                    buf.writeVarInt(s.done());
                    buf.writeVarInt(s.total());
                    buf.writeVarInt(s.needsPlayer());
                    buf.writeVarInt(s.failed());
                    buf.writeVarInt(s.sources());
                    buf.writeVarInt(s.maids());
                    ItemCount.CODEC.apply(ByteBufCodecs.list(64)).encode(buf, s.missing());
                },
                buf -> new JobStatus(UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        ItemCount.CODEC.apply(ByteBufCodecs.list(64)).decode(buf)));

        @Override
        public Type<JobStatus> type() {
            return TYPE;
        }
    }

    /** Client -> server: save the area marked with the held Blueprint Quill under this name. */
    public record CaptureRequest(String name) implements CustomPacketPayload {
        public static final Type<CaptureRequest> TYPE = payloadType("capture_request");
        public static final StreamCodec<ByteBuf, CaptureRequest> CODEC = ByteBufCodecs.stringUtf8(256)
                .map(CaptureRequest::new, CaptureRequest::name);

        @Override
        public Type<CaptureRequest> type() {
            return TYPE;
        }
    }

    /** Server -> client: one piece of a captured blueprint to write into the player's schematics folder. */
    public record DownloadChunk(String name, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<DownloadChunk> TYPE = payloadType("download_chunk");
        public static final StreamCodec<ByteBuf, DownloadChunk> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(256), DownloadChunk::name,
                ByteBufCodecs.VAR_INT, DownloadChunk::index,
                ByteBufCodecs.VAR_INT, DownloadChunk::total,
                ByteBufCodecs.byteArray(DOWNLOAD_CHUNK_BYTES), DownloadChunk::data,
                DownloadChunk::new);

        @Override
        public Type<DownloadChunk> type() {
            return TYPE;
        }
    }
}
