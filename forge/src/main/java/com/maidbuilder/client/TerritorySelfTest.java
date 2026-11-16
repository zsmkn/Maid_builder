package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.client.territory.BuildMode;
import com.maidbuilder.client.territory.ClientTerritories;
import com.maidbuilder.client.territory.TemplateCatalogScreen;
import com.maidbuilder.client.territory.TerritoryScreen;
import com.maidbuilder.common.territory.TemplatePlacement;
import com.maidbuilder.common.territory.TemplateRegistry;
import com.maidbuilder.common.territory.Territory;
import com.maidbuilder.common.territory.TerritoryManager;
import com.maidbuilder.common.territory.TerritoryReports;
import com.maidbuilder.init.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import com.maidbuilder.client.territory.ProblemHighlights;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Territory part of the client self test: a flag in front of the player, the territory screen's
 * tabs, the template catalog and build mode (a valid spot, then one overlapping a queued
 * building), each saved as {@code screenshots/maidbuilder_selftest_territory_*.png}. Everything
 * is removed again afterwards.
 */
final class TerritorySelfTest {
    @Nullable
    private static volatile UUID territory;
    private static volatile BlockPos flag = BlockPos.ZERO;
    private static float startYaw, startPitch;

    private TerritorySelfTest() {
    }

    /** Returns true when done. */
    static boolean tick(Minecraft mc, int t) {
        if (mc.player == null) return true;
        var server = mc.getSingleplayerServer();
        if (server == null) return true;
        UUID playerId = mc.player.getUUID();
        if (t == 0) {
            startYaw = mc.player.getYRot();
            startPitch = mc.player.getXRot();
            server.execute(() -> setUp(server, playerId));
        }
        if (t == 15) shot(mc, "overview");
        if (t == 17) press(mc, "screen.maidbuilder.territory.tab.construction");
        if (t == 25) shot(mc, "construction");
        if (t == 27) press(mc, "screen.maidbuilder.territory.tab.buildings");
        if (t == 31) shot(mc, "buildings");
        if (t == 32) press(mc, "screen.maidbuilder.territory.tab.maids");
        if (t == 35) shot(mc, "maids");
        if (t == 36 && mc.screen instanceof TerritoryScreen screen && !screen.report().maids().isEmpty()) {
            mc.setScreen(new com.maidbuilder.client.territory.WorkplacePickerScreen(screen, screen.report(), screen.report().maids().get(0)));
        }
        if (t == 40) shot(mc, "workplace_picker");
        if (t == 41) {
            press(mc, "screen.maidbuilder.territory.tab.overview");
            UUID id = territory;
            mc.setScreen(id == null ? null : new TemplateCatalogScreen(id));
        }
        if (t == 46) shot(mc, "catalog");
        if (t == 47) {
            mc.setScreen(null);
            var info = ClientTerritories.template(new ResourceLocation("maidbuilder", "small_greenhouse"));
            UUID id = territory;
            if (id != null && info != null) BuildMode.start(id, info);
            // 1.20.1 self test world: the player still holds the placed wand, whose HUD would overlap the build mode HUD
            savedHand = mc.player.getMainHandItem().copy();
            setHand(server, playerId, net.minecraft.world.item.ItemStack.EMPTY);
            // away from the flag: a free spot (looking further away than on 1.21.1, so the greenhouse's long side
            // does not reach the flag in this world)
            mc.player.setYRot(startYaw + 180);
            mc.player.setXRot(15);
        }
        if (t == 75) shot(mc, "build_valid");
        if (t == 77) {
            // over the flag: not allowed, so red
            mc.player.setYRot(startYaw);
            mc.player.setXRot(35);
        }
        if (t == 105) shot(mc, "build_invalid");
        if (t == 107) {
            BuildMode.stop();
            setHand(server, playerId, savedHand);
            mc.player.setYRot(startYaw);
            mc.player.setXRot(startPitch);
            UUID id = territory;
            BlockPos pos = flag;
            server.execute(() -> {
                Territory created = id == null ? null : TerritoryManager.data(server).get(id);
                var player = server.getPlayerList().getPlayer(playerId);
                if (created != null && player != null) {
                    for (var b : created.buildings()) {
                        for (BlockPos p : BlockPos.betweenClosed(b.min(), b.max())) player.level().setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                if (worker != null) {
                    worker.discard();
                    worker = null;
                }
                if (created != null) TerritoryManager.remove(server, created);
                if (player != null && id != null) player.level().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            });
        }
        if (t == 109) {
            // the shop screen with a few goods (in game the list comes from the server)
            java.util.function.Function<String, ResourceLocation> item = ResourceLocation::new;
            var rows = java.util.List.of(
                    new TerritoryPayloads.ShopRow(item.apply("oak_planks"), "wood", 2, 16, 112, 2),
                    new TerritoryPayloads.ShopRow(item.apply("spruce_stairs"), "wood", 2, 16, 128, 2),
                    new TerritoryPayloads.ShopRow(item.apply("dark_oak_log"), "wood", 2, 16, 0, 2),
                    new TerritoryPayloads.ShopRow(item.apply("cherry_fence"), "wood", 2, 16, 128, 3),
                    new TerritoryPayloads.ShopRow(item.apply("stone_bricks"), "stone", 2, 16, 128, 2));
            var missing = java.util.List.of(new TerritoryPayloads.MissingRow(item.apply("oak_planks"), 40, 3, TerritoryPayloads.MissingStatus.OK));
            mc.setScreen(new com.maidbuilder.client.territory.ShopScreen(
                    new TerritoryPayloads.ShopMenu(UUID.randomUUID(), 2, "Reimu", rows, missing, 6, true)));
        }
        if (t == 114) shot(mc, "shop");
        if (t == 115) {
            // the details of blocks left for the player (in game the list comes from the server)
            BlockPos at = mc.player.blockPosition();
            var rows = java.util.List.of(
                    new TerritoryPayloads.ProblemRow(at.offset(2, 0, 1), Blocks.OAK_PLANKS.defaultBlockState(), Blocks.CHEST.defaultBlockState(),
                            com.maidbuilder.common.job.BuildJob.Problem.UNBREAKABLE),
                    new TerritoryPayloads.ProblemRow(at.offset(4, 3, 2), Blocks.LANTERN.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                            com.maidbuilder.common.job.BuildJob.Problem.NO_SUPPORT),
                    new TerritoryPayloads.ProblemRow(at.offset(-6, 7, 3), Blocks.OAK_STAIRS.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                            com.maidbuilder.common.job.BuildJob.Problem.UNREACHABLE),
                    new TerritoryPayloads.ProblemRow(at.offset(1, 1, -5), Blocks.AIR.defaultBlockState(), Blocks.BEDROCK.defaultBlockState(),
                            com.maidbuilder.common.job.BuildJob.Problem.UNBREAKABLE));
            mc.setScreen(new com.maidbuilder.client.territory.JobProblemsScreen(null, new TerritoryPayloads.JobProblems(UUID.randomUUID(),
                    UUID.randomUUID(), "", "maidbuilder:dormitory", mc.level.dimension().location(), 4, rows)));
        }
        if (t == 120) shot(mc, "problems");
        if (t == 121) {
            mc.setScreen(null);
            // 1.20.1 port check: the outlines of blocks left for the player show through a wall (with
            // shaders too: runClientShaderTest), as after "Outline all"
            net.minecraft.core.Direction facing = mc.player.getDirection();
            BlockPos at = mc.player.blockPosition();
            outlineWall = at.relative(facing, 3);
            setWall(mc, facing, true);
            ProblemHighlights.show(mc.level.dimension().location(), java.util.List.of(at.relative(facing, 5), at.relative(facing, 6).above(),
                    at.relative(facing, 5).relative(facing.getClockWise(), 2), at.relative(facing, 2).relative(facing.getCounterClockWise(), 2)));
        }
        if (t == 126) shot(mc, "problems_outline");
        if (t == 127) {
            ProblemHighlights.clear();
            setWall(mc, mc.player.getDirection(), false);
        }
        return t >= 130;
    }

    private static net.minecraft.world.item.ItemStack savedHand = net.minecraft.world.item.ItemStack.EMPTY;

    private static void setHand(net.minecraft.server.MinecraftServer server, UUID playerId, net.minecraft.world.item.ItemStack stack) {
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            if (player != null) player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack.copy());
        });
    }

    private static void setUp(net.minecraft.server.MinecraftServer server, UUID playerId) {
        var player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        BlockPos pos = player.blockPosition().relative(player.getDirection(), 3);
        var check = TerritoryManager.check(server, player.getUUID(), player.level().dimension(), pos);
        if (!check.ok()) {
            MaidBuilder.LOGGER.warn("[selftest] cannot claim: {}", check.error().getString());
            return;
        }
        player.level().setBlockAndUpdate(pos, ModBlocks.TERRITORY_FLAG.get().defaultBlockState());
        Territory created = TerritoryManager.place(server, player, player.level().dimension(), pos, check);
        territory = created.id();
        flag = pos;
        // a finished warehouse and greenhouse (built at once), a queued sugar cane farm, and a maid at the greenhouse
        TemplateRegistry.Entry warehouse = TemplateRegistry.entry(new ResourceLocation("maidbuilder", "warehouse"));
        TemplateRegistry.Entry greenhouse = TemplateRegistry.entry(new ResourceLocation("maidbuilder", "small_greenhouse"));
        TemplateRegistry.Entry farm = TemplateRegistry.entry(new ResourceLocation("maidbuilder", "sugar_cane_farm"));
        if (warehouse != null && greenhouse != null && farm != null) {
            try {
                var dir = player.getDirection();
                instant(server, player, created, warehouse, player.blockPosition().relative(dir, 8).relative(dir.getClockWise(), 8));
                BlockPos greenhouseOrigin = player.blockPosition().relative(dir, 8).relative(dir.getClockWise(), 18);
                instant(server, player, created, greenhouse, greenhouseOrigin);
                TemplatePlacement.createJob(server, player.getUUID(), player.getGameProfile().getName(), created, farm,
                        player.blockPosition().relative(dir, 8).relative(dir.getCounterClockWise(), 14), Rotation.NONE, Mirror.NONE, null);
                spawnWorker(player, created, greenhouseOrigin);
            } catch (java.io.IOException e) {
                MaidBuilder.LOGGER.warn("[selftest] cannot queue", e);
            }
        }
        TerritoryReports.send(player, created, true);
    }

    @Nullable
    private static com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid worker;

    private static void instant(net.minecraft.server.MinecraftServer server, net.minecraft.server.level.ServerPlayer player, Territory territory,
                                TemplateRegistry.Entry entry, BlockPos origin) throws java.io.IOException {
        var job = TemplatePlacement.createJob(server, player.getUUID(), player.getGameProfile().getName(), territory, entry,
                origin, Rotation.NONE, Mirror.NONE, null);
        for (int i = 0; i < job.size(); i++) {
            if (job.status(i) != com.maidbuilder.common.job.BuildJob.PENDING) continue;
            com.maidbuilder.common.BlockPlacer.placeExact(player.serverLevel(), job.pos(i), job.target(i));
            job.setStatus(i, com.maidbuilder.common.job.BuildJob.DONE);
        }
        com.maidbuilder.common.territory.TerritoryTicker.updateQueue(server, territory);
    }

    /** A maid of the player working at the greenhouse, resting because her backpack is full. */
    private static void spawnWorker(net.minecraft.server.level.ServerPlayer player, Territory territory, BlockPos greenhouseOrigin) {
        var level = player.serverLevel();
        var maid = com.github.tartaricacid.touhoulittlemaid.init.InitEntities.MAID.get().create(level);
        if (maid == null) return;
        maid.moveTo(greenhouseOrigin.getX() + 4.5, greenhouseOrigin.getY() + 1, greenhouseOrigin.getZ() + 2.5);
        maid.setTame(true);
        maid.setOwnerUUID(player.getUUID());
        level.addFreshEntity(maid);
        worker = maid;
        var building = territory.buildings().stream().filter(b -> b.origin().equals(greenhouseOrigin)).findFirst().orElse(null);
        if (building == null) return;
        com.maidbuilder.common.territory.WorkplaceActions.assign(player, territory, maid, building.id());
        var backpack = maid.getAvailableBackpackInv();
        for (int i = 0; i < backpack.getSlots(); i++) backpack.insertItem(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 64), false);
        com.maidbuilder.common.maid.WorkplaceHandler.check(level, maid, com.maidbuilder.common.maid.WorkplaceMaidData.of(maid));
    }

    private static BlockPos outlineWall = BlockPos.ZERO;

    /** A 3x3 stone wall across the view (or air again), on the integrated server. */
    private static void setWall(Minecraft mc, net.minecraft.core.Direction facing, boolean build) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        BlockPos centre = outlineWall;
        net.minecraft.core.Direction side = facing.getClockWise();
        server.execute(() -> {
            var level = server.overworld();
            for (int i = -1; i <= 1; i++) {
                for (int y = 0; y <= 2; y++) {
                    level.setBlockAndUpdate(centre.relative(side, i).above(y),
                            build ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        });
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "maidbuilder_selftest_territory_" + name + ".png", mc.getMainRenderTarget(),
                msg -> MaidBuilder.LOGGER.info("[selftest] {}", msg.getString()));
    }

    private static void press(Minecraft mc, String key) {
        String label = Component.translatable(key).getString();
        if (!(mc.screen instanceof TerritoryScreen screen) || !press(screen, label)) MaidBuilder.LOGGER.warn("[selftest] no button {}", key);
    }

    private static boolean press(ContainerEventHandler parent, String label) {
        for (GuiEventListener child : parent.children()) {
            if (child instanceof Button button && button.getMessage().getString().equals(label)) {
                button.onPress();
                return true;
            }
        }
        return false;
    }
}
