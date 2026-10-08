package com.maidbuilder.common.territory;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.maidbuilder.common.MaterialContainers;
import com.maidbuilder.common.maid.BuilderMaidData;
import com.maidbuilder.common.maid.TaskDeposit;
import com.maidbuilder.common.maid.WorkplaceMaidData;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Maids working at territory buildings (a convenience; it does not count for prosperity):
 * assigning them, taking their products to the warehouse, and the warehouse lookups that needs.
 * Pausing when the backpack is full lives in {@link com.maidbuilder.common.maid.WorkplaceHandler}.
 */
public final class WorkplaceActions {
    private WorkplaceActions() {
    }

    public static void handle(ServerPlayer player, Territory territory, TerritoryPayloads.Action action, @Nullable UUID maidId,
                              @Nullable UUID buildingId) {
        EntityMaid maid = TerritoryActions.ownedMaid(player, territory, maidId);
        if (maid == null) return;
        switch (action) {
            case ASSIGN -> assign(player, territory, maid, buildingId);
            case UNASSIGN -> {
                unassign(player.server, maid);
                player.displayClientMessage(Component.translatable("message.maidbuilder.workplace.unassigned", maid.getDisplayName()), true);
            }
            case DEPOSIT -> startDeposit(player, territory, maid);
            case RESUME -> resume(maid);
            default -> {
            }
        }
    }

    // ---- assigning ----

    @Nullable
    public static TemplateDef.Workplace workplaceOf(RegisteredBuilding building) {
        TemplateDef def = TemplateRegistry.get(building.templateId());
        return def == null ? null : def.workplace().orElse(null);
    }

