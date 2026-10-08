package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.MaterialContainers;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.core.territory.LevelDef;
import com.maidbuilder.core.territory.TerritoryRules;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Level upgrades (conditions plus handed-in items), building repairs and unregistering buildings. */
public final class TerritoryUpgrades {
    private TerritoryUpgrades() {
    }

    // ---- items ----

    /** An upgrade item key: an item id, or {@code #tag}. */
    static Predicate<ItemStack> matcher(String key) {
        if (key.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(key.substring(1)));
            return stack -> stack.is(tag);
        }
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(key));
        return stack -> !stack.isEmpty() && stack.is(item);
    }

    /** An item to show for a key (the first item of a tag). */
    static Item icon(String key) {
        if (key.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM, ResourceLocation.parse(key.substring(1)));
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) return holder.value();
            return Items.BARRIER;
        }
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(key) == null ? ResourceLocation.withDefaultNamespace("air")
                : ResourceLocation.parse(key));
        return item == Items.AIR ? Items.BARRIER : item;
    }

    private static List<IItemHandler> containers(MinecraftServer server, Territory territory) {
        ServerLevel level = server.getLevel(territory.dimension());
        if (level == null) return List.of();
        return new ArrayList<>(MaterialContainers.distinct(level, territory.materialSources()).values());
    }

    private static int count(Inventory inventory, List<IItemHandler> containers, Predicate<ItemStack> matcher) {
        int n = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (matcher.test(s)) n += s.getCount();
        }
        for (IItemHandler h : containers) {
            for (int i = 0; i < h.getSlots(); i++) {
                ItemStack s = h.getStackInSlot(i);
                if (matcher.test(s)) n += s.getCount();
            }
        }
        return n;
    }

    /** Takes {@code amount} matching items, the player's inventory first. */
    private static void take(Inventory inventory, List<IItemHandler> containers, Predicate<ItemStack> matcher, int amount) {
        for (int i = 0; i < inventory.getContainerSize() && amount > 0; i++) {
            ItemStack s = inventory.getItem(i);
            if (!matcher.test(s)) continue;
            int n = Math.min(amount, s.getCount());
            s.shrink(n);
            amount -= n;
        }
        for (IItemHandler h : containers) {
            for (int i = 0; i < h.getSlots() && amount > 0; i++) {
                if (!matcher.test(h.getStackInSlot(i))) continue;
                amount -= h.extractItem(i, amount, false).getCount();
            }
        }
        inventory.setChanged();
    }

    /** The next level's items with what the player and the territory's containers have. */
    public static List<TerritoryPayloads.ItemRow> itemRows(ServerPlayer player, Territory territory) {
        LevelDef next = TerritoryLevels.table().next(territory.level());
        if (next == null) return List.of();
        List<IItemHandler> containers = containers(player.server, territory);
        List<TerritoryPayloads.ItemRow> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted(next.upgradeItems())) {
            try {
                rows.add(new TerritoryPayloads.ItemRow(e.getKey(), icon(e.getKey()),
                        count(player.getInventory(), containers, matcher(e.getKey())), e.getValue()));
            } catch (RuntimeException ex) {
                MaidBuilder.LOGGER.warn("Bad upgrade item {} in territory level {}", e.getKey(), next.level());
            }
        }
        return rows;
    }

    private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> items) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(items.entrySet());
        list.sort(Map.Entry.comparingByKey());
        return list;
    }

    /** Whether the territory can go up a level now (conditions met and items at hand). */
    public static boolean canUpgrade(ServerPlayer player, Territory territory) {
        LevelDef next = TerritoryLevels.table().next(territory.level());
        if (next == null || !territory.isActive()) return false;
        if (!TerritoryRules.allMet(TerritoryRules.upgradeConditions(next, territory.prosperity(), territory.builtBuildings()))) return false;
        for (TerritoryPayloads.ItemRow row : itemRows(player, territory)) if (row.have() < row.need()) return false;
        return itemRows(player, territory).size() == next.upgradeItems().size();
    }

    public static boolean upgrade(ServerPlayer player, Territory territory) {
        if (!canUpgrade(player, territory)) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.upgrade_not_ready"), true);
            return false;
        }
        LevelDef next = TerritoryLevels.table().next(territory.level());
        List<IItemHandler> containers = containers(player.server, territory);
        for (Map.Entry<String, Integer> e : next.upgradeItems().entrySet()) {
            take(player.getInventory(), containers, matcher(e.getKey()), e.getValue());
        }
        TerritoryManager.setLevel(player.server, territory, next.level());
        player.level().playSound(null, territory.flagPos(), SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1f, 1f);
        player.sendSystemMessage(Component.translatable("message.maidbuilder.territory.upgraded", next.level(), next.radius()));
        return true;
    }

    // ---- buildings ----

    /** Whether a repair job of the building is already queued. */
    public static boolean repairQueued(MinecraftServer server, Territory territory, UUID building) {
        for (UUID id : territory.jobQueue()) {
            BuildJob job = BuildJobManager.data(server).get(id);
            if (job != null && building.equals(job.buildingId()) && !job.isCancelled()) return true;
        }
        return false;
    }

    /** Queues a job building the template again where the building stands; blocks already right are skipped. */
    public static boolean repair(ServerPlayer player, Territory territory, @Nullable UUID buildingId) {
        RegisteredBuilding building = buildingId == null ? null : territory.building(buildingId);
        if (building == null || !territory.isActive() || repairQueued(player.server, territory, building.id())) return false;
        try {
            String hash = building.schematicHash();
            BuildJob job = new BuildJob(UUID.randomUUID(), player.getUUID(), player.getGameProfile().getName(), building.templateId(), hash,
                    territory.dimension(), building.origin(), building.rotation(), building.mirror(),
                    com.maidbuilder.MaidBuilderConfig.PLACE_FLUIDS.get(), null);
            job.useDefaultClearing();
            job.limitClearingToReplace(); // a repair must not clear what was added inside
            job.ensureLoaded(player.server);
            BuildJobManager.data(player.server).add(job);
            job.setTerritory(territory.id(), building.templateId(), building.id());
            territory.enqueue(job.id());
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.repair_queued",
                    Component.translatable(TerritoryTicker.nameKey(building.templateId()))), true);
            return true;
        } catch (IOException e) {
            MaidBuilder.LOGGER.error("Cannot queue a repair of building {}", building.shortId(), e);
            return false;
        }
    }

    /** Forgets a building (its blocks stay); its maids stop working there. */
    public static boolean unregister(ServerPlayer player, Territory territory, @Nullable UUID buildingId) {
        RegisteredBuilding building = buildingId == null ? null : territory.building(buildingId);
        if (building == null) return false;
        for (UUID id : List.copyOf(territory.jobQueue())) {
            BuildJob job = BuildJobManager.data(player.server).get(id);
            if (job != null && building.id().equals(job.buildingId())) {
                territory.dequeue(id);
                BuildJobManager.cancel(player.server, job);
            }
        }
        WorkplaceActions.onBuildingRemoved(player.server, territory, building);
        territory.removeBuilding(building.id());
        player.displayClientMessage(Component.translatable("message.maidbuilder.territory.building_removed",
                Component.translatable(TerritoryTicker.nameKey(building.templateId()))), true);
        return true;
    }
}
