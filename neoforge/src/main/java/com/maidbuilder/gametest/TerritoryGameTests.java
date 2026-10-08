package com.maidbuilder.gametest;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.common.maid.BuilderMaidData;
import com.maidbuilder.common.maid.TaskBuilder;
import com.maidbuilder.common.territory.BuildingIntegrity;
import com.maidbuilder.common.territory.RegisteredBuilding;
import com.maidbuilder.common.territory.TemplateDef;
import com.maidbuilder.common.territory.TemplatePlacement;
import com.maidbuilder.common.territory.TemplateRegistry;
import com.maidbuilder.common.territory.Territory;
import com.maidbuilder.common.territory.TerritoryActions;
import com.maidbuilder.common.territory.TerritoryBonuses;
import com.maidbuilder.common.territory.TerritoryReports;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import com.maidbuilder.common.territory.TerritoryData;
import com.maidbuilder.common.territory.TerritoryLevels;
import com.maidbuilder.common.territory.TerritoryManager;
import com.maidbuilder.common.territory.TerritoryTicker;
import com.maidbuilder.common.territory.TerritoryUpgrades;
import com.maidbuilder.common.wand.MaterialReports;
import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.territory.TerritoryRules;
import com.maidbuilder.init.ModBlocks;
import com.maidbuilder.init.ModItems;
import com.maidbuilder.network.TerritoryPayloads;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Territory game tests. Territories reach far beyond a test structure and flags must be far apart,
 * so every test runs in a batch of its own and clears territories near it before starting.
 */
