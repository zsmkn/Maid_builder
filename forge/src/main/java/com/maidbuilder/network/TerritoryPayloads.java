package com.maidbuilder.network;

import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.territory.TemplateDef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Payloads of the territory / building template gameplay. Handlers live in {@link ModNetwork}. */
public final class TerritoryPayloads {
    private TerritoryPayloads() {
    }

    private static <T> void writeList(FriendlyByteBuf buf, List<T> list, BiConsumer<FriendlyByteBuf, T> writer) {
        Payloads.writeList(buf, list, writer);
    }

    private static <T> List<T> readList(FriendlyByteBuf buf, int max, Function<FriendlyByteBuf, T> reader) {
        return Payloads.readList(buf, max, reader);
    }

    private static void writeOptionalUuid(FriendlyByteBuf buf, Optional<UUID> id) {
        buf.writeOptional(id, FriendlyByteBuf::writeUUID);
    }

    private static Optional<UUID> readOptionalUuid(FriendlyByteBuf buf) {
        return buf.readOptional(FriendlyByteBuf::readUUID);
    }

    // ---- territory list (borders, build mode checks) ----

    /** A building or a planned (queued) building of a territory, as an inclusive box. */
    public record Box(BlockPos min, BlockPos max, boolean planned) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(min);
            buf.writeBlockPos(max);
            buf.writeBoolean(planned);
        }

        public static Box decode(FriendlyByteBuf buf) {
            return new Box(buf.readBlockPos(), buf.readBlockPos(), buf.readBoolean());
        }

        public boolean intersects(BlockPos lo, BlockPos hi) {
            return lo.getX() <= max.getX() && min.getX() <= hi.getX() && lo.getY() <= max.getY() && min.getY() <= hi.getY()
                    && lo.getZ() <= max.getZ() && min.getZ() <= hi.getZ();
        }
    }

    public record TerritoryInfo(UUID id, UUID owner, String ownerName, ResourceLocation dimension, BlockPos flag, int radius,
                                int level, boolean active, List<Box> boxes) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUUID(owner);
            buf.writeUtf(ownerName);
            buf.writeResourceLocation(dimension);
            buf.writeBlockPos(flag);
            buf.writeVarInt(radius);
            buf.writeVarInt(level);
            buf.writeBoolean(active);
            writeList(buf, boxes, (b, x) -> x.encode(b));
        }

        public static TerritoryInfo decode(FriendlyByteBuf buf) {
            return new TerritoryInfo(buf.readUUID(), buf.readUUID(), buf.readUtf(), buf.readResourceLocation(), buf.readBlockPos(),
                    buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), readList(buf, 4096, Box::decode));
        }

        public boolean contains(int x, int z) {
            return Math.abs(x - flag.getX()) <= radius && Math.abs(z - flag.getZ()) <= radius;
        }
    }

    /** Server -> client: every territory (sent on login and whenever one changes). */
    public record TerritoryList(List<TerritoryInfo> territories) {
        public void encode(FriendlyByteBuf buf) {
            writeList(buf, territories, (b, t) -> t.encode(b));
        }

        public static TerritoryList decode(FriendlyByteBuf buf) {
            return new TerritoryList(readList(buf, 65536, TerritoryInfo::decode));
        }
    }

    // ---- territory screen ----

    /** One upgrade condition; {@code kind} is {@link com.maidbuilder.core.territory.TerritoryRules.Kind#ordinal()}. */
    public record ConditionRow(int kind, String key, int have, int need) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(kind);
            buf.writeUtf(key);
            buf.writeVarInt(have);
            buf.writeVarInt(need);
        }

        public static ConditionRow decode(FriendlyByteBuf buf) {
            return new ConditionRow(buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readVarInt());
        }

        public boolean met() {
            return have >= need;
        }
    }

    /** An upgrade item (or tag, key starting with '#') with an icon item. */
    public record ItemRow(String key, Item icon, int have, int need) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(key);
            buf.writeId(BuiltInRegistries.ITEM, icon);
            buf.writeVarInt(have);
            buf.writeVarInt(need);
        }

        public static ItemRow decode(FriendlyByteBuf buf) {
            String key = buf.readUtf();
            Item icon = buf.readById(BuiltInRegistries.ITEM);
            return new ItemRow(key, icon == null ? Items.AIR : icon, buf.readVarInt(), buf.readVarInt());
        }
    }

    /** A queued build job. {@code template} is empty for a plain schematic. */
    public record JobRow(UUID id, String name, String template, int done, int total, int needsPlayer, int maids,
                         boolean suspended, boolean repair) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(name);
            buf.writeUtf(template);
            buf.writeVarInt(done);
            buf.writeVarInt(total);
            buf.writeVarInt(needsPlayer);
            buf.writeVarInt(maids);
            buf.writeBoolean(suspended);
            buf.writeBoolean(repair);
        }

        public static JobRow decode(FriendlyByteBuf buf) {
            return new JobRow(buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
        }
    }

    /** A registered building. */
    public record BuildingRow(UUID id, String template, boolean working, int integrity, int maids, int maxMaids,
                              boolean repairQueued, BlockPos center) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(template);
            buf.writeBoolean(working);
            buf.writeVarInt(integrity);
            buf.writeVarInt(maids);
            buf.writeVarInt(maxMaids);
            buf.writeBoolean(repairQueued);
            buf.writeBlockPos(center);
        }

        public static BuildingRow decode(FriendlyByteBuf buf) {
            return new BuildingRow(buf.readUUID(), buf.readUtf(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean(), buf.readBlockPos());
        }

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
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(name);
            buf.writeBoolean(loaded);
            buf.writeBoolean(home);
            buf.writeUtf(task);
            buf.writeBoolean(builder);
            writeOptionalUuid(buf, building);
            buf.writeVarInt(used);
            buf.writeVarInt(slots);
            buf.writeBoolean(paused);
            buf.writeBoolean(depositing);
        }

        public static MaidRow decode(FriendlyByteBuf buf) {
            return new MaidRow(buf.readUUID(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(), buf.readUtf(), buf.readBoolean(),
                    readOptionalUuid(buf), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean());
        }

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
                                  List<String> bonuses, List<String> nextBonuses) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(open);
            buf.writeUUID(id);
            buf.writeUtf(ownerName);
            buf.writeBlockPos(flag);
            buf.writeVarInt(level);
            buf.writeVarInt(maxLevel);
            buf.writeVarInt(radius);
            buf.writeVarInt(nextRadius + 1);
            buf.writeBoolean(active);
            buf.writeVarInt(flagsOwned);
            buf.writeVarInt(maxFlags);
            buf.writeVarInt(prosperity);
            buf.writeVarInt(buildingPoints);
            buf.writeVarInt(residentPoints);
            buf.writeVarInt(residents);
            buf.writeVarInt(residentCap);
            writeList(buf, conditions, (b, r) -> r.encode(b));
            writeList(buf, upgradeItems, (b, r) -> r.encode(b));
            buf.writeBoolean(canUpgrade);
            writeList(buf, jobs, (b, r) -> r.encode(b));
            writeList(buf, buildings, (b, r) -> r.encode(b));
            writeList(buf, maids, (b, r) -> r.encode(b));
            buf.writeVarInt(sources);
            buf.writeBoolean(bindingSources);
            buf.writeBoolean(hasWarehouse);
            writeList(buf, bonuses, FriendlyByteBuf::writeUtf);
            writeList(buf, nextBonuses, FriendlyByteBuf::writeUtf);
        }

        public static TerritoryReport decode(FriendlyByteBuf buf) {
            return new TerritoryReport(buf.readBoolean(), buf.readUUID(), buf.readUtf(),
                    buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1,
                    buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    readList(buf, 256, ConditionRow::decode),
                    readList(buf, 256, ItemRow::decode), buf.readBoolean(),
                    readList(buf, 1024, JobRow::decode),
                    readList(buf, 1024, BuildingRow::decode),
                    readList(buf, 1024, MaidRow::decode),
                    buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                    readList(buf, 256, FriendlyByteBuf::readUtf), readList(buf, 256, FriendlyByteBuf::readUtf));
        }
    }

    /** Client -> server: send the territory screen data (again). */
    public record RequestTerritoryReport(UUID territory, boolean open) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(territory);
            buf.writeBoolean(open);
        }

        public static RequestTerritoryReport decode(FriendlyByteBuf buf) {
            return new RequestTerritoryReport(buf.readUUID(), buf.readBoolean());
        }
    }

    public enum Action {
        UPGRADE, JOB_UP, JOB_DOWN, JOB_CANCEL, JOB_MATERIALS, REPAIR, REMOVE_BUILDING, BIND_SOURCES, TOGGLE_BUILDER, ASSIGN, UNASSIGN,
        DEPOSIT, RESUME, JOB_PROBLEMS, JOB_RETRY, JOB_SKIP
    }

    /** Client -> server: a button of the territory screen. {@code target} is a job, building or maid; {@code other} a building. */
    public record TerritoryAction(UUID territory, Action action, Optional<UUID> target, Optional<UUID> other) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(territory);
            buf.writeVarInt(action.ordinal());
            writeOptionalUuid(buf, target);
            writeOptionalUuid(buf, other);
        }

        public static TerritoryAction decode(FriendlyByteBuf buf) {
            return new TerritoryAction(buf.readUUID(), Action.values()[Math.floorMod(buf.readVarInt(), Action.values().length)],
                    readOptionalUuid(buf), readOptionalUuid(buf));
        }
    }

    // ---- templates ----

    /** A template as the catalog shows it. */
    public record TemplateInfo(TemplateDef def, int sizeX, int sizeY, int sizeZ, int blocks) {
        public void encode(FriendlyByteBuf buf) {
            def.write(buf);
            buf.writeVarInt(sizeX);
            buf.writeVarInt(sizeY);
            buf.writeVarInt(sizeZ);
            buf.writeVarInt(blocks);
        }

        public static TemplateInfo decode(FriendlyByteBuf buf) {
            return new TemplateInfo(TemplateDef.read(buf), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /** Server -> client: the template catalog (on login and after a data pack reload). */
    public record TemplateList(List<TemplateInfo> templates) {
        public void encode(FriendlyByteBuf buf) {
            writeList(buf, templates, (b, t) -> t.encode(b));
        }

        public static TemplateList decode(FriendlyByteBuf buf) {
            return new TemplateList(readList(buf, 4096, TemplateInfo::decode));
        }
    }

    /** Client -> server: send me this template's schematic (for the preview). */
    public record RequestTemplate(ResourceLocation id) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(id);
        }

        public static RequestTemplate decode(FriendlyByteBuf buf) {
            return new RequestTemplate(buf.readResourceLocation());
        }
    }

    /** Server -> client: a template's schematic file. */
    public record TemplateStructure(ResourceLocation id, String sha1, byte[] bytes) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(id);
            buf.writeUtf(sha1, 40);
            buf.writeByteArray(bytes);
        }

        public static TemplateStructure decode(FriendlyByteBuf buf) {
            return new TemplateStructure(buf.readResourceLocation(), buf.readUtf(40), buf.readByteArray(1024 * 1024));
        }
    }

    /** Client -> server: build this template here (build mode confirm). */
    public record PlaceTemplate(UUID territory, ResourceLocation template, BlockPos origin, Rotation rotation, Mirror mirror) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(territory);
            buf.writeResourceLocation(template);
            buf.writeBlockPos(origin);
            buf.writeVarInt(rotation.ordinal());
            buf.writeVarInt(mirror.ordinal());
        }

        public static PlaceTemplate decode(FriendlyByteBuf buf) {
            return new PlaceTemplate(buf.readUUID(), buf.readResourceLocation(), buf.readBlockPos(),
                    Rotation.values()[Math.floorMod(buf.readVarInt(), 4)], Mirror.values()[Math.floorMod(buf.readVarInt(), 3)]);
        }
    }

    /** Server -> client: result of {@link PlaceTemplate}; on success the client leaves placement. */
    public record PlaceResult(boolean success) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(success);
        }

        public static PlaceResult decode(FriendlyByteBuf buf) {
            return new PlaceResult(buf.readBoolean());
        }
    }

    // ---- travel between trading posts ----

    /** A trading post the player can travel to: its building, the territory's flag and where it stands. */
    public record TravelDestination(UUID building, ResourceLocation dimension, BlockPos flag, BlockPos pos) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(building);
            buf.writeResourceLocation(dimension);
            buf.writeBlockPos(flag);
            buf.writeBlockPos(pos);
        }

        public static TravelDestination decode(FriendlyByteBuf buf) {
            return new TravelDestination(buf.readUUID(), buf.readResourceLocation(), buf.readBlockPos(), buf.readBlockPos());
        }
    }

    /** Server -> client: open the travel menu of a trading post; the cost is per trip (count 0: free). */
    public record TravelMenu(UUID from, List<TravelDestination> destinations, ResourceLocation costItem, int costCount) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(from);
            writeList(buf, destinations, (b, d) -> d.encode(b));
            buf.writeResourceLocation(costItem);
            buf.writeVarInt(costCount);
        }

        public static TravelMenu decode(FriendlyByteBuf buf) {
            return new TravelMenu(buf.readUUID(), readList(buf, 1024, TravelDestination::decode), buf.readResourceLocation(),
                    buf.readVarInt());
        }
    }

    /** Client -> server: travel from one trading post to another. */
    public record Travel(UUID from, UUID to) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(from);
            buf.writeUUID(to);
        }

        public static Travel decode(FriendlyByteBuf buf) {
            return new Travel(buf.readUUID(), buf.readUUID());
        }
    }

    // ---- building supplies shop ----

    /** An item for sale: {@code count} of it per bundle for {@code price} emeralds; {@code left} still for sale today. */
    public record ShopRow(ResourceLocation item, String category, int price, int count, int left, int requiredLevel) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(item);
            buf.writeUtf(category);
            buf.writeVarInt(price);
            buf.writeVarInt(count);
            buf.writeVarInt(left);
            buf.writeVarInt(requiredLevel);
        }

        public static ShopRow decode(FriendlyByteBuf buf) {
            return new ShopRow(buf.readResourceLocation(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt());
        }
    }

    /** Why an item the territory's queue lacks cannot be ordered (in full). */
    public enum MissingStatus { OK, NOT_SOLD, LOCKED, SOLD_OUT }

    /** An item the territory's queued jobs still lack, and how many bundles ordering it would buy. */
    public record MissingRow(ResourceLocation item, int needed, int bundles, MissingStatus status) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeResourceLocation(item);
            buf.writeVarInt(needed);
            buf.writeVarInt(bundles);
            buf.writeVarInt(status.ordinal());
        }

        public static MissingRow decode(FriendlyByteBuf buf) {
            return new MissingRow(buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt(),
                    MissingStatus.values()[Math.floorMod(buf.readVarInt(), MissingStatus.values().length)]);
        }
    }

    /** Server -> client: the shop's goods and the territory's missing materials; {@code open} opens the screen, else it refreshes. */
    public record ShopMenu(UUID building, int level, String clerk, List<ShopRow> rows, List<MissingRow> missing, int missingCost,
                           boolean open) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(building);
            buf.writeVarInt(level);
            buf.writeUtf(clerk);
            writeList(buf, rows, (b, r) -> r.encode(b));
            writeList(buf, missing, (b, r) -> r.encode(b));
            buf.writeVarInt(missingCost);
            buf.writeBoolean(open);
        }

        public static ShopMenu decode(FriendlyByteBuf buf) {
            return new ShopMenu(buf.readUUID(), buf.readVarInt(), buf.readUtf(), readList(buf, 8192, ShopRow::decode),
                    readList(buf, 8192, MissingRow::decode), buf.readVarInt(), buf.readBoolean());
        }
    }

    // ---- blocks left for the player ----

    private static void writeState(FriendlyByteBuf buf, BlockState state) {
        buf.writeVarInt(Block.getId(state));
    }

    private static BlockState readState(FriendlyByteBuf buf) {
        return Block.stateById(buf.readVarInt());
    }

    /** A block the maids left: what goes there, what is there now and why ({@link BuildJob.Problem}). */
    public record ProblemRow(BlockPos pos, BlockState target, BlockState current, BuildJob.Problem problem) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeBlockPos(pos);
            writeState(buf, target);
            writeState(buf, current);
            buf.writeVarInt(problem.ordinal());
        }

        public static ProblemRow decode(FriendlyByteBuf buf) {
            return new ProblemRow(buf.readBlockPos(), readState(buf), readState(buf),
                    BuildJob.Problem.values()[Math.floorMod(buf.readVarInt(), BuildJob.Problem.values().length)]);
        }
    }

    /**
     * Server -> client: the blocks of a queued job left for the player, nearest first ({@code total}
     * may be more than the rows sent).
     */
    public record JobProblems(UUID territory, UUID job, String name, String template, ResourceLocation dimension, int total,
                              List<ProblemRow> rows) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(territory);
            buf.writeUUID(job);
            buf.writeUtf(name);
            buf.writeUtf(template);
            buf.writeResourceLocation(dimension);
            buf.writeVarInt(total);
            writeList(buf, rows, (b, r) -> r.encode(b));
        }

        public static JobProblems decode(FriendlyByteBuf buf) {
            return new JobProblems(buf.readUUID(), buf.readUUID(), buf.readUtf(), buf.readUtf(), buf.readResourceLocation(),
                    buf.readVarInt(), readList(buf, 1024, ProblemRow::decode));
        }
    }

    /** Client -> server: buy {@code bundles} bundles of an item; or, with no item, order everything the queue lacks. */
    public record ShopBuy(UUID building, Optional<ResourceLocation> item, int bundles) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(building);
            buf.writeOptional(item, FriendlyByteBuf::writeResourceLocation);
            buf.writeVarInt(bundles);
        }

        public static ShopBuy decode(FriendlyByteBuf buf) {
            return new ShopBuy(buf.readUUID(), buf.readOptional(FriendlyByteBuf::readResourceLocation), buf.readVarInt());
        }
    }
}
