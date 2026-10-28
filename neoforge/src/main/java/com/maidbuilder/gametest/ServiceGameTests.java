package com.maidbuilder.gametest;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.territory.BuildingIntegrity;
import com.maidbuilder.common.territory.RegisteredBuilding;
import com.maidbuilder.common.territory.TemplateDef;
import com.maidbuilder.common.territory.TemplateRegistry;
import com.maidbuilder.common.territory.Territory;
import com.maidbuilder.common.territory.TerritoryBonuses;
import com.maidbuilder.common.territory.TerritoryLevels;
import com.maidbuilder.common.territory.TerritoryManager;
import com.maidbuilder.common.territory.ShopActions;
import com.maidbuilder.common.territory.ShopCatalog;
import com.maidbuilder.common.territory.TravelActions;
import com.maidbuilder.common.territory.WorkplaceActions;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;

import static com.maidbuilder.gametest.TerritoryGameTests.FLOOR;
import static com.maidbuilder.gametest.TerritoryGameTests.clearNear;
import static com.maidbuilder.gametest.TerritoryGameTests.clearOwnedBy;
import static com.maidbuilder.gametest.TerritoryGameTests.flagTerritory;
import static com.maidbuilder.gametest.TerritoryGameTests.instantBuilding;
import static com.maidbuilder.gametest.TerritoryGameTests.player;
import static com.maidbuilder.gametest.TerritoryGameTests.spawnMaid;

