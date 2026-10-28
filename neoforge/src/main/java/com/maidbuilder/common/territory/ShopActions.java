package com.maidbuilder.common.territory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.MaterialContainers;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.common.maid.BuilderMaidData;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The building supplies shop: using the stonecutter of a working shop (not sneaking) while its clerk
 * (a maid assigned to the building) is there opens the shop. Goods come from {@link ShopCatalog},
 * are paid in emeralds from the player's inventory (free in creative mode) and are limited per item
 * and day (day time / 24000, so sleeping through the night starts a new day). "Order what is missing"
 * buys what the territory's queued jobs still lack and puts it into the territory's material
 * containers.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class ShopActions {
    /** How far from the shop's box the player may be when buying. */
    private static final double RANGE = 6;
    /**
     * How far from the shop's box its clerk may be: TLM lets a working maid stroll about her work point
     * (12 blocks by default). When her schedule sends her off to rest or sleep, the shop is closed.
     */
    private static final double CLERK_RANGE = 12;
    private static final int MAX_BUNDLES = 64;

    private ShopActions() {
    }

    /** A shop with its territory. */
    record Shop(Territory territory, RegisteredBuilding building) {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.isSecondaryUseActive() || !MaidBuilderConfig.SHOP.get()) return;
        if (!event.getLevel().getBlockState(event.getPos()).is(Blocks.STONECUTTER)) return;
        Territory territory = TerritoryManager.data(player.server).at(player.level().dimension(), event.getPos());
        if (territory == null || !territory.isOwnedBy(player.getUUID())) return;
        RegisteredBuilding shop = ServiceBuildings.workingAt(territory, event.getPos(), TemplateDef.BONUS_SHOP);
        if (shop == null) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        open(player, new Shop(territory, shop), true);
    }

    /** Sends the shop to the player (opening the screen, or refreshing it); false if the clerk is not there. */
    static boolean open(ServerPlayer player, Shop shop, boolean openScreen) {
        ServerLevel level = player.serverLevel();
        EntityMaid clerk = clerk(level, shop.building());
        if (clerk == null) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.shop.no_clerk"), true);
            return false;
        }
        PacketDistributor.sendToPlayer(player, menu(level, shop, clerk, openScreen));
        return true;
    }

    /** The shop's clerk if she is at the shop: a maid assigned to the building, alive and near it. */
    @Nullable
    public static EntityMaid clerk(ServerLevel level, RegisteredBuilding building) {
        for (UUID id : building.assignedMaids()) {
            Entity e = level.getEntity(id);
            if (e instanceof EntityMaid maid && maid.isAlive() && building.box().inflate(CLERK_RANGE).contains(maid.position())) return maid;
        }
        return null;
    }

    public static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    /** How many of the offer's item the shop still sells today. */
    public static int left(ServerLevel level, RegisteredBuilding building, ShopCatalog.Offer offer) {
        return Math.max(0, offer.dailyLimit() - building.soldOn(day(level), key(offer.item())));
    }

    private static String key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    static TerritoryPayloads.ShopMenu menu(ServerLevel level, Shop shop, EntityMaid clerk, boolean openScreen) {
        List<TerritoryPayloads.ShopRow> rows = new ArrayList<>();
        for (ShopCatalog.Offer offer : ShopCatalog.offers().values()) {
            rows.add(new TerritoryPayloads.ShopRow(BuiltInRegistries.ITEM.getKey(offer.item()), offer.category(), offer.price(), offer.count(),
                    left(level, shop.building(), offer), offer.requiredLevel()));
        }
        List<TerritoryPayloads.MissingRow> missing = new ArrayList<>();
        int cost = 0;
        for (Order order : plan(level, shop)) {
            missing.add(new TerritoryPayloads.MissingRow(BuiltInRegistries.ITEM.getKey(order.item()), order.needed(), order.bundles(), order.status()));
            if (order.offer() != null) cost += order.bundles() * order.offer().price();
        }
        return new TerritoryPayloads.ShopMenu(shop.building().id(), shop.territory().level(), clerk.getDisplayName().getString(), rows, missing,
                cost, openScreen);
    }

    // ---- buying ----

    public static void handle(ServerPlayer player, TerritoryPayloads.ShopBuy msg) {
        Shop shop = shop(player, msg.building());
        if (shop == null) return;
        if (msg.item().isPresent()) {
            Item item = BuiltInRegistries.ITEM.get(msg.item().get());
            buy(player, shop, item, msg.bundles());
        } else {
            orderMissing(player, shop);
        }
        open(player, shop, false);
    }

    /** The player's working shop, if the player is at it and its clerk is there (else null, with a message). */
    @Nullable
    static Shop shop(ServerPlayer player, UUID buildingId) {
        if (!MaidBuilderConfig.SHOP.get()) return null;
        for (Territory t : TerritoryManager.data(player.server).ownedBy(player.getUUID())) {
            RegisteredBuilding b = t.building(buildingId);
            if (b == null) continue;
            if (!t.isActive() || !b.isWorking() || !TemplateDef.BONUS_SHOP.equals(ServiceBuildings.bonusOf(b))) break;
            if (!player.level().dimension().equals(t.dimension()) || !b.box().inflate(RANGE).contains(player.position())) {
                player.displayClientMessage(Component.translatable("message.maidbuilder.shop.too_far"), true);
                return null;
            }
            if (clerk(player.serverLevel(), b) == null) {
                player.displayClientMessage(Component.translatable("message.maidbuilder.shop.no_clerk"), true);
                return null;
            }
            return new Shop(t, b);
        }
        player.displayClientMessage(Component.translatable("message.maidbuilder.shop.closed"), true);
        return null;
    }

    /** Buys {@code bundles} bundles of the item into the player's inventory; returns how many items were bought. */
    static int buy(ServerPlayer player, Shop shop, Item item, int bundles) {
        ShopCatalog.Offer offer = ShopCatalog.offer(item);
        if (offer == null || bundles < 1) return 0;
        if (shop.territory().level() < offer.requiredLevel()) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.shop.locked", offer.requiredLevel()), true);
            return 0;
        }
        ServerLevel level = player.serverLevel();
        bundles = Math.min(Math.min(bundles, MAX_BUNDLES), left(level, shop.building(), offer) / offer.count());
        if (bundles < 1) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.shop.sold_out"), true);
            return 0;
        }
        bundles = Math.min(bundles, affordable(player, offer.price()));
        if (bundles < 1) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.shop.no_money", offer.price()), true);
            return 0;
        }
        pay(player, bundles * offer.price());
        int amount = bundles * offer.count();
        sell(level, shop, offer, amount);
        give(player, new ItemStack(item, amount));
        level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_TRADE, SoundSource.NEUTRAL, 0.6f, 1.2f);
        return amount;
    }

    /** What ordering the missing materials would buy, item by item. */
    record Order(Item item, int needed, int bundles, TerritoryPayloads.MissingStatus status, @Nullable ShopCatalog.Offer offer) {
    }

    static List<Order> plan(ServerLevel level, Shop shop) {
        List<Order> result = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : missing(level, shop.territory()).entrySet()) {
            Item item = e.getKey();
            int needed = e.getValue();
            ShopCatalog.Offer offer = ShopCatalog.offer(item);
            if (offer == null) {
                result.add(new Order(item, needed, 0, TerritoryPayloads.MissingStatus.NOT_SOLD, null));
            } else if (shop.territory().level() < offer.requiredLevel()) {
                result.add(new Order(item, needed, 0, TerritoryPayloads.MissingStatus.LOCKED, offer));
            } else {
                int want = (needed + offer.count() - 1) / offer.count();
                int bundles = Math.min(want, left(level, shop.building(), offer) / offer.count());
                result.add(new Order(item, needed, bundles, bundles < want ? TerritoryPayloads.MissingStatus.SOLD_OUT
                        : TerritoryPayloads.MissingStatus.OK, offer));
            }
        }
        return result;
    }

    /**
     * Buys what the territory's queue lacks (as far as stock and money go, in the queue's order) and
     * puts it into the territory's material containers (what does not fit goes to the player).
     */
    static int orderMissing(ServerPlayer player, Shop shop) {
        ServerLevel level = player.serverLevel();
        List<IItemHandler> containers = new ArrayList<>(MaterialContainers.distinct(level, shop.territory().materialSources()).values());
        int items = 0, kinds = 0, spent = 0, skipped = 0;
        for (Order order : plan(level, shop)) {
            if (order.offer() == null || order.bundles() < 1) {
                skipped++;
                continue;
            }
            int bundles = Math.min(order.bundles(), affordable(player, order.offer().price()));
            if (bundles < 1) {
                skipped++;
                continue;
            }
            if (bundles < order.bundles() || order.status() != TerritoryPayloads.MissingStatus.OK) skipped++;
            pay(player, bundles * order.offer().price());
            spent += bundles * order.offer().price();
            int amount = bundles * order.offer().count();
            sell(level, shop, order.offer(), amount);
            ItemStack rest = new ItemStack(order.item(), amount);
            for (IItemHandler handler : containers) {
                if (rest.isEmpty()) break;
                rest = ItemHandlerHelper.insertItemStacked(handler, rest, false);
            }
            if (!rest.isEmpty()) give(player, rest);
            items += amount;
            kinds++;
        }
        if (kinds > 0) level.playSound(null, player.blockPosition(), SoundEvents.VILLAGER_TRADE, SoundSource.NEUTRAL, 0.6f, 1.2f);
        player.sendSystemMessage(kinds > 0 ? Component.translatable("message.maidbuilder.shop.ordered", kinds, items, spent, skipped)
                : Component.translatable("message.maidbuilder.shop.nothing_ordered", skipped));
        return items;
    }

    /**
     * What the territory's queued jobs still need minus what is already there for them: in the
     * territory's and the jobs' material containers, and with the maids building those jobs.
     */
    public static Map<Item, Integer> missing(ServerLevel level, Territory territory) {
        MinecraftServer server = level.getServer();
        Map<Item, Integer> needed = new LinkedHashMap<>();
        Set<BlockPos> sources = new HashSet<>(territory.materialSources());
        Set<UUID> jobs = new HashSet<>();
        for (UUID id : territory.jobQueue()) {
            BuildJob job = BuildJobManager.loaded(server, id).orElse(null);
            if (job == null || job.isFinished()) continue;
            job.remainingMaterials().forEach((item, n) -> needed.merge(item, n, Integer::sum));
            sources.addAll(job.materialSources());
            jobs.add(job.id());
        }
        Map<Item, Integer> have = new LinkedHashMap<>();
        for (IItemHandler handler : MaterialContainers.distinct(level, sources).values()) MaterialContainers.count(handler, have);
        for (EntityMaid maid : TerritoryMaids.of(level, territory)) {
            UUID job = BuilderMaidData.jobOf(maid);
            if (job != null && jobs.contains(job)) MaterialContainers.count(maid.getAvailableInv(false), have);
        }
        needed.replaceAll((item, n) -> n - have.getOrDefault(item, 0));
        needed.values().removeIf(n -> n <= 0);
        return needed;
    }

    // ---- money and goods ----

    private static int affordable(ServerPlayer player, int price) {
        if (player.getAbilities().instabuild) return MAX_BUNDLES;
        return player.getInventory().countItem(Items.EMERALD) / price;
    }

    private static void pay(ServerPlayer player, int emeralds) {
        if (player.getAbilities().instabuild || emeralds <= 0) return;
        player.getInventory().clearOrCountMatchingItems(s -> s.is(Items.EMERALD), emeralds, player.inventoryMenu.getCraftSlots());
    }

    private static void sell(ServerLevel level, Shop shop, ShopCatalog.Offer offer, int amount) {
        shop.building().recordSale(day(level), key(offer.item()), amount);
        TerritoryManager.data(level.getServer()).setDirty();
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        while (!stack.isEmpty()) {
            ItemStack part = stack.split(stack.getMaxStackSize());
            if (!player.getInventory().add(part)) player.drop(part, false);
        }
    }
}
