package com.maidbuilder.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** All custom payloads of the mod. Handlers live in {@link ModNetwork}. */
public final class Payloads {
    /**
     * Serverbound custom payloads are limited to 32767 bytes (the channel adds one byte for the
     * message index); keep chunks well below that.
     */
    public static final int UPLOAD_CHUNK_BYTES = 30_000;
    /** Clientbound custom payloads may be up to 1 MiB. */
    public static final int DOWNLOAD_CHUNK_BYTES = 256 * 1024;

    private Payloads() {
    }

    // ---- shared encoding helpers ----

    private static <E extends Enum<E>> E enumById(E[] values, int id) {
        return values[Math.floorMod(id, values.length)];
    }

    private static void writeItem(FriendlyByteBuf buf, Item item) {
        buf.writeId(BuiltInRegistries.ITEM, item);
    }

    private static Item readItem(FriendlyByteBuf buf) {
        Item item = buf.readById(BuiltInRegistries.ITEM);
        return item == null ? Items.AIR : item;
    }

    private static <T> void writeList(FriendlyByteBuf buf, List<T> list, BiConsumer<FriendlyByteBuf, T> writer) {
        buf.writeVarInt(list.size());
        for (T t : list) writer.accept(buf, t);
    }

    private static <T> List<T> readList(FriendlyByteBuf buf, int max, Function<FriendlyByteBuf, T> reader) {
        int size = buf.readVarInt();
        if (size < 0 || size > max) throw new DecoderException(size + " elements exceeds the limit of " + max);
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(reader.apply(buf));
        return list;
    }

    private static byte[] readBytes(FriendlyByteBuf buf, int max) {
        return buf.readByteArray(max);
    }