@GameTestHolder(MaidBuilder.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerritoryGameTests {
    static final String FLOOR = "floor16";

    private TerritoryGameTests() {
    }

    // ---- helpers ----

    static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        player.getInventory().clearContent();
        return player;
    }

    /** Removes territories (left over by a failed run) whose flag is near the test. */
    static void clearNear(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BlockPos center = helper.absolutePos(new BlockPos(8, 1, 8));
        for (Territory t : List.copyOf(TerritoryManager.data(server).all())) {
            if (t.dimension().equals(helper.getLevel().dimension())
                    && TerritoryRules.flagDistance(t.flagPos().getX(), t.flagPos().getZ(), center.getX(), center.getZ()) < 400) {
                TerritoryManager.remove(server, t);
            }
        }
    }

    static void clearOwnedBy(MinecraftServer server, UUID owner) {
        for (Territory t : TerritoryManager.data(server).ownedBy(owner)) TerritoryManager.remove(server, t);
    }

    /** Places a flag with the item, as the player would, on top of {@code ground} (relative). */
    static boolean useFlag(GameTestHelper helper, ServerPlayer player, BlockPos groundRel) {
        BlockPos ground = helper.absolutePos(groundRel);
        ItemStack stack = new ItemStack(ModItems.TERRITORY_FLAG.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false);
        return player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit)).consumesAction()
                && helper.getLevel().getBlockState(ground.above()).is(ModBlocks.TERRITORY_FLAG.get());
    }

    /** A territory created directly (no block), e.g. far away to fill up a player's flag slots. */
    static Territory claim(GameTestHelper helper, ServerPlayer player, BlockPos absolute) {
        MinecraftServer server = helper.getLevel().getServer();
        TerritoryManager.PlacementCheck check = TerritoryManager.check(server, player.getUUID(), helper.getLevel().dimension(), absolute);
        if (!check.ok()) throw new IllegalStateException("cannot claim " + absolute + ": " + check.error().getString());
        return TerritoryManager.place(server, player, helper.getLevel().dimension(), absolute, check);
    }

    /** A tiny template: 3x3 planks with a log on top (10 blocks). */
    static TemplateRegistry.Entry testTemplate(String path, int requiredLevel, int prosperity, String group, String category) {
        return testTemplate(path, requiredLevel, prosperity, group, category, false);
    }

    /** Same, optionally with a torch in a corner (a decoration: not counted for integrity). */
    static TemplateRegistry.Entry testTemplate(String path, int requiredLevel, int prosperity, String group, String category, boolean torch) {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder(path, IntPos.ZERO, new IntPos(3, 2, 3));
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) rb.set(x, 0, z, "minecraft:oak_planks");
        rb.set(1, 1, 1, "minecraft:oak_log[axis=y]");
        if (torch) rb.set(0, 1, 0, "minecraft:torch");
        Schematic schematic = LitematicWriter.schematic(path, 3955, List.of(rb.build()));
        TemplateDef def = new TemplateDef(MaidBuilder.id("test/" + path), MaidBuilder.id(path), category, group, requiredLevel, prosperity,
                "", false, Optional.empty(), ResourceLocation.withDefaultNamespace("oak_planks"), 0);
        return TemplateRegistry.registerForTest(def, schematic);
    }

    static EntityMaid spawnMaid(GameTestHelper helper, ServerPlayer owner, BlockPos rel) {
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), rel);
        maid.setSchedule(MaidSchedule.ALL);
        maid.setTame(true, false);
        maid.setOwnerUUID(owner.getUUID());
        maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
        maid.setHomeModeEnable(true);
        return maid;
    }

    @Nullable
    static BuildJob placeTemplate(GameTestHelper helper, ServerPlayer player, Territory territory, TemplateRegistry.Entry entry, BlockPos rel) {
        return TemplatePlacement.place(player, new TerritoryPayloads.PlaceTemplate(territory.id(), entry.def().id(), helper.absolutePos(rel),
                Rotation.NONE, Mirror.NONE));
    }

    /** Builds a queued job instantly (no maid) and marks every step done. */
    static void buildInstantly(GameTestHelper helper, BuildJob job) {
        for (int i = 0; i < job.size(); i++) {
            if (job.status(i) == BuildJob.FAILED) continue;
            if (!BlockPlacer.matches(helper.getLevel().getBlockState(job.pos(i)), job.target(i))) {
                BlockPlacer.placeExact(helper.getLevel(), job.pos(i), job.target(i));
            }
            job.setStatus(i, BuildJob.DONE);
        }
    }

    /** Queues the template, builds it instantly and lets the territory register it. */
    static RegisteredBuilding instantBuilding(GameTestHelper helper, ServerPlayer owner, Territory t, TemplateRegistry.Entry entry, BlockPos rel) {
        BuildJob job = placeTemplate(helper, owner, t, entry, rel);
        if (job == null) throw new IllegalStateException("template not queued at " + rel);
        buildInstantly(helper, job);
        TerritoryTicker.updateQueue(helper.getLevel().getServer(), t);
        for (RegisteredBuilding b : t.buildings()) if (b.origin().equals(helper.absolutePos(rel))) return b;
        throw new IllegalStateException("building not registered");
    }

    static int count(IItemHandler inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getSlots(); i++) if (inv.getStackInSlot(i).is(item)) n += inv.getStackInSlot(i).getCount();
        return n;
    }

    // ---- G2 ----

    /**
     * A finished template becomes a working building worth its prosperity; decorations do not count
     * for integrity; under 80% it needs repair and stops counting; a queued repair fixes it; an
     * unregistered building frees its spot.
     */
    @GameTest(template = FLOOR, batch = "territory_integrity")
    public static void buildingIntegrityAndRepair(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_integrity_owner");
        clearOwnedBy(server, owner.getUUID());
        try {
            if (!useFlag(helper, owner, new BlockPos(1, 1, 14))) helper.fail("flag was not placed");
            Territory t = TerritoryManager.data(server).atFlag(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 2, 14)));
            TemplateRegistry.Entry entry = testTemplate("deco_hut", 1, 9, "test_deco_hut", "production", true);
            RegisteredBuilding b = instantBuilding(helper, owner, t, entry, new BlockPos(5, 2, 5));
            if (!t.jobQueue().isEmpty()) helper.fail("finished job still queued");
            if (!b.isWorking() || b.integrity() != 100) helper.fail("new building not working: " + b.integrity());
            if (t.prosperity() != 9) helper.fail("prosperity " + t.prosperity() + ", expected 9");

            helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR); // the torch
            BuildingIntegrity.checkNow(server, t, b);
            if (!b.isWorking() || b.integrity() != 100) helper.fail("removing a decoration changed integrity: " + b.integrity());

            helper.setBlock(new BlockPos(5, 2, 5), Blocks.AIR);
            helper.setBlock(new BlockPos(6, 2, 5), Blocks.AIR);
            BuildingIntegrity.checkNow(server, t, b);
            if (!b.isWorking() || b.integrity() != 80) helper.fail("8 of 10 should still work: " + b.integrity());
            helper.setBlock(new BlockPos(7, 2, 5), Blocks.STONE);
            BuildingIntegrity.checkNow(server, t, b);
            if (b.isWorking() || b.integrity() != 70) helper.fail("7 of 10 should need repair: " + b.integrity() + " " + b.isWorking());
            if (t.prosperity() != 0) helper.fail("a damaged building still counts: " + t.prosperity());

            if (!TerritoryUpgrades.repair(owner, t, b.id())) helper.fail("repair not queued");
            if (TerritoryUpgrades.repair(owner, t, b.id())) helper.fail("a second repair was queued");
            BuildJob repair = BuildJobManager.data(server).get(t.jobQueue().getFirst());
            if (repair == null || !b.id().equals(repair.buildingId())) helper.fail("queued job is not the repair");
            helper.setBlock(new BlockPos(7, 2, 5), Blocks.AIR); // the player clears what is in the way
            buildInstantly(helper, repair);
            TerritoryTicker.updateQueue(server, t);
            if (t.buildings().size() != 1) helper.fail("a repair registered a new building");
            if (!b.isWorking() || b.integrity() != 100) helper.fail("not working after the repair: " + b.integrity());
            if (t.prosperity() != 9) helper.fail("prosperity not back: " + t.prosperity());

            if (!TerritoryUpgrades.unregister(owner, t, b.id()) || !t.buildings().isEmpty()) helper.fail("building not unregistered");
            if (placeTemplate(helper, owner, t, entry, new BlockPos(5, 2, 5)) == null) helper.fail("an unregistered building's spot is still taken");
        } finally {
            clearOwnedBy(server, owner.getUUID());
        }
        helper.succeed();
    }

    /** A maid in home mode inside the territory is a resident (and counts for prosperity) until home mode is off. */
    @GameTest(template = FLOOR, batch = "territory_residents", timeoutTicks = 800)
    public static void residentsComeAndGo(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_resident_owner");
        clearOwnedBy(server, owner.getUUID());
        if (!useFlag(helper, owner, new BlockPos(1, 1, 14))) helper.fail("flag was not placed");
        Territory t = TerritoryManager.data(server).atFlag(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 2, 14)));
        EntityMaid maid = spawnMaid(helper, owner, new BlockPos(8, 2, 8));
        int perResident = TerritoryLevels.table().get(1).residentPoints();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!t.residents().containsKey(maid.getUUID())) helper.fail("maid not a resident yet");
                })
                .thenExecute(() -> {
                    if (t.prosperity() != perResident) helper.fail("resident prosperity " + t.prosperity() + ", expected " + perResident);
                    maid.setHomeModeEnable(false);
                })
                .thenWaitUntil(() -> {
                    if (t.residents().containsKey(maid.getUUID())) helper.fail("maid still a resident");
                })
                .thenExecute(() -> {
                    if (t.prosperity() != 0) helper.fail("prosperity " + t.prosperity() + " without residents");
                    clearOwnedBy(server, owner.getUUID());
                })
                .thenSucceed();
    }

    /**
     * Upgrading needs the conditions and the items (player inventory plus territory containers),
     * hands the items in, widens the territory and unlocks templates of the new level.
     */
    @GameTest(template = FLOOR, batch = "territory_upgrade")
    public static void upgradeHandsInItemsAndUnlocks(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_upgrade_owner");
        clearOwnedBy(server, owner.getUUID());
        try {
            if (!useFlag(helper, owner, new BlockPos(1, 1, 14))) helper.fail("flag was not placed");
            Territory t = TerritoryManager.data(server).atFlag(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 2, 14)));
            var level2 = TerritoryLevels.table().get(2);
            TemplateRegistry.Entry store = testTemplate("store", 1, level2.prosperity(), "warehouse", "storage");
            TemplateRegistry.Entry level2Hut = testTemplate("level2_hut", 2, 1, "test_hut", "production");
            if (placeTemplate(helper, owner, t, level2Hut, new BlockPos(10, 2, 3)) != null) helper.fail("level 2 template placed at level 1");
            if (TerritoryUpgrades.upgrade(owner, t)) helper.fail("upgraded without meeting the conditions");

            instantBuilding(helper, owner, t, store, new BlockPos(3, 2, 3));
            if (TerritoryUpgrades.canUpgrade(owner, t)) helper.fail("upgrade possible without items");

            BlockPos chestRel = new BlockPos(7, 2, 12);
            helper.setBlock(chestRel, Blocks.CHEST);
            BlockPos chestAbs = helper.absolutePos(chestRel);
            IItemHandler chest = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, chestAbs, null);
            TerritoryActions.toggleSource(owner, t, chestAbs);
            // the default level 2 asks for 64 logs (any kind: a tag) and 16 iron
            owner.getInventory().add(new ItemStack(Items.OAK_LOG, 40));
            ItemHandlerHelper.insertItemStacked(chest, new ItemStack(Items.BIRCH_LOG, 30), false);
            ItemHandlerHelper.insertItemStacked(chest, new ItemStack(Items.IRON_INGOT, 16), false);
            var rows = TerritoryUpgrades.itemRows(owner, t);
            if (rows.size() != level2.upgradeItems().size()) helper.fail("item rows " + rows);
            if (!TerritoryUpgrades.canUpgrade(owner, t)) helper.fail("cannot upgrade: " + rows);
            if (!TerritoryUpgrades.upgrade(owner, t)) helper.fail("upgrade failed");
            if (t.level() != 2 || t.radius() != level2.radius()) helper.fail("level " + t.level() + " radius " + t.radius());
            if (owner.getInventory().countItem(Items.OAK_LOG) != 0 || count(chest, Items.BIRCH_LOG) != 40 + 30 - 64) {
                helper.fail("logs left: inventory " + owner.getInventory().countItem(Items.OAK_LOG) + ", chest " + count(chest, Items.BIRCH_LOG));
            }
            if (count(chest, Items.IRON_INGOT) != 0) helper.fail("iron not taken");
            if (placeTemplate(helper, owner, t, level2Hut, new BlockPos(10, 2, 3)) == null) helper.fail("level 2 template still locked");
        } finally {
            clearOwnedBy(server, owner.getUUID());
        }
        helper.succeed();
    }

    // ---- G3 ----

    /**
     * A 5x4x5 test building with a range bonus: a planks floor with farmland in the middle and a log
     * post in one corner (so the building box is 4 high).
     */
    static TemplateRegistry.Entry bonusTemplate(String path, String bonus) {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder(path, IntPos.ZERO, new IntPos(5, 4, 5));
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) rb.set(x, 0, z, "minecraft:oak_planks");
        rb.set(2, 0, 2, "minecraft:farmland[moisture=7]");
        for (int y = 1; y < 4; y++) rb.set(0, y, 0, "minecraft:oak_log[axis=y]");
        Schematic schematic = LitematicWriter.schematic(path, 3955, List.of(rb.build()));
        TemplateDef def = new TemplateDef(MaidBuilder.id("test/" + path), MaidBuilder.id(path), "production", path, 1, 1,
                bonus, false, Optional.empty(), ResourceLocation.withDefaultNamespace("oak_planks"), 0);
        return TemplateRegistry.registerForTest(def, schematic);
    }

    static Territory flagTerritory(GameTestHelper helper, ServerPlayer owner) {
        if (!useFlag(helper, owner, new BlockPos(1, 1, 14))) throw new IllegalStateException("flag was not placed");
        return TerritoryManager.data(helper.getLevel().getServer()).atFlag(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 2, 14)));
    }

    static MobSpawnEvent.PositionCheck.Result spawnCheck(GameTestHelper helper, net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> type,
                                                        BlockPos absolute, MobSpawnType spawnType) {
        var mob = type.create(helper.getLevel());
        mob.moveTo(absolute.getX() + 0.5, absolute.getY(), absolute.getZ() + 0.5);
        return NeoForge.EVENT_BUS.post(new MobSpawnEvent.PositionCheck(mob, helper.getLevel(), spawnType, null)).getResult();
    }

    /** Hostile mobs stop spawning naturally once the level grants the bonus; animals, spawners and other land are unaffected. */
    @GameTest(template = FLOOR, batch = "territory_bonus_spawn")
    public static void noHostileSpawnsAtLevel(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_spawn_owner");
        clearOwnedBy(server, owner.getUUID());
        try {
            Territory t = flagTerritory(helper, owner);
            BlockPos inside = helper.absolutePos(new BlockPos(8, 2, 8));
            var deny = MobSpawnEvent.PositionCheck.Result.FAIL;
            if (spawnCheck(helper, EntityType.ZOMBIE, inside, MobSpawnType.NATURAL) == deny) helper.fail("blocked before the bonus level");
            int level = TerritoryLevels.table().bonusLevel(TerritoryBonuses.NO_HOSTILE_SPAWNS);
            if (level < 1) helper.fail("no level grants " + TerritoryBonuses.NO_HOSTILE_SPAWNS);
            TerritoryManager.setLevel(server, t, level);
            if (spawnCheck(helper, EntityType.ZOMBIE, inside, MobSpawnType.NATURAL) != deny) helper.fail("zombie not blocked");
            if (spawnCheck(helper, EntityType.SKELETON, inside, MobSpawnType.CHUNK_GENERATION) != deny) helper.fail("skeleton not blocked");
            if (spawnCheck(helper, EntityType.COW, inside, MobSpawnType.NATURAL) == deny) helper.fail("cow blocked");
            if (spawnCheck(helper, EntityType.ZOMBIE, inside, MobSpawnType.SPAWNER) == deny) helper.fail("spawner blocked by default");
            BlockPos outside = t.flagPos().offset(t.radius() + 3, 0, 0);
            if (spawnCheck(helper, EntityType.ZOMBIE, outside, MobSpawnType.NATURAL) == deny) helper.fail("blocked outside the territory");
            helper.getLevel().destroyBlock(t.flagPos(), false);
            if (spawnCheck(helper, EntityType.ZOMBIE, inside, MobSpawnType.NATURAL) == deny) helper.fail("blocked after the flag was removed");
        } finally {
            clearOwnedBy(server, owner.getUUID());
        }
        helper.succeed();
    }

    /**
     * Hurt maids of the owner heal inside the territory once the level grants the bonus: at least one
     * half-heart per interval, on top of TLM's own slow random healing (about one per 400 ticks).
     */
    @GameTest(template = FLOOR, batch = "territory_bonus_regen", timeoutTicks = 3000)
    public static void maidsHealAtLevel(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_regen_owner");
        clearOwnedBy(server, owner.getUUID());
        Territory t = flagTerritory(helper, owner);
        EntityMaid maid = spawnMaid(helper, owner, new BlockPos(8, 2, 8));
        int interval = com.maidbuilder.MaidBuilderConfig.MAID_REGEN_INTERVAL.get();
        int window = interval * 6;
        float[] start = new float[1];
        helper.startSequence()
                .thenExecute(() -> {
                    maid.setHealth(4);
                    start[0] = maid.getHealth();
                })
                .thenIdle(window)
                .thenExecute(() -> {
                    float gained = maid.getHealth() - start[0];
                    // TLM alone: about 1.5 on average over this window; 7 or more would be the bonus (or very unlikely luck)
                    if (gained >= 7) helper.fail("healed " + gained + " before the bonus level");
                    TerritoryManager.setLevel(server, t, TerritoryLevels.table().bonusLevel(TerritoryBonuses.MAID_REGEN));
                    maid.setHealth(4);
                    start[0] = maid.getHealth();
                })
                .thenIdle(window + 2)
                .thenExecute(() -> {
                    float gained = maid.getHealth() - start[0];
                    if (gained < 6) helper.fail("healed only " + gained + " in " + window + " ticks at the bonus level");
                    clearOwnedBy(server, owner.getUUID());
                })
                .thenSucceed();
    }

    /** Crops in a working greenhouse building grow noticeably faster at the bonus level; elsewhere at the usual rate. */
    @GameTest(template = FLOOR, batch = "territory_bonus_crop")
    public static void greenhouseCropsGrowFaster(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_crop_owner");
        clearOwnedBy(server, owner.getUUID());
        try {
            Territory t = flagTerritory(helper, owner);
            TerritoryManager.setLevel(server, t, TerritoryLevels.table().bonusLevel(TerritoryBonuses.CROP_GROWTH));
            RegisteredBuilding greenhouse = instantBuilding(helper, owner, t, bonusTemplate("greenhouse_plot", TemplateDef.BONUS_GREENHOUSE),
                    new BlockPos(3, 2, 3));
            BlockPos inside = new BlockPos(5, 3, 5);
            BlockPos outside = new BlockPos(11, 3, 5);
            helper.setBlock(outside.below(), Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7));
            helper.setBlock(new BlockPos(5, 3, 4), Blocks.GLOWSTONE);
            helper.setBlock(new BlockPos(11, 3, 4), Blocks.GLOWSTONE);
            if (!greenhouse.contains(helper.absolutePos(inside))) helper.fail("crop not inside the building box");
            int grownInside = grow(helper, inside, 400), grownOutside = grow(helper, outside, 400);
            if (grownInside < grownOutside + 40) helper.fail("greenhouse " + grownInside + " vs outside " + grownOutside + " of 400");
            // a damaged greenhouse gives no bonus
            helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(4, 2, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(5, 2, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(6, 2, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(7, 2, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(3, 2, 4), Blocks.AIR);
            BuildingIntegrity.checkNow(server, t, greenhouse);
            if (greenhouse.isWorking()) helper.fail("greenhouse should be damaged: " + greenhouse.integrity());
            int grownDamaged = grow(helper, inside, 400);
            if (grownDamaged > grownOutside + 30) helper.fail("damaged greenhouse still boosts: " + grownDamaged + " vs " + grownOutside);
        } finally {
            clearOwnedBy(server, owner.getUUID());
        }
        helper.succeed();
    }

    /** How many of {@code tries} single random ticks make a fresh wheat crop at {@code rel} grow. */
    static int grow(GameTestHelper helper, BlockPos rel, int tries) {
        BlockPos pos = helper.absolutePos(rel);
        var random = net.minecraft.util.RandomSource.create(42);
        int grown = 0;
        for (int i = 0; i < tries; i++) {
            helper.getLevel().setBlock(pos, Blocks.WHEAT.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            helper.getLevel().getBlockState(pos).randomTick(helper.getLevel(), pos, random);
            if (helper.getLevel().getBlockState(pos).getValue(net.minecraft.world.level.block.CropBlock.AGE) > 0) grown++;
        }
        return grown;
    }

    /** Animals that breed inside a working ranch building get a shorter cooldown at the bonus level. */
    @GameTest(template = FLOOR, batch = "territory_bonus_ranch")
    public static void ranchBreedingCooldown(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_ranch_owner");
        clearOwnedBy(server, owner.getUUID());
        Territory t = flagTerritory(helper, owner);
        TerritoryManager.setLevel(server, t, TerritoryLevels.table().bonusLevel(TerritoryBonuses.ANIMAL_BREEDING));
        instantBuilding(helper, owner, t, bonusTemplate("ranch_pen", TemplateDef.BONUS_RANCH), new BlockPos(3, 2, 3));
        net.minecraft.world.entity.animal.Cow a = helper.spawn(EntityType.COW, new BlockPos(4, 3, 5));
        net.minecraft.world.entity.animal.Cow b = helper.spawn(EntityType.COW, new BlockPos(6, 3, 5));
        net.minecraft.world.entity.animal.Cow c = helper.spawn(EntityType.COW, new BlockPos(11, 2, 11));
        net.minecraft.world.entity.animal.Cow d = helper.spawn(EntityType.COW, new BlockPos(12, 2, 11));
        a.spawnChildFromBreeding(helper.getLevel(), b);
        c.spawnChildFromBreeding(helper.getLevel(), d);
        int expected = (int) Math.round(6000 * com.maidbuilder.MaidBuilderConfig.BREEDING_COOLDOWN_FACTOR.get());
        helper.runAfterDelay(3, () -> {
            if (a.getAge() > expected || b.getAge() > expected) helper.fail("ranch cows' cooldown " + a.getAge() + "/" + b.getAge());
            if (c.getAge() < 5900 || d.getAge() < 5900) helper.fail("cows outside the ranch got a shorter cooldown: " + c.getAge());
            clearOwnedBy(server, owner.getUUID());
            helper.succeed();
        });
    }

    // ---- G4 ----

    /** A 5x2x5 field: farmland around a water source (hydrated), as a workplace for TLM's farm task. */
    static TemplateRegistry.Entry fieldTemplate(String path, int keep) {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder(path, IntPos.ZERO, new IntPos(5, 1, 5));
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) rb.set(x, 0, z, "minecraft:farmland[moisture=7]");
        rb.set(2, 0, 2, "minecraft:water[level=0]");
        Schematic schematic = LitematicWriter.schematic(path, 3955, List.of(rb.build()));
        TemplateDef def = new TemplateDef(MaidBuilder.id("test/" + path), MaidBuilder.id(path), "production", path, 1, 1, "", false,
                Optional.of(new TemplateDef.Workplace(ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "farm"), 2,
                        List.of("#c:crops", "#c:seeds"), keep)),
                ResourceLocation.withDefaultNamespace("wheat"), 0);
        return TemplateRegistry.registerForTest(def, schematic);
    }

    /** A 3x2x3 store: planks with a chest in the middle. */
    static TemplateRegistry.Entry storeTemplate(String path) {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder(path, IntPos.ZERO, new IntPos(3, 2, 3));
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) rb.set(x, 0, z, "minecraft:oak_planks");
        rb.set(1, 1, 1, "minecraft:chest[facing=north,type=single,waterlogged=false]");
        Schematic schematic = LitematicWriter.schematic(path, 3955, List.of(rb.build()));
        TemplateDef def = new TemplateDef(MaidBuilder.id("test/" + path), MaidBuilder.id(path), "storage", "warehouse", 1, 1, "", true,
                Optional.empty(), ResourceLocation.withDefaultNamespace("chest"), 0);
        return TemplateRegistry.registerForTest(def, schematic);
    }

    static void fillBackpack(EntityMaid maid, Item item) {
        IItemHandler backpack = maid.getAvailableBackpackInv();
        for (int i = 0; i < backpack.getSlots(); i++) {
            if (backpack.getStackInSlot(i).isEmpty()) backpack.insertItem(i, new ItemStack(item, 64), false);
        }
    }

    static void removeAll(EntityMaid maid, Item item) {
        IItemHandler backpack = maid.getAvailableBackpackInv();
        for (int i = 0; i < backpack.getSlots(); i++) {
            if (backpack.getStackInSlot(i).is(item)) backpack.extractItem(i, 64, false);
        }
    }

    /**
     * A maid assigned to a field farms it with TLM's farm task; with a full backpack she rests (shown
     * on the territory screen); without a warehouse she cannot deposit; with one she takes only her
     * products there (keeping some seeds) and goes back to work; emptied again, she resumes by herself.
     */
    @GameTest(template = FLOOR, batch = "territory_workplace", timeoutTicks = 6000)
    public static void workplaceFarmPauseDepositResume(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_work_owner");
        clearOwnedBy(server, owner.getUUID());
        Territory t = flagTerritory(helper, owner);
        int keep = 4;
        RegisteredBuilding field = instantBuilding(helper, owner, t, fieldTemplate("field", keep), new BlockPos(3, 1, 3));
        BlockPos crop = new BlockPos(4, 2, 4);
        helper.setBlock(crop, Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));

        EntityMaid maid = spawnMaid(helper, owner, new BlockPos(5, 2, 7));
        maid.getAvailableBackpackInv().insertItem(0, new ItemStack(Items.WHEAT_SEEDS, 8), false);
        if (!com.maidbuilder.common.territory.WorkplaceActions.assign(owner, t, maid, field.id())) helper.fail("not assigned");
        if (!maid.getTask().getUid().toString().equals("touhou_little_maid:farm")) helper.fail("task " + maid.getTask().getUid());
        IItemHandler[] chest = new IItemHandler[1];
        helper.startSequence()
                // she farms: the ripe wheat ends up in her backpack and the crop starts over
                .thenWaitUntil(() -> {
                    if (count(maid.getAvailableBackpackInv(), Items.WHEAT) == 0) helper.fail("wheat not harvested yet");
                })
                .thenExecute(() -> {
                    if (com.maidbuilder.common.territory.WorkplaceActions.startDeposit(owner, t, maid)) helper.fail("deposited without a warehouse");
                    if (TerritoryReports.build(owner, t, false).hasWarehouse()) helper.fail("report shows a warehouse");
                    instantBuilding(helper, owner, t, storeTemplate("store_room"), new BlockPos(11, 2, 11));
                    chest[0] = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, helper.absolutePos(new BlockPos(12, 3, 12)), null);
                    if (chest[0] == null) helper.fail("no warehouse chest");
                    // one harvest gives a single wheat, which she would keep: give her a good crop
                    ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), new ItemStack(Items.WHEAT, 20), false);
                    // without seeds she cannot plant (which would free a slot), so the backpack stays full
                    removeAll(maid, Items.WHEAT_SEEDS);
                    fillBackpack(maid, Items.DIRT);
                })
                // full backpack: she rests, and the territory screen says so
                .thenWaitUntil(() -> {
                    if (!com.maidbuilder.common.territory.WorkplaceActions.isPaused(maid)) helper.fail("not resting yet");
                })
                .thenExecute(() -> {
                    if (!maid.getTask().getUid().equals(com.github.tartaricacid.touhoulittlemaid.entity.task.TaskIdle.UID)) {
                        helper.fail("resting maid has task " + maid.getTask().getUid());
                    }
                    var row = TerritoryReports.build(owner, t, false).maids().stream().filter(r -> r.id().equals(maid.getUUID())).findFirst();
                    if (row.isEmpty() || !row.get().paused() || !row.get().full() || row.get().building().isEmpty()) {
                        helper.fail("report row " + row);
                    }
                    if (!com.maidbuilder.common.territory.WorkplaceActions.startDeposit(owner, t, maid)) helper.fail("deposit not started");
                })
                // she takes her products to the warehouse and goes back to farming
                .thenWaitUntil(() -> {
                    if (com.maidbuilder.common.territory.WorkplaceActions.isDepositing(maid)
                            || !maid.getTask().getUid().toString().equals("touhou_little_maid:farm")) {
                        helper.fail("still depositing (task " + maid.getTask().getUid() + ")");
                    }
                })
                .thenExecute(() -> {
                    IItemHandler backpack = maid.getAvailableBackpackInv();
                    if (count(chest[0], Items.WHEAT) == 0) helper.fail("no wheat in the warehouse");
                    if (count(backpack, Items.WHEAT) > keep) helper.fail("kept " + count(backpack, Items.WHEAT) + " wheat");
                    if (count(chest[0], Items.DIRT) > 0) helper.fail("dirt is not a product but was stored");
                    if (count(backpack, Items.DIRT) == 0) helper.fail("her dirt is gone");
                    if (!maid.getSchedulePos().getWorkPos().equals(field.center())) helper.fail("work point not restored: " + maid.getSchedulePos().getWorkPos());
                    // without seeds she cannot plant (which would free a slot), so the backpack stays full
                    removeAll(maid, Items.WHEAT_SEEDS);
                    fillBackpack(maid, Items.DIRT);
                })
                .thenWaitUntil(() -> {
                    if (!com.maidbuilder.common.territory.WorkplaceActions.isPaused(maid)) helper.fail("not resting again");
                })
                // the player empties her backpack: she resumes on her own
                .thenExecute(() -> removeAll(maid, Items.DIRT))
                .thenWaitUntil(() -> {
                    if (com.maidbuilder.common.territory.WorkplaceActions.isPaused(maid)
                            || !maid.getTask().getUid().toString().equals("touhou_little_maid:farm")) {
                        helper.fail("did not resume");
                    }
                })
                .thenExecute(() -> {
                    com.maidbuilder.common.territory.WorkplaceActions.unassign(server, maid);
                    if (!field.assignedMaids().isEmpty()) helper.fail("still assigned");
                    clearOwnedBy(server, owner.getUUID());
                })
                .thenSucceed();
    }

    /**
     * A maid the player sets up by hand at a building (home there, the building's task) is
     * registered as working there; given another task, she no longer is.
     */
    @GameTest(template = FLOOR, batch = "territory_detect", timeoutTicks = 1200)
    public static void workplaceDetectedWhenSetUpByHand(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_detect_owner");
        clearOwnedBy(server, owner.getUUID());
        Territory t = flagTerritory(helper, owner);
        RegisteredBuilding field = instantBuilding(helper, owner, t, fieldTemplate("hand_field", 0), new BlockPos(3, 1, 3));
        EntityMaid maid = spawnMaid(helper, owner, new BlockPos(4, 2, 4)); // home mode, work point in the field
        maid.getAvailableBackpackInv().insertItem(0, new ItemStack(Items.WHEAT_SEEDS, 8), false);
        TaskManager.findTask(ResourceLocation.fromNamespaceAndPath("touhou_little_maid", "farm")).ifPresent(maid::setTask);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!field.assignedMaids().contains(maid.getUUID())) helper.fail("not detected as working at the field");
                })
                .thenExecute(() -> {
                    var row = TerritoryReports.build(owner, t, false).buildings().stream().filter(b -> b.id().equals(field.id())).findFirst();
                    if (row.isEmpty() || row.get().maids() != 1) helper.fail("building row " + row);
                    maid.setTask(TaskManager.getIdleTask());
                })
                .thenWaitUntil(() -> {
                    if (field.assignedMaids().contains(maid.getUUID())) helper.fail("still registered after her task changed");
                })
                .thenExecute(() -> clearOwnedBy(server, owner.getUUID()))
                .thenSucceed();
    }

    // ---- G0 ----

    /** Flag limit per player and minimum spacing between flags (any owner). */
    @GameTest(template = FLOOR, batch = "territory_rules")
    public static void flagLimitAndSpacing(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer alice = player(helper, "mb_alice");
        ServerPlayer bob = player(helper, "mb_bob");
        clearOwnedBy(server, alice.getUUID());
        clearOwnedBy(server, bob.getUUID());
        int spacing = TerritoryLevels.minFlagDistance();
        int max = TerritoryManager.maxFlags();
        // far from everything else: a row along x
        BlockPos base = new BlockPos(3_000_000, 64, 3_000_000 + helper.absolutePos(BlockPos.ZERO).getZ());
        List<Territory> made = new ArrayList<>();
        try {
            for (int i = 0; i < max; i++) made.add(claim(helper, alice, base.offset(i * spacing, 0, 0)));
            var full = TerritoryManager.check(server, alice.getUUID(), helper.getLevel().dimension(), base.offset(0, 0, spacing * 3));
            if (full.ok()) helper.fail("flag " + (max + 1) + " should be refused");

            BlockPos near = base.offset(spacing - 1, 0, spacing * 2 - 1);
            var tooClose = TerritoryManager.check(server, bob.getUUID(), helper.getLevel().dimension(), base.offset(0, 0, spacing - 1));
            if (tooClose.ok()) helper.fail("a flag " + (spacing - 1) + " blocks away should be refused");
            var inside = TerritoryManager.check(server, bob.getUUID(), helper.getLevel().dimension(), base.offset(3, 0, 3));
            if (inside.ok()) helper.fail("a flag inside another territory should be refused");
            var farEnough = TerritoryManager.check(server, bob.getUUID(), helper.getLevel().dimension(), base.offset(0, 0, spacing));
            if (!farEnough.ok()) helper.fail("a flag " + spacing + " blocks away should be allowed: " + farEnough.error().getString());
            if (!TerritoryManager.check(server, bob.getUUID(), helper.getLevel().dimension(), near).ok()) {
                helper.fail("a position at least " + spacing + " blocks along z from every flag should be allowed");
            }

            // the largest radius keeps neighbours apart
            int radius = TerritoryLevels.table().get(TerritoryLevels.table().maxLevel()).radius();
            if (radius * 2 >= spacing) helper.fail("max radius " + radius + " lets territories " + spacing + " apart overlap");
            Territory a = made.get(0), b = made.get(1);
            BlockPos edgeA = a.flagPos().offset(a.radius(), 0, 0);
            if (TerritoryManager.data(server).at(helper.getLevel().dimension(), edgeA) != a) helper.fail("edge of A not in A");
            if (TerritoryManager.data(server).at(helper.getLevel().dimension(), edgeA.east()) == a) helper.fail("A reaches beyond its radius");
            if (b.contains(edgeA)) helper.fail("A and B overlap");
        } finally {
            clearOwnedBy(server, alice.getUUID());
            clearOwnedBy(server, bob.getUUID());
        }
        helper.succeed();
    }

    // ---- G1 ----

    /**
     * Build mode on the server: templates go into the queue only inside the territory, without
     * overlapping and when unlocked; a territory builder maid builds the queue in order, fetching
     * from a territory material container; finished template jobs leave the queue.
     */
    @GameTest(template = FLOOR, batch = "territory_build", timeoutTicks = 4000)
    public static void templatesQueuedAndBuiltInOrder(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_builder_owner");
        clearOwnedBy(server, owner.getUUID());
        if (!useFlag(helper, owner, new BlockPos(1, 1, 14))) helper.fail("flag was not placed");
        Territory t = TerritoryManager.data(server).atFlag(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 2, 14)));
        TemplateRegistry.Entry hut = testTemplate("hut", 1, 7, "test_hut", "production");
        TemplateRegistry.Entry locked = testTemplate("locked_hut", 3, 7, "test_hut", "production");

        BuildJob first = placeTemplate(helper, owner, t, hut, new BlockPos(3, 2, 3));
        BuildJob second = placeTemplate(helper, owner, t, hut, new BlockPos(10, 2, 3));
        if (first == null || second == null) helper.fail("templates were not queued");
        if (!t.jobQueue().equals(List.of(first.id(), second.id()))) helper.fail("queue order " + t.jobQueue());
        if (placeTemplate(helper, owner, t, hut, new BlockPos(4, 2, 4)) != null) helper.fail("overlapping template was queued");
        if (placeTemplate(helper, owner, t, locked, new BlockPos(3, 2, 10)) != null) helper.fail("locked template was queued");
        BlockPos outside = new BlockPos(1 + t.radius() - 1, 2, 3);
        if (placeTemplate(helper, owner, t, hut, outside) != null) helper.fail("template reaching outside the territory was queued");
        ServerPlayer stranger = player(helper, "mb_stranger");
        if (placeTemplate(helper, stranger, t, hut, new BlockPos(3, 2, 10)) != null) helper.fail("a stranger queued a template");

        // materials for both in a territory container
        BlockPos chestRel = new BlockPos(7, 2, 12);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        IItemHandler chest = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, chestAbs, null);
        ItemHandlerHelper.insertItemStacked(chest, new ItemStack(Items.OAK_PLANKS, 18), false);
        ItemHandlerHelper.insertItemStacked(chest, new ItemStack(Items.OAK_LOG, 2), false);
        if (!TerritoryActions.toggleSource(owner, t, chestAbs)) helper.fail("chest not bound to the territory");
        var report = MaterialReports.build(helper.getLevel(), null, first, false);
        var planks = report.rows().stream().filter(r -> r.item() == Items.OAK_PLANKS).findFirst().orElse(null);
        if (planks == null || planks.containers() != 18 || planks.remaining() != 9) helper.fail("territory container not counted: " + planks);

        EntityMaid maid = spawnMaid(helper, owner, new BlockPos(7, 2, 8));
        TaskManager.findTask(TaskBuilder.UID).ifPresent(maid::setTask);

        helper.onEachTick(() -> {
            if (second.count(BuildJob.DONE) > 0 && !first.isComplete()) helper.fail("the second job was started before the first was done");
        });
        helper.succeedWhen(() -> {
            if (!t.jobQueue().isEmpty()) helper.fail("queue not empty: " + t.jobQueue().size());
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(4, 3, 4));
            helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(11, 3, 4));
            if (BuildJobManager.data(server).get(first.id()) != null) helper.fail("finished template job not removed");
            clearOwnedBy(server, owner.getUUID());
        });
    }

    /**
     * Placing the flag block claims a territory; removing it deactivates the territory and suspends
     * its jobs; other players cannot claim the inactive land; the owner takes it back (even with all
     * flag slots used) and the jobs resume. Survives a save/load round trip.
     */
    @GameTest(template = FLOOR, batch = "territory_flag")
    public static void flagRemoveAndRetake(GameTestHelper helper) {
        clearNear(helper);
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer owner = player(helper, "mb_owner");
        ServerPlayer other = player(helper, "mb_other");
        clearOwnedBy(server, owner.getUUID());
        clearOwnedBy(server, other.getUUID());
        try {
            if (!useFlag(helper, owner, new BlockPos(4, 1, 4))) helper.fail("flag was not placed");
            BlockPos flag = helper.absolutePos(new BlockPos(4, 2, 4));
            Territory t = TerritoryManager.data(server).atFlag(helper.getLevel().dimension(), flag);
            if (t == null || !t.isActive() || !t.isOwnedBy(owner.getUUID()) || t.level() != 1) helper.fail("no active territory at the flag");
            if (t.radius() != TerritoryLevels.table().get(1).radius()) helper.fail("level 1 radius expected, got " + t.radius());

            // a queued job is suspended with the territory
            BuildJob job = new BuildJob(UUID.randomUUID(), owner.getUUID(), "mb_owner", "test", "0".repeat(40),
                    helper.getLevel().dimension(), flag.east(2), net.minecraft.world.level.block.Rotation.NONE,
                    net.minecraft.world.level.block.Mirror.NONE, false, null);
            BuildJobManager.data(server).add(job);
            job.setTerritory(t.id(), null, null);
            t.enqueue(job.id());

            // a second flag inside one's own active territory is refused
            if (useFlag(helper, owner, new BlockPos(10, 1, 10))) helper.fail("second flag inside an active territory was placed");

            helper.getLevel().destroyBlock(flag, false);
            if (t.isActive()) helper.fail("territory still active after the flag was removed");
            if (!job.isSuspended() || !job.isFinished()) helper.fail("job not suspended");

            // nobody else may claim the land meanwhile
            if (useFlag(helper, other, new BlockPos(10, 1, 10))) helper.fail("another player claimed an inactive territory");

            // fill the owner's remaining slots far away: taking the old territory back needs no new slot
            int spacing = TerritoryLevels.minFlagDistance();
            BlockPos far = new BlockPos(-3_000_000, 64, -3_000_000);
            for (int i = 1; i < TerritoryManager.maxFlags(); i++) claim(helper, owner, far.offset(i * spacing, 0, 0));
            if (TerritoryManager.data(server).ownedBy(owner.getUUID()).size() != TerritoryManager.maxFlags()) helper.fail("slots not full");

            if (!useFlag(helper, owner, new BlockPos(10, 1, 10))) helper.fail("owner could not take the territory back");
            if (!t.isActive() || !t.flagPos().equals(helper.absolutePos(new BlockPos(10, 2, 10)))) helper.fail("territory not taken back");
            if (job.isSuspended()) helper.fail("job still suspended");
            if (TerritoryManager.data(server).ownedBy(owner.getUUID()).size() != TerritoryManager.maxFlags()) {
                helper.fail("taking a territory back used a new slot");
            }
            // the old flag block is gone; removing the new one deactivates again
            if (TerritoryManager.data(server).at(helper.getLevel().dimension(), flag) != t) helper.fail("index not updated after the flag moved");

            // save / load round trip
            CompoundTag saved = TerritoryManager.data(server).save(new CompoundTag(), helper.getLevel().registryAccess());
            TerritoryData loaded = TerritoryData.FACTORY.deserializer().apply(saved, helper.getLevel().registryAccess());
            Territory copy = loaded.get(t.id());
            if (copy == null || !copy.flagPos().equals(t.flagPos()) || copy.level() != t.level() || !copy.isActive()
                    || !copy.jobQueue().equals(t.jobQueue()) || !copy.owner().equals(t.owner())) {
                helper.fail("territory changed in a save/load round trip");
            }
            if (loaded.at(helper.getLevel().dimension(), t.flagPos().offset(5, 0, 5)) == null) helper.fail("loaded data has no index");
            BuildJobManager.data(server).remove(job.id());
        } finally {
            clearOwnedBy(server, owner.getUUID());
            clearOwnedBy(server, other.getUUID());
        }
        helper.succeed();
    }
}
