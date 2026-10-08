package com.maidbuilder.network;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.territory.TemplateDef;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Payloads of the territory / building template gameplay. Handlers live in {@link ModNetwork}. */
public final class TerritoryPayloads {
    private TerritoryPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(MaidBuilder.id(path));
    }

    private static final StreamCodec<ByteBuf, Optional<UUID>> OPTIONAL_UUID = ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC);

    // ---- territory list (borders, build mode checks) ----

    /** A building or a planned (queued) building of a territory, as an inclusive box. */
    public record Box(BlockPos min, BlockPos max, boolean planned) {
        public static final StreamCodec<ByteBuf, Box> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Box::min,
                BlockPos.STREAM_CODEC, Box::max,
                ByteBufCodecs.BOOL, Box::planned,
                Box::new);

        public boolean intersects(BlockPos lo, BlockPos hi) {
            return lo.getX() <= max.getX() && min.getX() <= hi.getX() && lo.getY() <= max.getY() && min.getY() <= hi.getY()
                    && lo.getZ() <= max.getZ() && min.getZ() <= hi.getZ();
        }
    }

    public record TerritoryInfo(UUID id, UUID owner, String ownerName, ResourceLocation dimension, BlockPos flag, int radius,
                                int level, boolean active, List<Box> boxes) {
        public static final StreamCodec<ByteBuf, TerritoryInfo> CODEC = StreamCodec.of(
                (buf, t) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, t.id());
                    UUIDUtil.STREAM_CODEC.encode(buf, t.owner());
                    ByteBufCodecs.STRING_UTF8.encode(buf, t.ownerName());
                    ResourceLocation.STREAM_CODEC.encode(buf, t.dimension());
                    BlockPos.STREAM_CODEC.encode(buf, t.flag());
                    ByteBufCodecs.VAR_INT.encode(buf, t.radius());
                    ByteBufCodecs.VAR_INT.encode(buf, t.level());
                    ByteBufCodecs.BOOL.encode(buf, t.active());
                    Box.CODEC.apply(ByteBufCodecs.list(4096)).encode(buf, t.boxes());
                },
                buf -> new TerritoryInfo(UUIDUtil.STREAM_CODEC.decode(buf), UUIDUtil.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf), BlockPos.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf),
                        Box.CODEC.apply(ByteBufCodecs.list(4096)).decode(buf)));

        public boolean contains(int x, int z) {
            return Math.abs(x - flag.getX()) <= radius && Math.abs(z - flag.getZ()) <= radius;
        }
    }

    /** Server -> client: every territory (sent on login and whenever one changes). */
    public record TerritoryList(List<TerritoryInfo> territories) implements CustomPacketPayload {
        public static final Type<TerritoryList> TYPE = payloadType("territory_list");
        public static final StreamCodec<ByteBuf, TerritoryList> CODEC = TerritoryInfo.CODEC.apply(ByteBufCodecs.list(65536))
                .map(TerritoryList::new, TerritoryList::territories);

        @Override
        public Type<TerritoryList> type() {
            return TYPE;
        }
    }

    // ---- territory screen ----

    /** One upgrade condition; {@code kind} is {@link com.maidbuilder.core.territory.TerritoryRules.Kind#ordinal()}. */
    public record ConditionRow(int kind, String key, int have, int need) {
        public static final StreamCodec<ByteBuf, ConditionRow> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ConditionRow::kind,
                ByteBufCodecs.STRING_UTF8, ConditionRow::key,
                ByteBufCodecs.VAR_INT, ConditionRow::have,
                ByteBufCodecs.VAR_INT, ConditionRow::need,
                ConditionRow::new);

        public boolean met() {
            return have >= need;
        }
    }

    /** An upgrade item (or tag, key starting with '#') with an icon item. */
    public record ItemRow(String key, Item icon, int have, int need) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ItemRow> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, ItemRow::key,
                ByteBufCodecs.registry(Registries.ITEM), ItemRow::icon,
                ByteBufCodecs.VAR_INT, ItemRow::have,
                ByteBufCodecs.VAR_INT, ItemRow::need,
                ItemRow::new);
    }

    /** A queued build job. {@code template} is empty for a plain schematic. */
    public record JobRow(UUID id, String name, String template, int done, int total, int needsPlayer, int maids,
                         boolean suspended, boolean repair) {
        public static final StreamCodec<ByteBuf, JobRow> CODEC = StreamCodec.of(
                (buf, r) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, r.id());
                    ByteBufCodecs.STRING_UTF8.encode(buf, r.name());
                    ByteBufCodecs.STRING_UTF8.encode(buf, r.template());
                    ByteBufCodecs.VAR_INT.encode(buf, r.done());
                    ByteBufCodecs.VAR_INT.encode(buf, r.total());
                    ByteBufCodecs.VAR_INT.encode(buf, r.needsPlayer());
                    ByteBufCodecs.VAR_INT.encode(buf, r.maids());
                    ByteBufCodecs.BOOL.encode(buf, r.suspended());
                    ByteBufCodecs.BOOL.encode(buf, r.repair());
                },
                buf -> new JobRow(UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.BOOL.decode(buf)));
    }

    /** A registered building. */
    public record BuildingRow(UUID id, String template, boolean working, int integrity, int maids, int maxMaids,
                              boolean repairQueued, BlockPos center) {
        public static final StreamCodec<ByteBuf, BuildingRow> CODEC = StreamCodec.of(
                (buf, r) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, r.id());
                    ByteBufCodecs.STRING_UTF8.encode(buf, r.template());
                    ByteBufCodecs.BOOL.encode(buf, r.working());
                    ByteBufCodecs.VAR_INT.encode(buf, r.integrity());
                    ByteBufCodecs.VAR_INT.encode(buf, r.maids());
                    ByteBufCodecs.VAR_INT.encode(buf, r.maxMaids());
                    ByteBufCodecs.BOOL.encode(buf, r.repairQueued());
                    BlockPos.STREAM_CODEC.encode(buf, r.center());
                },
                buf -> new BuildingRow(UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf), BlockPos.STREAM_CODEC.decode(buf)));

        public boolean isWorkplace() {
            return maxMaids > 0;
        }
    }

    /**
     * A maid of the territory owner that lives here (home mode) or works for it.
     *
     * @param loaded   her chunk is loaded (otherwise only the name is known and nothing can be done)
     * @param task     her TLM task id
     * @param builder  she builds the territory's queue
     * @param building the building she works at
     * @param paused   she stopped working because her backpack is full
     */
    public record MaidRow(UUID id, String name, boolean loaded, boolean home, String task, boolean builder,
                          Optional<UUID> building, int used, int slots, boolean paused, boolean depositing) {
        public static final StreamCodec<ByteBuf, MaidRow> CODEC = StreamCodec.of(
                (buf, r) -> {
                    UUIDUtil.STREAM_CODEC.encode(buf, r.id());
                    ByteBufCodecs.STRING_UTF8.encode(buf, r.name());
                    ByteBufCodecs.BOOL.encode(buf, r.loaded());
                    ByteBufCodecs.BOOL.encode(buf, r.home());
                    ByteBufCodecs.STRING_UTF8.encode(buf, r.task());
                    ByteBufCodecs.BOOL.encode(buf, r.builder());
                    OPTIONAL_UUID.encode(buf, r.building());
                    ByteBufCodecs.VAR_INT.encode(buf, r.used());
                    ByteBufCodecs.VAR_INT.encode(buf, r.slots());
                    ByteBufCodecs.BOOL.encode(buf, r.paused());
                    ByteBufCodecs.BOOL.encode(buf, r.depositing());
                },
                buf -> new MaidRow(UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.BOOL.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.BOOL.decode(buf),
                        OPTIONAL_UUID.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.BOOL.decode(buf)));

        public boolean full() {
            return slots > 0 && used >= slots;
        }
    }

    /**
     * Server -> client: everything the territory screen shows.
     *
     * @param nextRadius radius after the next upgrade, -1 at the top level
     * @param bonuses    bonus ids active now
     * @param nextBonuses bonus ids the next level adds
     */
    public record TerritoryReport(boolean open, UUID id, String ownerName, BlockPos flag, int level, int maxLevel, int radius,
                                  int nextRadius, boolean active, int flagsOwned, int maxFlags,
                                  int prosperity, int buildingPoints, int residentPoints, int residents, int residentCap,
                                  List<ConditionRow> conditions, List<ItemRow> upgradeItems, boolean canUpgrade,
                                  List<JobRow> jobs, List<BuildingRow> buildings, List<MaidRow> maids,
                                  int sources, boolean bindingSources, boolean hasWarehouse,
                                  List<String> bonuses, List<String> nextBonuses) implements CustomPacketPayload {
        public static final Type<TerritoryReport> TYPE = payloadType("territory_report");
        private static final StreamCodec<ByteBuf, List<String>> STRINGS = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(256));
        public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryReport> CODEC = StreamCodec.of(
                (buf, r) -> {
                    buf.writeBoolean(r.open());
                    UUIDUtil.STREAM_CODEC.encode(buf, r.id());
                    ByteBufCodecs.STRING_UTF8.encode(buf, r.ownerName());
                    BlockPos.STREAM_CODEC.encode(buf, r.flag());
                    buf.writeVarInt(r.level());
                    buf.writeVarInt(r.maxLevel());
                    buf.writeVarInt(r.radius());
                    buf.writeVarInt(r.nextRadius() + 1);
                    buf.writeBoolean(r.active());
                    buf.writeVarInt(r.flagsOwned());
                    buf.writeVarInt(r.maxFlags());
                    buf.writeVarInt(r.prosperity());
                    buf.writeVarInt(r.buildingPoints());
                    buf.writeVarInt(r.residentPoints());
                    buf.writeVarInt(r.residents());
                    buf.writeVarInt(r.residentCap());
                    ConditionRow.CODEC.apply(ByteBufCodecs.list(256)).encode(buf, r.conditions());
                    ItemRow.CODEC.apply(ByteBufCodecs.list(256)).encode(buf, r.upgradeItems());
                    buf.writeBoolean(r.canUpgrade());
                    JobRow.CODEC.apply(ByteBufCodecs.list(1024)).encode(buf, r.jobs());
                    BuildingRow.CODEC.apply(ByteBufCodecs.list(1024)).encode(buf, r.buildings());
                    MaidRow.CODEC.apply(ByteBufCodecs.list(1024)).encode(buf, r.maids());
                    buf.writeVarInt(r.sources());
                    buf.writeBoolean(r.bindingSources());
                    buf.writeBoolean(r.hasWarehouse());
                    STRINGS.encode(buf, r.bonuses());
                    STRINGS.encode(buf, r.nextBonuses());
                },
                buf -> new TerritoryReport(buf.readBoolean(), UUIDUtil.STREAM_CODEC.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
                        BlockPos.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1,
                        buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                        ConditionRow.CODEC.apply(ByteBufCodecs.list(256)).decode(buf),
                        ItemRow.CODEC.apply(ByteBufCodecs.list(256)).decode(buf), buf.readBoolean(),
                        JobRow.CODEC.apply(ByteBufCodecs.list(1024)).decode(buf),
                        BuildingRow.CODEC.apply(ByteBufCodecs.list(1024)).decode(buf),
                        MaidRow.CODEC.apply(ByteBufCodecs.list(1024)).decode(buf),
                        buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), STRINGS.decode(buf), STRINGS.decode(buf)));

        @Override
        public Type<TerritoryReport> type() {
            return TYPE;
        }
    }

    /** Client -> server: send the territory screen data (again). */
    public record RequestTerritoryReport(UUID territory, boolean open) implements CustomPacketPayload {
        public static final Type<RequestTerritoryReport> TYPE = payloadType("request_territory_report");
        public static final StreamCodec<ByteBuf, RequestTerritoryReport> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, RequestTerritoryReport::territory,
                ByteBufCodecs.BOOL, RequestTerritoryReport::open,
                RequestTerritoryReport::new);

        @Override
        public Type<RequestTerritoryReport> type() {
            return TYPE;
        }
    }

    public enum Action {
        UPGRADE, JOB_UP, JOB_DOWN, JOB_CANCEL, JOB_MATERIALS, REPAIR, REMOVE_BUILDING, BIND_SOURCES, TOGGLE_BUILDER, ASSIGN, UNASSIGN,
        DEPOSIT, RESUME
    }

    /** Client -> server: a button of the territory screen. {@code target} is a job, building or maid; {@code other} a building. */
    public record TerritoryAction(UUID territory, Action action, Optional<UUID> target, Optional<UUID> other) implements CustomPacketPayload {
        public static final Type<TerritoryAction> TYPE = payloadType("territory_action");
        public static final StreamCodec<ByteBuf, TerritoryAction> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, TerritoryAction::territory,
                ByteBufCodecs.VAR_INT.map(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal), TerritoryAction::action,
                OPTIONAL_UUID, TerritoryAction::target,
                OPTIONAL_UUID, TerritoryAction::other,
                TerritoryAction::new);

        @Override
        public Type<TerritoryAction> type() {
            return TYPE;
        }
    }

    // ---- templates ----

    /** A template as the catalog shows it. */
    public record TemplateInfo(TemplateDef def, int sizeX, int sizeY, int sizeZ, int blocks) {
        public static final StreamCodec<ByteBuf, TemplateInfo> CODEC = StreamCodec.composite(
                TemplateDef.STREAM_CODEC, TemplateInfo::def,
                ByteBufCodecs.VAR_INT, TemplateInfo::sizeX,
                ByteBufCodecs.VAR_INT, TemplateInfo::sizeY,
                ByteBufCodecs.VAR_INT, TemplateInfo::sizeZ,
                ByteBufCodecs.VAR_INT, TemplateInfo::blocks,
                TemplateInfo::new);
    }

    /** Server -> client: the template catalog (on login and after a data pack reload). */
    public record TemplateList(List<TemplateInfo> templates) implements CustomPacketPayload {
        public static final Type<TemplateList> TYPE = payloadType("template_list");
        public static final StreamCodec<ByteBuf, TemplateList> CODEC = TemplateInfo.CODEC.apply(ByteBufCodecs.list(4096))
                .map(TemplateList::new, TemplateList::templates);

        @Override
        public Type<TemplateList> type() {
            return TYPE;
        }
    }

    /** Client -> server: send me this template's schematic (for the preview). */
    public record RequestTemplate(ResourceLocation id) implements CustomPacketPayload {
        public static final Type<RequestTemplate> TYPE = payloadType("request_template");
        public static final StreamCodec<ByteBuf, RequestTemplate> CODEC = ResourceLocation.STREAM_CODEC
                .map(RequestTemplate::new, RequestTemplate::id);

        @Override
        public Type<RequestTemplate> type() {
            return TYPE;
        }
    }

    /** Server -> client: a template's schematic file. */
    public record TemplateStructure(ResourceLocation id, String sha1, byte[] bytes) implements CustomPacketPayload {
        public static final Type<TemplateStructure> TYPE = payloadType("template_structure");
        public static final StreamCodec<ByteBuf, TemplateStructure> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, TemplateStructure::id,
                ByteBufCodecs.stringUtf8(40), TemplateStructure::sha1,
                ByteBufCodecs.byteArray(1024 * 1024), TemplateStructure::bytes,
                TemplateStructure::new);

        @Override
        public Type<TemplateStructure> type() {
            return TYPE;
        }
    }

    /** Client -> server: build this template here (build mode confirm). */
    public record PlaceTemplate(UUID territory, ResourceLocation template, BlockPos origin, Rotation rotation, Mirror mirror)
            implements CustomPacketPayload {
        public static final Type<PlaceTemplate> TYPE = payloadType("place_template");
        public static final StreamCodec<ByteBuf, PlaceTemplate> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, PlaceTemplate::territory,
                ResourceLocation.STREAM_CODEC, PlaceTemplate::template,
                BlockPos.STREAM_CODEC, PlaceTemplate::origin,
                ByteBufCodecs.VAR_INT.map(i -> Rotation.values()[Math.floorMod(i, 4)], Rotation::ordinal), PlaceTemplate::rotation,
                ByteBufCodecs.VAR_INT.map(i -> Mirror.values()[Math.floorMod(i, 3)], Mirror::ordinal), PlaceTemplate::mirror,
                PlaceTemplate::new);

        @Override
        public Type<PlaceTemplate> type() {
            return TYPE;
        }
    }

    /** Server -> client: result of {@link PlaceTemplate}; on success the client leaves placement. */
    public record PlaceResult(boolean success) implements CustomPacketPayload {
        public static final Type<PlaceResult> TYPE = payloadType("place_result");
        public static final StreamCodec<ByteBuf, PlaceResult> CODEC = ByteBufCodecs.BOOL.map(PlaceResult::new, PlaceResult::success);

        @Override
        public Type<PlaceResult> type() {
            return TYPE;
        }
    }
}