/** The service buildings: trading post (traders, travel), village hall, watchtower, canteen and shrine. Each in its own batch. */
@GameTestHolder(MaidBuilder.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ServiceGameTests {
    /** Ticks for the service check (every 40 ticks) to have run at least once. */
    private static final int CHECK = 81;

    private ServiceGameTests() {
    }

    /** A 5x3x5 service building: plank floor, a log post in one corner and, if asked, a cartography table in the other. */
    static TemplateRegistry.Entry serviceTemplate(String path, String bonus, boolean table) {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder(path, IntPos.ZERO, new IntPos(5, 3, 5));
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) rb.set(x, 0, z, "minecraft:oak_planks");
        for (int y = 1; y < 3; y++) rb.set(0, y, 0, "minecraft:oak_log[axis=y]");
        if (table) rb.set(4, 1, 4, "minecraft:cartography_table");
        Schematic schematic = LitematicWriter.schematic(path, 3955, List.of(rb.build()));
        TemplateDef def = new TemplateDef(MaidBuilder.id("test/" + path), MaidBuilder.id(path), "service", path, 1, 5,
                bonus, false, Optional.empty(), ResourceLocation.withDefaultNamespace("oak_planks"), 0);
        return TemplateRegistry.registerForTest(def, schematic);
    }

    /** Removes enough of the floor (relative origin {@code rel}) that the building stops working. */
    static void damage(GameTestHelper helper, Territory t, RegisteredBuilding b, BlockPos rel) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 2; z++) helper.setBlock(rel.offset(x, 0, z), Blocks.AIR);
        BuildingIntegrity.checkNow(helper.getLevel().getServer(), t, b);
        if (b.isWorking()) helper.fail("building should be damaged: " + b.integrity());
    }

    static void repair(GameTestHelper helper, Territory t, RegisteredBuilding b, BlockPos rel) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 2; z++) helper.setBlock(rel.offset(x, 0, z), Blocks.OAK_PLANKS);
        BuildingIntegrity.checkNow(helper.getLevel().getServer(), t, b);
        if (!b.isWorking()) helper.fail("building should work again: " + b.integrity());
    }

    static <T extends Entity> List<T> around(GameTestHelper helper, Class<T> type, AABB box) {
        return helper.getLevel().getEntitiesOfClass(type, box, Entity::isAlive);
    }

    static void discard(GameTestHelper helper, AABB box, Class<?>... types) {
        for (Class<?> type : types) {
            for (Entity e : helper.getLevel().getEntitiesOfClass(Entity.class, box, type::isInstance)) e.discard();
        }
    }

    // ---- trading post ----

    /**
     * A working trading post gets a trader with two llamas on its leash, who stays around it for
     * the configured time; no second trader while one is there; a damaged post gets none.
     */
    @GameTest(template = FLOOR, batch = "service_trader", timeoutTicks = 600)
    public static void tradingPostTraderComes(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_trader_owner");
        clearOwnedBy(server, owner.getUUID());
        Territory t = flagTerritory(helper, owner);
        BlockPos rel = new BlockPos(5, 2, 5);
        RegisteredBuilding post = instantBuilding(helper, owner, t, serviceTemplate("trader_post", TemplateDef.BONUS_TRADING_POST, false), rel);
        AABB area = post.box().inflate(24);
        discard(helper, area, WanderingTrader.class, TraderLlama.class);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (around(helper, WanderingTrader.class, area).size() != 1) helper.fail("no trader yet");
                })
                .thenExecute(() -> {
                    WanderingTrader trader = around(helper, WanderingTrader.class, area).getFirst();
                    if (!post.box().inflate(1).contains(trader.position())) helper.fail("trader arrived outside the post: " + trader.position());
                    if (trader.getDespawnDelay() > MaidBuilderConfig.TRADING_POST_STAY.get()) helper.fail("stays " + trader.getDespawnDelay());
                    List<TraderLlama> llamas = around(helper, TraderLlama.class, area);
                    if (llamas.size() != 2 || llamas.stream().anyMatch(l -> l.getLeashHolder() != trader)) {
                        helper.fail("expected two llamas on the trader's leash, got " + llamas.size());
                    }
                    if (post.nextEvent() <= helper.getLevel().getGameTime()) helper.fail("next trader not scheduled");
                    post.setNextEvent(0); // due again, but the trader is still here
                })
                .thenIdle(CHECK)
                .thenExecute(() -> {
                    if (around(helper, WanderingTrader.class, area).size() != 1) helper.fail("a second trader came");
                    discard(helper, area, WanderingTrader.class, TraderLlama.class);
                    damage(helper, t, post, rel);
                    post.setNextEvent(0);
                })
                .thenIdle(CHECK)
                .thenExecute(() -> {
                    if (!around(helper, WanderingTrader.class, area).isEmpty()) helper.fail("a damaged post got a trader");
                    clearOwnedBy(server, owner.getUUID());
                })
                .thenSucceed();
    }

    /**
     * Using the cartography table of a working post opens the travel menu instead of the table
     * (sneaking uses the table as usual); a trip costs one emerald and takes the player to the other post;
     * without an emerald, away from the post or to a damaged post it fails.
     */
    @GameTest(template = FLOOR, batch = "service_travel")
    public static void travelBetweenTradingPosts(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = player(helper, "mb_travel_owner");
        clearOwnedBy(server, player.getUUID());
        try {
            Territory t = flagTerritory(helper, player);
            BlockPos relA = new BlockPos(1, 2, 1), relB = new BlockPos(9, 2, 7);
            RegisteredBuilding a = instantBuilding(helper, player, t, serviceTemplate("post_a", TemplateDef.BONUS_TRADING_POST, true), relA);
            RegisteredBuilding b = instantBuilding(helper, player, t, serviceTemplate("post_b", TemplateDef.BONUS_TRADING_POST, true), relB);
            Vec3 atA = Vec3.atBottomCenterOf(helper.absolutePos(relA.offset(2, 1, 2)));
            player.moveTo(atA.x, atA.y, atA.z);

            BlockPos table = helper.absolutePos(relA.offset(4, 1, 4));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(table).add(0, 0.5, 0), Direction.UP, table, false);
            if (!rightClick(player, table, hit)) helper.fail("using the table did not open the travel menu");
            player.setShiftKeyDown(true);
            if (rightClick(player, table, hit)) helper.fail("sneaking should use the cartography table as usual");
            player.setShiftKeyDown(false);

            if (TravelActions.travel(player, a.id(), b.id()) != null) helper.fail("travelled without paying");
            player.getInventory().add(new ItemStack(Items.EMERALD, 2));
            BlockPos arrived = TravelActions.travel(player, a.id(), b.id());
            if (arrived == null) helper.fail("could not travel to the other post");
            if (!b.contains(arrived)) helper.fail("arrived at " + arrived + ", not in " + b.box());
            if (player.getInventory().countItem(Items.EMERALD) != 1) helper.fail("trip should cost one emerald");
            // (a fake player's connection ignores teleports, so she is still at the first post)
            Vec3 away = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(15, 2, 0)));
            player.moveTo(away.x, away.y, away.z);
            if (TravelActions.travel(player, a.id(), b.id()) != null) helper.fail("travelled from a post she is not at");
            Vec3 atB = Vec3.atBottomCenterOf(arrived);
            player.moveTo(atB.x, atB.y, atB.z);
            damage(helper, t, a, relA);
            if (TravelActions.travel(player, b.id(), a.id()) != null) helper.fail("travelled to a damaged post");
            if (player.getInventory().countItem(Items.EMERALD) != 1) helper.fail("a failed trip cost an emerald");
        } finally {
            clearOwnedBy(server, player.getUUID());
        }
        helper.succeed();
    }

    /** Fires the player's right click on a block; true if something (the travel menu) took it over. */
    static boolean rightClick(ServerPlayer player, BlockPos pos, BlockHitResult hit) {
        return NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit)).isCanceled();
    }

    // ---- village hall ----

    /** Villagers move in one at a time while the hall works, up to the limit. */
    @GameTest(template = FLOOR, batch = "service_villagers", timeoutTicks = 800)
    public static void villageHallVillagersMoveIn(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_hall_owner");
        clearOwnedBy(server, owner.getUUID());
        Territory t = flagTerritory(helper, owner);
        RegisteredBuilding hall = instantBuilding(helper, owner, t, serviceTemplate("hall", TemplateDef.BONUS_VILLAGE_HALL, false), new BlockPos(5, 2, 5));
        AABB area = hall.box().inflate(16);
        discard(helper, area, Villager.class);
        int cap = MaidBuilderConfig.VILLAGE_HALL_CAP.get();
        var seq = helper.startSequence();
        for (int n = 1; n <= cap; n++) {
            int expected = n;
            seq.thenWaitUntil(() -> {
                if (around(helper, Villager.class, area).size() != expected) helper.fail("waiting for villager " + expected);
            }).thenExecute(() -> {
                if (hall.nextEvent() <= helper.getLevel().getGameTime()) helper.fail("next villager not scheduled");
                hall.setNextEvent(0);
            });
        }
        seq.thenIdle(CHECK)
                .thenExecute(() -> {
                    int count = around(helper, Villager.class, area).size();
                    if (count != cap) helper.fail(count + " villagers, limit " + cap);
                    discard(helper, area, Villager.class);
                    clearOwnedBy(server, owner.getUUID());
                })
                .thenSucceed();
    }

    // ---- watchtower ----

    /** Hostile mobs near a working watchtower glow, other mobs do not; a damaged tower shows nothing. */
    @GameTest(template = FLOOR, batch = "service_watchtower", timeoutTicks = 400)
    public static void watchtowerMakesMonstersGlow(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_tower_owner");
        clearOwnedBy(server, owner.getUUID());
        Territory t = flagTerritory(helper, owner);
        BlockPos rel = new BlockPos(2, 2, 2);
        RegisteredBuilding tower = instantBuilding(helper, owner, t, serviceTemplate("tower", TemplateDef.BONUS_WATCHTOWER, false), rel);
        Creeper creeper = helper.spawn(EntityType.CREEPER, new BlockPos(12, 2, 12));
        creeper.setNoAi(true);
        Cow cow = helper.spawn(EntityType.COW, new BlockPos(12, 2, 4));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!creeper.hasEffect(MobEffects.GLOWING)) helper.fail("creeper does not glow");
                })
                .thenExecute(() -> {
                    if (cow.hasEffect(MobEffects.GLOWING)) helper.fail("the cow glows");
                    damage(helper, t, tower, rel);
                    creeper.removeEffect(MobEffects.GLOWING);
                })
                .thenIdle(CHECK)
                .thenExecute(() -> {
                    if (creeper.hasEffect(MobEffects.GLOWING)) helper.fail("a damaged tower still marks monsters");
                    creeper.discard();
                    cow.discard();
                    clearOwnedBy(server, owner.getUUID());
                })
                .thenSucceed();
    }

    // ---- canteen ----

    /**
     * A working canteen heals the owner's maids even below the maid_regen level; a damaged one
     * does not; with both, they heal twice as fast.
     */
    @GameTest(template = FLOOR, batch = "service_canteen", timeoutTicks = 4000)
    public static void canteenHealsMaids(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_canteen_owner");
        clearOwnedBy(server, owner.getUUID());
        Territory t = flagTerritory(helper, owner);
        if (t.hasBonus(TerritoryBonuses.MAID_REGEN)) helper.fail("level 1 should not have maid_regen");
        BlockPos rel = new BlockPos(2, 2, 2);
        RegisteredBuilding canteen = instantBuilding(helper, owner, t, serviceTemplate("canteen", TemplateDef.BONUS_CANTEEN, false), rel);
        EntityMaid maid = spawnMaid(helper, owner, new BlockPos(10, 2, 10));
        int window = MaidBuilderConfig.MAID_REGEN_INTERVAL.get() * 6;
        float[] start = new float[1];
        Runnable reset = () -> {
            maid.setHealth(4);
            start[0] = maid.getHealth();
        };
        helper.startSequence()
                .thenExecute(reset)
                .thenIdle(window + 2)
                .thenExecute(() -> {
                    float gained = maid.getHealth() - start[0];
                    if (gained < 6) helper.fail("canteen: healed only " + gained);
                    damage(helper, t, canteen, rel);
                    reset.run();
                })
                .thenIdle(window + 2)
                .thenExecute(() -> {
                    float gained = maid.getHealth() - start[0];
                    // TLM alone: about 1.5 on average over this window
                    if (gained >= 7) helper.fail("damaged canteen: healed " + gained);
                    repair(helper, t, canteen, rel);
                    TerritoryManager.setLevel(server, t, TerritoryLevels.table().bonusLevel(TerritoryBonuses.MAID_REGEN));
                    reset.run();
                })
                .thenIdle(window + 2)
                .thenExecute(() -> {
                    float gained = maid.getHealth() - start[0];
                    if (gained < 12) helper.fail("canteen and maid_regen: healed only " + gained);
                    maid.discard();
                    clearOwnedBy(server, owner.getUUID());
                })
                .thenSucceed();
    }

    // ---- shrine ----

    /** With a working shrine the owner's maids gain favorability once a day; another player's maid does not. */
    @GameTest(template = FLOOR, batch = "service_shrine", timeoutTicks = 1200)
    public static void shrineRaisesFavorability(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_shrine_owner");
        ServerPlayer stranger = player(helper, "mb_shrine_stranger");
        clearOwnedBy(server, owner.getUUID());
        Territory t = flagTerritory(helper, owner);
        instantBuilding(helper, owner, t, serviceTemplate("shrine", TemplateDef.BONUS_SHRINE, false), new BlockPos(2, 2, 2));
        EntityMaid maid = spawnMaid(helper, owner, new BlockPos(10, 2, 10));
        EntityMaid other = spawnMaid(helper, stranger, new BlockPos(12, 2, 10));
        int points = TerritoryBonuses.SHRINE_PRAYER.getPoint();
        int[] before = {maid.getFavorability(), other.getFavorability()};
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (maid.getFavorability() != before[0] + points) helper.fail("favorability " + maid.getFavorability());
                })
                .thenIdle(450)
                .thenExecute(() -> {
                    if (maid.getFavorability() != before[0] + points) helper.fail("gained again the same day: " + maid.getFavorability());
                    if (other.getFavorability() != before[1]) helper.fail("another player's maid gained favorability");
                    maid.discard();
                    other.discard();
                    clearOwnedBy(server, owner.getUUID());
                })
                .thenSucceed();
    }

    // ---- building supplies shop ----

    /** A service template like {@link #serviceTemplate} with a stonecutter (the shop desk) and room for one clerk. */
    static TemplateRegistry.Entry shopTemplate(String path) {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder(path, IntPos.ZERO, new IntPos(5, 3, 5));
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) rb.set(x, 0, z, "minecraft:oak_planks");
        for (int y = 1; y < 3; y++) rb.set(0, y, 0, "minecraft:oak_log[axis=y]");
        rb.set(4, 1, 4, "minecraft:stonecutter[facing=north]");
        Schematic schematic = LitematicWriter.schematic(path, 3955, List.of(rb.build()));
        TemplateDef def = new TemplateDef(MaidBuilder.id("test/" + path), MaidBuilder.id(path), "service", path, 1, 5,
                TemplateDef.BONUS_SHOP, false, Optional.of(new TemplateDef.Workplace(ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "idle"),
                1, List.of(), 0)), ResourceLocation.withDefaultNamespace("emerald"), 0);
        return TemplateRegistry.registerForTest(def, schematic);
    }

    static void buy(ServerPlayer player, RegisteredBuilding shop, Item item, int bundles) {
        ShopActions.handle(player, new TerritoryPayloads.ShopBuy(shop.id(), Optional.of(BuiltInRegistries.ITEM.getKey(item)), bundles));
    }

    /** The built-in shelves name only real items; the basics are on sale at the agreed price, a few things never are. */
    @GameTest(template = FLOOR, batch = "service_shop_catalog")
    public static void shopCatalogLoads(GameTestHelper helper) {
        if (!ShopCatalog.unknownEntries().isEmpty()) helper.fail("unknown shop entries: " + ShopCatalog.unknownEntries());
        if (ShopCatalog.offers().size() < 250) helper.fail("only " + ShopCatalog.offers().size() + " goods");
        ShopCatalog.Offer planks = ShopCatalog.offer(Items.SPRUCE_PLANKS);
        if (planks == null || planks.price() != 2 || planks.count() != 16 || planks.requiredLevel() != 2) helper.fail("spruce planks: " + planks);
        for (Item item : List.of(Items.STONE_BRICK_STAIRS, Items.CYAN_TERRACOTTA, Items.LANTERN, Items.OAK_LEAVES, Items.QUARTZ_BLOCK)) {
            if (ShopCatalog.offer(item) == null) helper.fail(item + " is not sold");
        }
        for (Item item : List.of(Items.BELL, Items.DIAMOND_BLOCK, Items.EMERALD, Items.BEACON)) {
            if (ShopCatalog.offer(item) != null) helper.fail(item + " should not be sold");
        }
        helper.succeed();
    }

    /**
     * The shop sells only while its clerk is in; a bundle costs its price in emeralds; goods above
     * the territory level are locked; each item sells at most its daily limit until the next day;
     * the stonecutter opens the shop unless the player sneaks.
     */
    @GameTest(template = FLOOR, batch = "service_shop")
    public static void shopSellsWithClerk(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = player(helper, "mb_shop_owner");
        clearOwnedBy(server, player.getUUID());
        long dayTime = helper.getLevel().getDayTime();
        try {
            Territory t = flagTerritory(helper, player);
            TerritoryManager.setLevel(server, t, 2);
            BlockPos rel = new BlockPos(4, 2, 4);
            RegisteredBuilding shop = instantBuilding(helper, player, t, shopTemplate("shop"), rel);
            Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(rel.offset(2, 1, 1)));
            player.moveTo(at.x, at.y, at.z);
            player.getInventory().add(new ItemStack(Items.EMERALD, 4));

            buy(player, shop, Items.OAK_PLANKS, 1);
            if (player.getInventory().countItem(Items.OAK_PLANKS) != 0) helper.fail("sold without a clerk");
            EntityMaid clerk = spawnMaid(helper, player, rel.offset(2, 1, 3));
            if (!WorkplaceActions.assign(player, t, clerk, shop.id())) helper.fail("clerk not assigned");

            BlockPos desk = helper.absolutePos(rel.offset(4, 1, 4));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(desk), Direction.UP, desk, false);
            if (!rightClick(player, desk, hit)) helper.fail("the stonecutter did not open the shop");
            player.setShiftKeyDown(true);
            if (rightClick(player, desk, hit)) helper.fail("sneaking should use the stonecutter as usual");
            player.setShiftKeyDown(false);

            buy(player, shop, Items.OAK_PLANKS, 1);
            if (player.getInventory().countItem(Items.OAK_PLANKS) != 16) helper.fail("bought " + player.getInventory().countItem(Items.OAK_PLANKS) + " planks");
            if (player.getInventory().countItem(Items.EMERALD) != 2) helper.fail("a bundle should cost 2 emeralds");
            buy(player, shop, Items.CYAN_TERRACOTTA, 1);
            if (player.getInventory().countItem(Items.CYAN_TERRACOTTA) != 0) helper.fail("sold goods above the territory level");
            buy(player, shop, Items.OAK_PLANKS, 5);
            if (player.getInventory().countItem(Items.OAK_PLANKS) != 32 || player.getInventory().countItem(Items.EMERALD) != 0) {
                helper.fail("should buy only what the emeralds pay for");
            }

            player.getAbilities().instabuild = true; // free, to reach the daily limit
            int limit = ShopCatalog.offer(Items.OAK_PLANKS).dailyLimit();
            buy(player, shop, Items.OAK_PLANKS, 64);
            if (player.getInventory().countItem(Items.OAK_PLANKS) != limit) helper.fail("daily limit " + limit + ", got " + player.getInventory().countItem(Items.OAK_PLANKS));
            buy(player, shop, Items.OAK_PLANKS, 1);
            if (player.getInventory().countItem(Items.OAK_PLANKS) != limit) helper.fail("sold above the daily limit");
            helper.getLevel().setDayTime(dayTime + 24000);
            buy(player, shop, Items.OAK_PLANKS, 1);
            if (player.getInventory().countItem(Items.OAK_PLANKS) != limit + 16) helper.fail("no new stock the next day");

            clerk.discard();
            buy(player, shop, Items.SPRUCE_PLANKS, 1);
            if (player.getInventory().countItem(Items.SPRUCE_PLANKS) != 0) helper.fail("sold after the clerk left");
        } finally {
            helper.getLevel().setDayTime(dayTime);
            clearOwnedBy(server, player.getUUID());
        }
        helper.succeed();
    }

    /** "Order all" buys what the queued jobs lack into the territory's material chest, and nothing once nothing is missing. */
    @GameTest(template = FLOOR, batch = "service_shop_order")
    public static void shopOrdersMissingMaterials(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = player(helper, "mb_order_owner");
        clearOwnedBy(server, player.getUUID());
        try {
            Territory t = flagTerritory(helper, player);
            TerritoryManager.setLevel(server, t, 2);
            BlockPos rel = new BlockPos(2, 2, 2);
            RegisteredBuilding shop = instantBuilding(helper, player, t, shopTemplate("order_shop"), rel);
            EntityMaid clerk = spawnMaid(helper, player, rel.offset(2, 1, 3));
            WorkplaceActions.assign(player, t, clerk, shop.id());
            BlockPos chestRel = new BlockPos(12, 2, 2);
            helper.setBlock(chestRel, Blocks.CHEST);
            t.addMaterialSource(helper.absolutePos(chestRel));
            // a queued job needing 9 planks and a log
            if (TerritoryGameTests.placeTemplate(helper, player, t, TerritoryGameTests.testTemplate("order_hut", 1, 1, "order_hut", "production"),
                    new BlockPos(10, 2, 8)) == null) helper.fail("job not queued");
            Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(rel.offset(2, 1, 1)));
            player.moveTo(at.x, at.y, at.z);
            player.getInventory().add(new ItemStack(Items.EMERALD, 10));

            ShopActions.handle(player, new TerritoryPayloads.ShopBuy(shop.id(), Optional.empty(), 0));
            var chest = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, helper.absolutePos(chestRel), null);
            if (TerritoryGameTests.count(chest, Items.OAK_PLANKS) != 16 || TerritoryGameTests.count(chest, Items.OAK_LOG) != 16) {
                helper.fail("chest holds " + TerritoryGameTests.count(chest, Items.OAK_PLANKS) + " planks, "
                        + TerritoryGameTests.count(chest, Items.OAK_LOG) + " logs");
            }
            if (player.getInventory().countItem(Items.EMERALD) != 6) helper.fail("order should cost 4 emeralds");
            ShopActions.handle(player, new TerritoryPayloads.ShopBuy(shop.id(), Optional.empty(), 0));
            if (player.getInventory().countItem(Items.EMERALD) != 6) helper.fail("ordered again though nothing is missing");
            clerk.discard();
        } finally {
            clearOwnedBy(server, player.getUUID());
        }
        helper.succeed();
    }
}