    /** Client -> server: the player picked a schematic in the selection screen. */
    public record SelectSchematic(String file, String sha1, BlockPos origin) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(file, 1024);
            buf.writeUtf(sha1, 40);
            buf.writeBlockPos(origin);
        }

        public static SelectSchematic decode(FriendlyByteBuf buf) {
            return new SelectSchematic(buf.readUtf(1024), buf.readUtf(40), buf.readBlockPos());
        }
    }

    public enum Adjustment {
        ROTATE, MIRROR, UP, DOWN, FORWARD, BACK, LEFT, RIGHT
    }

    /** Client -> server: a wand key was pressed. */
    public record AdjustPlacement(Adjustment adjustment) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(adjustment.ordinal());
        }

        public static AdjustPlacement decode(FriendlyByteBuf buf) {
            return new AdjustPlacement(enumById(Adjustment.values(), buf.readVarInt()));
        }
    }

    /** Server -> client: the server does not have this schematic; please upload it. */
    public record RequestUpload(String file, String sha1) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(file);
            buf.writeUtf(sha1);
        }

        public static RequestUpload decode(FriendlyByteBuf buf) {
            return new RequestUpload(buf.readUtf(), buf.readUtf());
        }
    }

    /** Client -> server: one piece of a schematic upload. */
    public record UploadChunk(String sha1, int index, int total, byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(sha1, 40);
            buf.writeVarInt(index);
            buf.writeVarInt(total);
            buf.writeByteArray(data);
        }

        public static UploadChunk decode(FriendlyByteBuf buf) {
            return new UploadChunk(buf.readUtf(40), buf.readVarInt(), buf.readVarInt(), readBytes(buf, UPLOAD_CHUNK_BYTES));
        }
    }

    public record ItemCount(Item item, int count) {
        void encode(FriendlyByteBuf buf) {
            writeItem(buf, item);
            buf.writeVarInt(count);
        }

        static ItemCount decode(FriendlyByteBuf buf) {
            return new ItemCount(readItem(buf), buf.readVarInt());
        }
    }

    /**
     * Server -> client: progress of the job linked to the wand the player holds.
     *
     * @param missing items still needed that are neither in the material containers nor in the maids' reach
     */
    public record JobStatus(UUID job, String name, int done, int total, int needsPlayer, int failed,
                            int sources, int maids, List<ItemCount> missing) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(job);
            buf.writeUtf(name);
            buf.writeVarInt(done);
            buf.writeVarInt(total);
            buf.writeVarInt(needsPlayer);
            buf.writeVarInt(failed);
            buf.writeVarInt(sources);
            buf.writeVarInt(maids);
            writeList(buf, missing, (b, c) -> c.encode(b));
        }

        public static JobStatus decode(FriendlyByteBuf buf) {
            return new JobStatus(buf.readUUID(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), readList(buf, 64, ItemCount::decode));
        }
    }

    /** At most this many rows per material report; far more than any real schematic has item types. */
    public static final int MAX_MATERIAL_ROWS = 4096;

    /**
     * One item of a material list.
     *
     * @param remaining  still needed for the blocks not built yet
     * @param total      needed for the whole structure
     * @param player     in the player's inventory
     * @param containers in the job's material containers (0 before the job exists)
     * @param maids      carried by the maids bound to the job (0 before the job exists)
     */
    public record MaterialRow(Item item, int remaining, int total, int player, int containers, int maids) {
        void encode(FriendlyByteBuf buf) {
            writeItem(buf, item);
            buf.writeVarInt(remaining);
            buf.writeVarInt(total);
            buf.writeVarInt(player);
            buf.writeVarInt(containers);
            buf.writeVarInt(maids);
        }

        static MaterialRow decode(FriendlyByteBuf buf) {
            return new MaterialRow(readItem(buf), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        public int available() {
            return player + containers + maids;
        }
    }

    /** Client -> server: send me the material list of the job linked to the wand I hold. */
    public record RequestMaterialReport(boolean open) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(open);
        }

        public static RequestMaterialReport decode(FriendlyByteBuf buf) {
            return new RequestMaterialReport(buf.readBoolean());
        }
    }

    /**
     * Server -> client: the material list of a job.
     *
     * @param open             open the material screen (otherwise only refresh it if it shows this job)
     * @param unloadedSources  material containers that could not be read because their chunk is not loaded
     * @param maids            maids bound to the job in loaded chunks
     */
    public record MaterialReport(boolean open, UUID job, String name, int done, int total, int needsPlayer,
                                 int sources, int unloadedSources, int maids, List<MaterialRow> rows) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(open);
            buf.writeUUID(job);
            buf.writeUtf(name);
            buf.writeVarInt(done);
            buf.writeVarInt(total);
            buf.writeVarInt(needsPlayer);
            buf.writeVarInt(sources);
            buf.writeVarInt(unloadedSources);
            buf.writeVarInt(maids);
            writeList(buf, rows, (b, r) -> r.encode(b));
        }

        public static MaterialReport decode(FriendlyByteBuf buf) {
            return new MaterialReport(buf.readBoolean(), buf.readUUID(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    readList(buf, MAX_MATERIAL_ROWS, MaterialRow::decode));
        }
    }

    /**
     * Client -> server: a resize key was pressed while holding the Blueprint Quill: move the face of
     * the marked box the player points at (or, with one corner marked, the air distance).
     */
    public record QuillAdjust(boolean grow, int amount) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(grow);
            buf.writeVarInt(amount);
        }

        public static QuillAdjust decode(FriendlyByteBuf buf) {
            return new QuillAdjust(buf.readBoolean(), buf.readVarInt());
        }
    }

    /** Client -> server: save the area marked with the held Blueprint Quill under this name. */
    public record CaptureRequest(String name) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name, 256);
        }

        public static CaptureRequest decode(FriendlyByteBuf buf) {
            return new CaptureRequest(buf.readUtf(256));
        }
    }

    /** Server -> client: one piece of a captured blueprint to write into the player's schematics folder. */
    public record DownloadChunk(String name, int index, int total, byte[] data) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name, 256);
            buf.writeVarInt(index);
            buf.writeVarInt(total);
            buf.writeByteArray(data);
        }

        public static DownloadChunk decode(FriendlyByteBuf buf) {
            return new DownloadChunk(buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), readBytes(buf, DOWNLOAD_CHUNK_BYTES));
        }
    }
}