    /** Puts the maid to work at the building: its task, her work point at the building (home mode on). */
    public static boolean assign(ServerPlayer player, Territory territory, EntityMaid maid, @Nullable UUID buildingId) {
        RegisteredBuilding building = buildingId == null ? null : territory.building(buildingId);
        TemplateDef.Workplace workplace = building == null ? null : workplaceOf(building);
        if (building == null || workplace == null) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.workplace.not_workplace"), true);
            return false;
        }
        if (!building.isWorking()) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.workplace.damaged"), true);
            return false;
        }
        if (!building.assignedMaids().contains(maid.getUUID()) && building.assignedMaids().size() >= workplace.maxMaids()) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.workplace.full", workplace.maxMaids()), true);
            return false;
        }
        Optional<IMaidTask> task = TaskManager.findTask(workplace.task());
        if (task.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.workplace.no_task", workplace.task().toString()), true);
            return false;
        }
        release(player.server, maid);
        if (BuilderMaidData.jobOf(maid) != null) BuilderMaidData.unbind(maid);
        territory.assignMaid(building, maid.getUUID());
        WorkplaceMaidData.set(maid, new WorkplaceMaidData(Optional.of(territory.id()), Optional.of(building.id()), Optional.empty(), false,
                Optional.empty(), Optional.empty()));
        maid.setTask(task.get());
        BlockPos center = building.center();
        if (!maid.isHomeModeEnable()) {
            maid.getSchedulePos().setHomeModeEnable(maid, center);
            maid.setHomeModeEnable(true);
        }
        maid.getSchedulePos().setWorkPos(center);
        maid.getSchedulePos().restrictTo(maid);
        player.displayClientMessage(Component.translatable("message.maidbuilder.workplace.assigned", maid.getDisplayName(),
                Component.translatable(TerritoryTicker.nameKey(building.templateId()))), true);
        return true;
    }

    /** Where a maid works from: her home work point in home mode, else where she stands. */
    public static BlockPos workSpot(EntityMaid maid) {
        BlockPos work = maid.isHomeModeEnable() ? maid.getSchedulePos().getWorkPos() : null;
        return work != null ? work : maid.blockPosition();
    }

    /** Whether {@code pos} is at the building (its box, plus a small margin). */
    public static boolean isAt(RegisteredBuilding building, BlockPos pos) {
        return building.box().inflate(AT_MARGIN).contains(net.minecraft.world.phys.Vec3.atCenterOf(pos));
    }

    private static final int AT_MARGIN = 2;

    /**
     * A maid the player set up by hand (the building's work task, working from the building) is
     * registered at that building, if it works and has room. Returns true if she was.
     */
    public static boolean detect(ServerLevel level, EntityMaid maid) {
        UUID owner = maid.getOwnerUUID();
        if (owner == null || !WorkplaceMaidData.of(maid).building().isEmpty()) return false;
        BlockPos spot = workSpot(maid);
        Territory territory = TerritoryManager.activeAt(level.getServer(), level.dimension(), spot);
        if (territory == null || !territory.isOwnedBy(owner)) return false;
        ResourceLocation task = maid.getTask().getUid();
        for (RegisteredBuilding building : territory.buildings()) {
            TemplateDef.Workplace workplace = workplaceOf(building);
            if (workplace == null || !workplace.task().equals(task) || !building.isWorking() || !isAt(building, spot)) continue;
            if (building.assignedMaids().size() >= workplace.maxMaids()) continue;
            territory.assignMaid(building, maid.getUUID());
            WorkplaceMaidData.set(maid, new WorkplaceMaidData(Optional.of(territory.id()), Optional.of(building.id()), Optional.empty(), false,
                    Optional.empty(), Optional.empty()));
            return true;
        }
        return false;
    }

    /** Takes the maid off any building she works at (her task stays as it is). */
    public static void release(MinecraftServer server, EntityMaid maid) {
        WorkplaceMaidData data = WorkplaceMaidData.of(maid);
        if (data.building().isEmpty() && !data.depositing() && data.savedSchedule().isEmpty()) return;
        restoreSchedule(maid, data);
        Territory territory = data.territory().map(id -> TerritoryManager.data(server).get(id)).orElse(null);
        RegisteredBuilding building = territory == null || data.building().isEmpty() ? null : territory.building(data.building().get());
        if (building != null) territory.unassignMaid(building, maid.getUUID());
        WorkplaceMaidData.set(maid, WorkplaceMaidData.EMPTY);
    }

    /** Takes the maid off her building and lets her rest. */
    public static void unassign(MinecraftServer server, EntityMaid maid) {
        release(server, maid);
        maid.setTask(TaskManager.getIdleTask());
    }

    /** Puts back the schedule points depositing borrowed. */
    public static void restoreSchedule(EntityMaid maid, WorkplaceMaidData data) {
        if (data.savedSchedule().isEmpty() || data.savedSchedule().get().size() != 3) return;
        List<BlockPos> saved = data.savedSchedule().get();
        maid.getSchedulePos().setWorkPos(saved.get(0));
        maid.getSchedulePos().setIdlePos(saved.get(1));
        maid.getSchedulePos().setSleepPos(saved.get(2));
        maid.getSchedulePos().restrictTo(maid);
    }

    /** The building is being unregistered: its maids stop working there. */
    static void onBuildingRemoved(MinecraftServer server, Territory territory, RegisteredBuilding building) {
        ServerLevel level = server.getLevel(territory.dimension());
        for (UUID id : List.copyOf(building.assignedMaids())) {
            Entity entity = level == null ? null : level.getEntity(id);
            if (entity instanceof EntityMaid maid) unassign(server, maid);
            else territory.unassignMaid(building, id);
        }
    }

    @Nullable
    public static RegisteredBuilding buildingOf(Territory territory, EntityMaid maid) {
        WorkplaceMaidData data = WorkplaceMaidData.of(maid);
        if (data.building().isEmpty() || !data.territory().map(territory.id()::equals).orElse(false)) return null;
        return territory.building(data.building().get());
    }

    public static boolean isPaused(EntityMaid maid) {
        return WorkplaceMaidData.of(maid).paused();
    }

    public static boolean isDepositing(EntityMaid maid) {
        return WorkplaceMaidData.of(maid).depositing();
    }

    /** Puts a resting maid back to work. */
    public static void resume(EntityMaid maid) {
        WorkplaceMaidData data = WorkplaceMaidData.of(maid);
        if (!data.paused()) return;
        Optional<IMaidTask> task = TaskManager.findTask(ResourceLocation.parse(data.pausedTask().get()));
        WorkplaceMaidData.set(maid, data.withPausedTask(null));
        task.ifPresent(maid::setTask);
    }

    // ---- depositing ----

    /** A working storage building whose containers take products. */
    public static boolean hasWarehouse(Territory territory) {
        for (RegisteredBuilding b : territory.buildings()) {
            TemplateDef def = TemplateRegistry.get(b.templateId());
            if (def != null && def.storage() && b.isWorking()) return true;
        }
        return false;
    }

    public static boolean startDeposit(ServerPlayer player, Territory territory, EntityMaid maid) {
        WorkplaceMaidData data = WorkplaceMaidData.of(maid);
        if (data.building().isEmpty() || !data.territory().map(territory.id()::equals).orElse(false)) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.workplace.not_working_here"), true);
            return false;
        }
        if (!hasWarehouse(territory)) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.deposit.no_warehouse", maid.getDisplayName(), 0), true);
            return false;
        }
        if (data.depositing()) return false;
        String back = data.paused() ? data.pausedTask().get() : maid.getTask().getUid().toString();
        WorkplaceMaidData.set(maid, data.withPausedTask(null).withDeposit(true, back));
        TaskManager.findTask(TaskDeposit.UID).ifPresent(maid::setTask);
        return true;
    }

    /** What the maid's workplace counts as products, and how many of each she keeps. */
    @Nullable
    private static TemplateDef.Workplace workplace(MinecraftServer server, EntityMaid maid) {
        WorkplaceMaidData data = WorkplaceMaidData.of(maid);
        Territory territory = data.territory().map(id -> TerritoryManager.data(server).get(id)).orElse(null);
        RegisteredBuilding building = territory == null || data.building().isEmpty() ? null : territory.building(data.building().get());
        return building == null ? null : workplaceOf(building);
    }

    private static Predicate<ItemStack> products(TemplateDef.Workplace workplace) {
        List<Predicate<ItemStack>> matchers = new ArrayList<>();
        for (String key : workplace.products()) {
            try {
                matchers.add(TerritoryUpgrades.matcher(key));
            } catch (RuntimeException ignored) {
                // a bad id in a data pack: skip it
            }
        }
        return stack -> {
            if (stack.isEmpty()) return false;
            for (Predicate<ItemStack> m : matchers) if (m.test(stack)) return true;
            return false;
        };
    }

    /** Product items beyond what she keeps, per item. */
    private static Map<Item, Integer> surplus(EntityMaid maid, TemplateDef.Workplace workplace) {
        Predicate<ItemStack> isProduct = products(workplace);
        IItemHandler backpack = maid.getAvailableBackpackInv();
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < backpack.getSlots(); i++) {
            ItemStack s = backpack.getStackInSlot(i);
            if (isProduct.test(s)) counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        counts.replaceAll((item, n) -> n - workplace.keep());
        counts.values().removeIf(n -> n <= 0);
        return counts;
    }

    public static boolean hasProducts(EntityMaid maid) {
        TemplateDef.Workplace workplace = maid.getServer() == null ? null : workplace(maid.getServer(), maid);
        return workplace != null && !surplus(maid, workplace).isEmpty();
    }

    /** Containers of the territory's working warehouses (each once, keyed by position). */
    public static Map<BlockPos, IItemHandler> warehouseContainers(ServerLevel level, Territory territory) {
        List<BlockPos> positions = new ArrayList<>();
        for (RegisteredBuilding b : territory.buildings()) {
            TemplateDef def = TemplateRegistry.get(b.templateId());
            if (def == null || !def.storage() || !b.isWorking()) continue;
            for (BlockPos pos : BlockPos.betweenClosed(b.min(), b.max())) {
                if (level.isLoaded(pos) && MaterialContainers.isContainer(level, pos)) positions.add(pos.immutable());
            }
        }
        return MaterialContainers.distinct(level, positions);
    }

    /** The nearest warehouse container (not in {@code exclude}) with room for at least one of her products. */
    @Nullable
    public static BlockPos nearestWarehouseContainer(ServerLevel level, EntityMaid maid, Set<BlockPos> exclude) {
        WorkplaceMaidData data = WorkplaceMaidData.of(maid);
        Territory territory = data.territory().map(id -> TerritoryManager.data(level.getServer()).get(id)).orElse(null);
        TemplateDef.Workplace workplace = workplace(level.getServer(), maid);
        if (territory == null || workplace == null) return null;
        Map<Item, Integer> surplus = surplus(maid, workplace);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, IItemHandler> e : warehouseContainers(level, territory).entrySet()) {
            if (exclude.contains(e.getKey()) || !hasRoom(e.getValue(), surplus)) continue;
            double d = maid.distanceToSqr(Vec3.atCenterOf(e.getKey()));
            if (d < bestDistance) {
                best = e.getKey();
                bestDistance = d;
            }
        }
        return best;
    }

    private static boolean hasRoom(IItemHandler handler, Map<Item, Integer> surplus) {
        for (Item item : surplus.keySet()) {
            ItemStack one = new ItemStack(item);
            if (ItemHandlerHelper.insertItemStacked(handler, one, true).isEmpty()) return true;
        }
        return false;
    }

    /** Moves her surplus products into the container at {@code pos}; returns how many items moved. */
    public static int depositInto(ServerLevel level, EntityMaid maid, BlockPos pos) {
        TemplateDef.Workplace workplace = workplace(level.getServer(), maid);
        IItemHandler container = MaterialContainers.handler(level, pos);
        if (workplace == null || container == null) return 0;
        Map<Item, Integer> wanted = new HashMap<>(surplus(maid, workplace));
        return MaterialContainers.transfer(maid.getAvailableBackpackInv(), container, wanted);
    }

    public static void notifyOwner(EntityMaid maid, Component message) {
        if (maid.getServer() == null || maid.getOwnerUUID() == null) return;
        ServerPlayer owner = maid.getServer().getPlayerList().getPlayer(maid.getOwnerUUID());
        if (owner != null) owner.displayClientMessage(message, false);
    }
}
