package com.maidbuilder.gametest;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.Convert;
import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.common.StateResolver;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.common.maid.BuilderMaidData;
import com.maidbuilder.common.maid.TaskBuilder;
import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.plan.BuildPlan;
import com.maidbuilder.core.plan.BuildPlanner;
import com.maidbuilder.core.plan.BuildStep;
import com.maidbuilder.core.schematic.BlockStateData;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.transform.Mirror;
import com.maidbuilder.core.transform.Placement;
import com.maidbuilder.core.transform.Rotation;
import com.maidbuilder.network.Payloads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * In-game tests, run with {@code gradlew :neoforge:runGameTestServer}.
 * Only active when the {@code maidbuilder} game test namespace is enabled (dev runs).
 */
@GameTestHolder(MaidBuilder.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaidBuilderGameTests {
    private static final String FLOOR = "floor16";

    /**
     * Blocks where vanilla's own mirror/rotate is not geometric and core deliberately differs.
     * They have no effect on building because the mod side always uses the game's transform:
     * anvils ignore mirroring (their shape is symmetric), fire and chorus plants never
     * transform their connections (recomputed from neighbours anyway), and the detector rail
     * turns north_south into east_west on a 180 degree rotation.
     */
    private static final java.util.Set<String> KNOWN_VANILLA_QUIRKS = java.util.Set.of(
            "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil",
            "minecraft:fire", "minecraft:chorus_plant", "minecraft:detector_rail");

    private MaidBuilderGameTests() {
    }

    // ---- 1. core property transform vs. the game's own BlockState.mirror/rotate ----

    @GameTest(template = FLOOR)
    public static void coreTransformMatchesVanilla(GameTestHelper helper) {
        Map<String, String> mismatches = new TreeMap<>();
        int checked = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (!blockId.startsWith("minecraft:") || KNOWN_VANILLA_QUIRKS.contains(blockId)) continue;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                BlockStateData data = toData(state);
                for (Rotation r : Rotation.values()) {
                    for (Mirror m : Mirror.values()) {
                        checked++;
                        BlockStateData expected = toData(state.mirror(Convert.toMc(m)).rotate(Convert.toMc(r)));
                        BlockStateData actual = new Placement(IntPos.ZERO, r, m).transform(data);
                        if (!expected.equals(actual)) {
                            mismatches.putIfAbsent(blockId, data + " " + r + "/" + m + " -> vanilla " + expected + " core " + actual);
                        }
                    }
                }
            }
        }
        if (!mismatches.isEmpty()) {
            mismatches.values().forEach(v -> MaidBuilder.LOGGER.error("Transform mismatch: {}", v));
            helper.fail(mismatches.size() + " blocks transform differently from vanilla, e.g. " + mismatches.values().iterator().next());
        }
        MaidBuilder.LOGGER.info("Core transform matches vanilla for {} state/transform combinations", checked);
        helper.succeed();
    }

    // ---- 2. paste pipeline: plan + resolve + exact placement, incl. two-block structures ----

    @GameTest(template = FLOOR)
    public static void pastePlacesTransformedStates(GameTestHelper helper) {
        Schematic schematic = sampleHouse();
        for (Rotation r : Rotation.values()) {
            for (Mirror m : Mirror.values()) {
                pasteAndVerify(helper, schematic, r, m);
            }
        }
        helper.succeed();
    }

    private static void pasteAndVerify(GameTestHelper helper, Schematic schematic, Rotation r, Mirror m) {
        BlockPos origin = helper.absolutePos(new BlockPos(8, 2, 8));
        // clear the area first
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-4, 0, -4), origin.offset(4, 4, 4))) {
            helper.getLevel().setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Placement placement = new Placement(Convert.toIntPos(origin), r, m);
        BuildPlan plan = BuildPlanner.plan(schematic, placement, new BuildPlanner.Options(true));
        StateResolver resolver = new StateResolver(schematic.minecraftDataVersion());
        for (BuildStep step : plan.steps()) {
            BlockPlacer.placeExact(helper.getLevel(), Convert.toBlockPos(step.worldPos()), resolver.resolve(step.schematicState(), placement));
        }
        for (BuildStep step : plan.steps()) {
            BlockPos pos = Convert.toBlockPos(step.worldPos());
            BlockState expected = resolver.resolve(step.schematicState(), placement);
            BlockState actual = helper.getLevel().getBlockState(pos);
            if (actual != expected) {
                helper.fail(r + "/" + m + ": expected " + expected + " at " + pos + " but found " + actual);
            }
        }
        // the door's upper half comes from the lower half; its position must match the schematic's
        BlockPos upper = Convert.toBlockPos(placement.toWorld(new IntPos(2, 2, 0)));
        BlockState upperState = helper.getLevel().getBlockState(upper);
        if (!upperState.is(Blocks.OAK_DOOR) || upperState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.UPPER) {
            helper.fail(r + "/" + m + ": door upper half missing at " + upper + ", found " + upperState);
        }
        BlockPos bedHead = Convert.toBlockPos(placement.toWorld(new IntPos(0, 1, 2)));
        if (!helper.getLevel().getBlockState(bedHead).is(Blocks.RED_BED)) {
            helper.fail(r + "/" + m + ": bed head missing at " + bedHead);
        }
    }

    // ---- 3. a maid builds a small structure from her inventory ----

    /** Relative position of the hut's origin; relative y=1 is the template floor. */
    private static final BlockPos HUT_ORIGIN = new BlockPos(7, 2, 7);

    private static List<ItemStack> hutMaterials() {
        return List.of(new ItemStack(Items.OAK_PLANKS, 9), new ItemStack(Items.OAK_LOG, 2),
                new ItemStack(Items.TORCH, 1), new ItemStack(Items.OAK_DOOR, 1));
    }

    @GameTest(template = FLOOR, timeoutTicks = 2400)
    public static void maidBuildsHut(GameTestHelper helper) {
        BuildJob job = createHutJob(helper);
        EntityMaid maid = spawnBuilderMaid(helper, job);
        IItemHandler inv = maid.getAvailableInv(false);
        for (ItemStack stack : hutMaterials()) {
            if (!ItemHandlerHelper.insertItemStacked(inv, stack.copy(), false).isEmpty()) helper.fail("Maid inventory full");
        }
        helper.succeedWhen(() -> {
            assertHutBuilt(helper, job);
            assertHoldsNoHutMaterials(helper, inv);
            BuildJobManager.data(helper.getLevel().getServer()).remove(job.id());
        });
    }

    // ---- 4. phase 6: an empty-handed maid fetches everything from a material chest ----

    @GameTest(template = FLOOR, timeoutTicks = 3600)
    public static void maidFetchesFromMaterialChest(GameTestHelper helper) {
        BuildJob job = createHutJob(helper);
        BlockPos chestRel = new BlockPos(2, 2, 12);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        IItemHandler chest = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, chestAbs, null);
        if (chest == null) throw new IllegalStateException("chest has no item handler");
        for (ItemStack stack : hutMaterials()) ItemHandlerHelper.insertItemStacked(chest, stack.copy(), false);
        chest.insertItem(20, new ItemStack(Items.DIAMOND, 3), false); // unrelated item must stay
        job.toggleMaterialSource(chestAbs);

        // Everything the job needs is in the chest, so nothing is reported missing.
        Payloads.JobStatus status = com.maidbuilder.common.wand.JobStatusSync.status(helper.getLevel().getServer(), job);
        if (!status.missing().isEmpty()) helper.fail("expected nothing missing, got " + status.missing());

        EntityMaid maid = spawnBuilderMaid(helper, job);
        helper.succeedWhen(() -> {
            assertHutBuilt(helper, job);
            assertHoldsNoHutMaterials(helper, chest);
            assertHoldsNoHutMaterials(helper, maid.getAvailableInv(false));
            if (chest.getStackInSlot(20).getCount() != 3) helper.fail("unrelated item was taken from the chest");
            BuildJobManager.data(helper.getLevel().getServer()).remove(job.id());
        });
    }

    // ---- 5. phase 3 server side: select, adjust, confirm, bind containers, unlink ----

    @GameTest(template = FLOOR)
    public static void wandSelectAdjustConfirm(GameTestHelper helper) {
        String hash = storeSchematic(helper, hutSchematic());
        // A FakePlayer is not in the player list, so TLM's broadcasts never try to reach it.
        net.minecraft.server.level.ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(
                helper.getLevel(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), "maidbuilder_test"));
        ItemStack wand = new ItemStack(com.maidbuilder.init.ModItems.BLUEPRINT_WAND.get());
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, wand);
        wand = player.getMainHandItem();
        BlockPos origin = helper.absolutePos(HUT_ORIGIN);

        com.maidbuilder.common.wand.WandActions.select(player, new Payloads.SelectSchematic("hut.litematic", hash, origin));
        com.maidbuilder.common.wand.WandActions.adjust(player, Payloads.Adjustment.ROTATE);
        com.maidbuilder.common.wand.WandActions.adjust(player, Payloads.Adjustment.MIRROR);
        com.maidbuilder.common.wand.WandActions.adjust(player, Payloads.Adjustment.UP);
        com.maidbuilder.common.wand.WandActions.adjust(player, Payloads.Adjustment.DOWN);
        com.maidbuilder.common.wand.WandActions.adjust(player, Payloads.Adjustment.UP);
        var placement = wand.get(com.maidbuilder.init.ModDataComponents.WAND_PLACEMENT.get());
        if (placement == null || placement.rotation() != net.minecraft.world.level.block.Rotation.CLOCKWISE_90
                || placement.mirror() != net.minecraft.world.level.block.Mirror.LEFT_RIGHT || !placement.origin().equals(origin.above())) {
            helper.fail("unexpected placement " + placement);
        }

        com.maidbuilder.common.wand.WandActions.confirm(player, wand);
        UUID jobId = wand.get(com.maidbuilder.init.ModDataComponents.BUILD_JOB.get());
        if (jobId == null) helper.fail("confirm did not link a job");
        BuildJob job = BuildJobManager.data(helper.getLevel().getServer()).get(jobId);
        if (job == null || job.rotation() != net.minecraft.world.level.block.Rotation.CLOCKWISE_90
                || !job.origin().equals(origin.above()) || job.size() != 13) {
            helper.fail("unexpected job " + (job == null ? null : job.origin() + " " + job.rotation() + " " + job.size()));
        }

        // Linked wands no longer move the placement.
        com.maidbuilder.common.wand.WandActions.adjust(player, Payloads.Adjustment.UP);
        if (!wand.get(com.maidbuilder.init.ModDataComponents.WAND_PLACEMENT.get()).equals(placement)) {
            helper.fail("linked wand placement changed");
        }

        // Containers toggle as material sources; plain blocks do not.
        BlockPos chest = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        if (!com.maidbuilder.common.wand.WandActions.toggleMaterialSource(player, wand, chest) || !job.materialSources().contains(chest)) {
            helper.fail("chest was not added as material source");
        }
        if (com.maidbuilder.common.wand.WandActions.toggleMaterialSource(player, wand, helper.absolutePos(new BlockPos(1, 1, 1)))) {
            helper.fail("stone accepted as material source");
        }
        com.maidbuilder.common.wand.WandActions.toggleMaterialSource(player, wand, chest);
        if (!job.materialSources().isEmpty()) helper.fail("chest was not removed");

        // Unlinking an unfinished job asks for confirmation, then cancels it.
        com.maidbuilder.common.wand.WandActions.unlink(player, wand);
        if (!wand.has(com.maidbuilder.init.ModDataComponents.BUILD_JOB.get())) helper.fail("first sneak-use should only ask to confirm");
        com.maidbuilder.common.wand.WandActions.unlink(player, wand);
        if (wand.has(com.maidbuilder.init.ModDataComponents.BUILD_JOB.get())) helper.fail("unlink failed");
        if (!job.isCancelled() || BuildJobManager.data(helper.getLevel().getServer()).get(jobId) != null) {
            helper.fail("job without scaffolding should be cancelled and deleted at once");
        }
        helper.succeed();
    }

    // ---- 6. phase 7: out of reach -> ask for scaffolding, build it, climb it, take it down ----

    private static final String TALL_FLOOR = "floor16_tall";
    private static final int PILLAR_HEIGHT = 10;
    private static final BlockPos PILLAR = new BlockPos(8, 2, 8);

    @GameTest(template = TALL_FLOOR, timeoutTicks = 6000)
    public static void maidUsesScaffoldingForHighBlocks(GameTestHelper helper) {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("pillar", IntPos.ZERO, new IntPos(1, PILLAR_HEIGHT, 1));
        for (int y = 0; y < PILLAR_HEIGHT; y++) rb.set(0, y, 0, "minecraft:oak_planks");
        BuildJob job = createJob(helper, LitematicWriter.schematic("pillar", 3955, List.of(rb.build())), PILLAR);
        EntityMaid maid = spawnBuilderMaid(helper, job);
        IItemHandler inv = maid.getAvailableInv(false);
        ItemHandlerHelper.insertItemStacked(inv, new ItemStack(Items.OAK_PLANKS, PILLAR_HEIGHT), false);

        helper.startSequence()
                .thenExecuteAfter(600, () -> {
                    // Without scaffolding she builds what she reaches and waits (asking her owner) for the rest.
                    if (job.count(BuildJob.DONE) < 4) helper.fail("low blocks not built: " + job.count(BuildJob.DONE));
                    if (job.count(BuildJob.PENDING) == 0) helper.fail("high blocks should still be pending");
                    if (job.count(BuildJob.NEEDS_PLAYER) + job.count(BuildJob.FAILED) > 0) {
                        helper.fail("high blocks were given up instead of waiting for scaffolding");
                    }
                    if (!job.scaffolds().isEmpty()) helper.fail("scaffolding appeared out of nowhere");
                    ItemHandlerHelper.insertItemStacked(inv, new ItemStack(Items.SCAFFOLDING, 16), false);
                })
                .thenWaitUntil(() -> {
                    if (!job.isComplete() || job.count(BuildJob.DONE) != job.size()) {
                        helper.fail("progress " + job.count(BuildJob.DONE) + "/" + job.size()
                                + ", needs player " + job.count(BuildJob.NEEDS_PLAYER));
                    }
                    if (!job.scaffolds().isEmpty()) helper.fail(job.scaffolds().size() + " scaffolding blocks still up");
                    int scaffolding = 0;
                    for (int i = 0; i < inv.getSlots(); i++) {
                        if (inv.getStackInSlot(i).is(Items.SCAFFOLDING)) scaffolding += inv.getStackInSlot(i).getCount();
                    }
                    if (scaffolding != 16) helper.fail("maid holds " + scaffolding + " scaffolding, expected all 16 back");
                    if (maid.isShiftKeyDown()) helper.fail("maid is still sneaking");
                })
                .thenExecute(() -> {
                    for (int y = 0; y < PILLAR_HEIGHT; y++) {
                        helper.assertBlockPresent(Blocks.OAK_PLANKS, PILLAR.above(y));
                    }
                    for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 2, 0)), helper.absolutePos(new BlockPos(15, 20, 15)))) {
                        if (helper.getLevel().getBlockState(p).is(Blocks.SCAFFOLDING)) helper.fail("scaffolding left at " + p);
                    }
                    BuildJobManager.data(helper.getLevel().getServer()).remove(job.id());
                })
                .thenSucceed();
    }

    // ---- 7. phase 7: capture an area into a .litematic and paste it back ----

    @GameTest(template = FLOOR)
    public static void captureRoundTrip(GameTestHelper helper) throws IOException {
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.STONE_BRICKS);
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.EAST));
        helper.setBlock(new BlockPos(4, 2, 3), Blocks.OAK_LOG.defaultBlockState()
                .setValue(BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.X));
        BlockPos door = new BlockPos(2, 2, 4);
        for (BlockPlacer.Part part : BlockPlacer.partsOf(helper.absolutePos(door), Blocks.OAK_DOOR.defaultBlockState())) {
            helper.getLevel().setBlock(part.pos(), part.state(), Block.UPDATE_CLIENTS);
        }
        BlockPos min = helper.absolutePos(new BlockPos(2, 2, 2)), max = helper.absolutePos(new BlockPos(4, 3, 4));
        Schematic captured = com.maidbuilder.common.capture.SchematicCapture.capture(helper.getLevel(), min, max, "cap", "gametest");
        byte[] bytes = com.maidbuilder.common.capture.SchematicCapture.toBytes(captured);
        Schematic read = new com.maidbuilder.core.schematic.LitematicReader().read(new java.io.ByteArrayInputStream(bytes));
        if (read.countNonAir() != 5) helper.fail("expected 5 captured blocks, got " + read.countNonAir());
        if (!"gametest".equals(read.metadata().author())) helper.fail("author not recorded");

        // Paste the capture 8 blocks further along x and compare every cell with the original.
        BlockPos offset = new BlockPos(8, 0, 0);
        Placement placement = new Placement(Convert.toIntPos(min.offset(offset)), Rotation.NONE, Mirror.NONE);
        StateResolver resolver = new StateResolver(read.minecraftDataVersion());
        for (BuildStep step : BuildPlanner.plan(read, placement, new BuildPlanner.Options(true)).steps()) {
            BlockPlacer.placeExact(helper.getLevel(), Convert.toBlockPos(step.worldPos()), resolver.resolve(step.schematicState(), placement));
        }
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            BlockState original = helper.getLevel().getBlockState(p);
            BlockState copy = helper.getLevel().getBlockState(p.offset(offset));
            if (original != copy) helper.fail("at " + p + ": " + original + " pasted as " + copy);
        }

        String sanitized = SchematicStore.sanitizeFileName("../sub/evil:name.litematic");
        if (sanitized == null || sanitized.contains("/") || sanitized.contains(":") || sanitized.startsWith(".")) {
            helper.fail("unsafe file name " + sanitized);
        }
        if (SchematicStore.sanitizeFileName("  ...  ") != null) helper.fail("empty name accepted");
        helper.succeed();
    }

    // ---- 8. recipes: the wand on TLM's altar, the quill on a crafting table ----

    @GameTest(template = FLOOR)
    public static void recipesLoaded(GameTestHelper helper) {
        var recipes = helper.getLevel().getServer().getRecipeManager();
        var wand = recipes.byKey(MaidBuilder.id("blueprint_wand"));
        if (wand.isEmpty()) helper.fail("blueprint_wand recipe missing");
        if (!(wand.get().value() instanceof com.github.tartaricacid.touhoulittlemaid.crafting.AltarRecipe altar)) {
            helper.fail("blueprint_wand is not an altar recipe: " + wand.get().value());
            return;
        }
        if (altar.getIngredients().size() != 6 || !altar.getResultItem(helper.getLevel().registryAccess())
                .is(com.maidbuilder.init.ModItems.BLUEPRINT_WAND.get())) {
            helper.fail("unexpected altar recipe " + altar.getIngredients().size());
        }
        if (recipes.byKey(MaidBuilder.id("blueprint_quill")).isEmpty()) helper.fail("blueprint_quill recipe missing");
        helper.succeed();
    }

    // ---- 9. cancelling a job stops the maid; she takes her scaffolding down and is unbound ----

    @GameTest(template = TALL_FLOOR, timeoutTicks = 4000)
    public static void cancelStopsMaidAndClearsScaffolding(GameTestHelper helper) {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("pillar", IntPos.ZERO, new IntPos(1, PILLAR_HEIGHT, 1));
        for (int y = 0; y < PILLAR_HEIGHT; y++) rb.set(0, y, 0, "minecraft:oak_planks");
        BuildJob job = createJob(helper, LitematicWriter.schematic("pillar", 3955, List.of(rb.build())), PILLAR);
        EntityMaid maid = spawnBuilderMaid(helper, job);
        IItemHandler inv = maid.getAvailableInv(false);
        ItemHandlerHelper.insertItemStacked(inv, new ItemStack(Items.OAK_PLANKS, PILLAR_HEIGHT), false);
        ItemHandlerHelper.insertItemStacked(inv, new ItemStack(Items.SCAFFOLDING, 16), false);
        int[] doneAtCancel = new int[1];

        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (job.scaffolds().isEmpty()) helper.fail("no scaffolding yet");
                })
                .thenExecute(() -> {
                    if (BuildJobManager.cancel(helper.getLevel().getServer(), job)) helper.fail("deleted while scaffolding is up");
                    doneAtCancel[0] = job.count(BuildJob.DONE);
                })
                .thenWaitUntil(() -> {
                    if (BuildJobManager.data(helper.getLevel().getServer()).get(job.id()) != null) helper.fail("job still exists");
                    if (BuilderMaidData.jobOf(maid) != null) helper.fail("maid still bound");
                })
                .thenExecute(() -> {
                    // at most the block she was already placing may have gone in after the cancel
                    if (job.count(BuildJob.DONE) > doneAtCancel[0] + 1) helper.fail("kept building after cancel");
                    for (BlockPos p : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 2, 0)), helper.absolutePos(new BlockPos(15, 20, 15)))) {
                        if (helper.getLevel().getBlockState(p).is(Blocks.SCAFFOLDING)) helper.fail("scaffolding left at " + p);
                    }
                    int scaffolding = 0;
                    for (int i = 0; i < inv.getSlots(); i++) {
                        if (inv.getStackInSlot(i).is(Items.SCAFFOLDING)) scaffolding += inv.getStackInSlot(i).getCount();
                    }
                    if (scaffolding != 16) helper.fail("maid holds " + scaffolding + " scaffolding, expected 16");
                })
                .thenSucceed();
    }

    // ---- 10. a work point far from the structure: TLM must not teleport her back while she builds ----

    @GameTest(template = FLOOR, timeoutTicks = 2400)
    public static void maidBuildsFarFromWorkPoint(GameTestHelper helper) {
        BuildJob job = createHutJob(helper);
        BlockPos farAway = helper.absolutePos(new BlockPos(8, 1, -40));
        EntityMaid maid = spawnBuilderMaid(helper, job);
        maid.getSchedulePos().setWorkPos(farAway);
        maid.getSchedulePos().setIdlePos(farAway);
        maid.getSchedulePos().setSleepPos(farAway);
        maid.getSchedulePos().restrictTo(maid);
        IItemHandler inv = maid.getAvailableInv(false);
        for (ItemStack stack : hutMaterials()) ItemHandlerHelper.insertItemStacked(inv, stack.copy(), false);
        helper.succeedWhen(() -> {
            assertHutBuilt(helper, job);
            if (!farAway.equals(maid.getSchedulePos().getWorkPos())) {
                helper.fail("work point not restored: " + maid.getSchedulePos().getWorkPos());
            }
            if (BuilderMaidData.of(maid).savedWorkPos().isPresent()) helper.fail("saved work point not cleared");
            BuildJobManager.data(helper.getLevel().getServer()).remove(job.id());
        });
    }

    // ---- 11. several maids share one job (phase 7 collaboration); logs ticks as a benchmark ----

    private static final BlockPos HALL_ORIGIN = new BlockPos(3, 2, 3);
    private static final int HALL_SIZE = 12;

    /** 12x12 floor with 3-high walls and a door-sized gap in the north wall: 276 planks. */
    private static Schematic hallSchematic() {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("hall", IntPos.ZERO, new IntPos(HALL_SIZE, 4, HALL_SIZE));
        for (int x = 0; x < HALL_SIZE; x++) {
            for (int z = 0; z < HALL_SIZE; z++) {
                rb.set(x, 0, z, "minecraft:oak_planks");
                boolean edge = x == 0 || z == 0 || x == HALL_SIZE - 1 || z == HALL_SIZE - 1;
                boolean door = z == 0 && x == HALL_SIZE / 2;
                if (!edge || door) continue;
                for (int y = 1; y <= 3; y++) rb.set(x, y, z, "minecraft:oak_planks");
            }
        }
        return LitematicWriter.schematic("hall", 3955, List.of(rb.build()));
    }

    @GameTest(template = FLOOR, timeoutTicks = 12000)
    public static void hallOneMaid(GameTestHelper helper) {
        buildHall(helper, 1);
    }

    @GameTest(template = FLOOR, timeoutTicks = 12000)
    public static void hallThreeMaids(GameTestHelper helper) {
        buildHall(helper, 3);
    }

    private static void buildHall(GameTestHelper helper, int maids) {
        BuildJob job = createJob(helper, hallSchematic(), HALL_ORIGIN);
        BlockPos chestRel = new BlockPos(1, 2, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        IItemHandler chest = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, chestAbs, null);
        if (chest == null) throw new IllegalStateException("chest has no item handler");
        ItemHandlerHelper.insertItemStacked(chest, new ItemStack(Items.OAK_PLANKS, 64 * 4), false);
        ItemHandlerHelper.insertItemStacked(chest, new ItemStack(Items.OAK_PLANKS, 20), false);
        job.toggleMaterialSource(chestAbs);
        BlockPos[] spawns = {new BlockPos(1, 2, 2), new BlockPos(2, 2, 1), new BlockPos(1, 2, 3)};
        List<EntityMaid> team = new java.util.ArrayList<>();
        for (int i = 0; i < maids; i++) team.add(spawnBuilderMaid(helper, job, spawns[i]));
        helper.succeedWhen(() -> {
            if (!job.isComplete() || job.count(BuildJob.DONE) != job.size()) {
                helper.fail("progress " + job.count(BuildJob.DONE) + "/" + job.size() + ", needs player " + job.count(BuildJob.NEEDS_PLAYER));
            }
            StringBuilder carried = new StringBuilder();
            for (EntityMaid maid : team) carried.append(' ').append(MaidBuilderGameTests.count(maid.getAvailableInv(false), Items.OAK_PLANKS));
            MaidBuilder.LOGGER.info("BENCH hall with {} maid(s): {} ticks, planks left in maids:{}, in chest: {}",
                    maids, helper.getTick(), carried, MaidBuilderGameTests.count(chest, Items.OAK_PLANKS));
            BuildJobManager.data(helper.getLevel().getServer()).remove(job.id());
        });
    }

    private static int count(IItemHandler inv, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < inv.getSlots(); i++) if (inv.getStackInSlot(i).is(item)) n += inv.getStackInSlot(i).getCount();
        return n;
    }

    // ---- 12. several maids put up scaffolding on the same tall job without getting in each other's way ----

    @GameTest(template = TALL_FLOOR, timeoutTicks = 12000)
    public static void towerThreeMaidsWithScaffolding(GameTestHelper helper) {
        buildTower(helper, 3);
    }

    @GameTest(template = TALL_FLOOR, timeoutTicks = 12000)
    public static void towerOneMaidWithScaffolding(GameTestHelper helper) {
        buildTower(helper, 1);
    }

    private static void buildTower(GameTestHelper helper, int maids) {
        int size = 5, height = 10;
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("tower", IntPos.ZERO, new IntPos(size, height, size));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    boolean edge = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                    boolean door = z == 0 && x == size / 2 && y < 2;
                    if (edge && !door) rb.set(x, y, z, "minecraft:stone_bricks");
                }
            }
        }
        BuildJob job = createJob(helper, LitematicWriter.schematic("tower", 3955, List.of(rb.build())), new BlockPos(6, 2, 6));
        BlockPos chestRel = new BlockPos(1, 2, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        IItemHandler chest = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, chestAbs, null);
        if (chest == null) throw new IllegalStateException("chest has no item handler");
        ItemHandlerHelper.insertItemStacked(chest, new ItemStack(Items.STONE_BRICKS, job.size()), false);
        job.toggleMaterialSource(chestAbs);
        BlockPos[] spawns = {new BlockPos(1, 2, 2), new BlockPos(2, 2, 1), new BlockPos(1, 2, 3)};
        List<EntityMaid> team = new java.util.ArrayList<>();
        for (int i = 0; i < maids; i++) {
            EntityMaid maid = spawnBuilderMaid(helper, job, spawns[i]);
            ItemHandlerHelper.insertItemStacked(maid.getAvailableInv(false), new ItemStack(Items.SCAFFOLDING, 16), false);
            team.add(maid);
        }
        helper.succeedWhen(() -> {
            if (!job.isComplete() || job.count(BuildJob.DONE) != job.size()) {
                helper.fail("progress " + job.count(BuildJob.DONE) + "/" + job.size() + ", needs player " + job.count(BuildJob.NEEDS_PLAYER));
            }
            if (!job.scaffolds().isEmpty()) helper.fail(job.scaffolds().size() + " scaffolding still up");
            int scaffolding = 0;
            for (EntityMaid maid : team) {
                scaffolding += count(maid.getAvailableInv(false), Items.SCAFFOLDING);
                if (maid.isShiftKeyDown()) helper.fail("a maid is still sneaking");
            }
            if (scaffolding != 16 * maids) helper.fail("maids hold " + scaffolding + " scaffolding, expected " + 16 * maids);
            MaidBuilder.LOGGER.info("BENCH tower with {} maid(s): {} ticks", maids, helper.getTick());
            BuildJobManager.data(helper.getLevel().getServer()).remove(job.id());
        });
    }

    // ---- 13. a double chest bound half by half; maid B joins after maid A already took her share ----

    @GameTest(template = FLOOR, timeoutTicks = 12000)
    public static void doubleChestLateJoiner(GameTestHelper helper) {
        BuildJob job = createJob(helper, hallSchematic(), HALL_ORIGIN);
        BlockPos leftRel = new BlockPos(1, 2, 1), rightRel = new BlockPos(1, 2, 2);
        helper.setBlock(leftRel, Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.WEST)
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        helper.setBlock(rightRel, Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.WEST)
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT));
        IItemHandler chest = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                helper.absolutePos(leftRel), null);
        if (chest == null || chest.getSlots() != 54) helper.fail("not a double chest: " + (chest == null ? null : chest.getSlots()));
        // put the planks in the second half so only the combined inventory has them
        for (int i = 0; i < 5; i++) chest.insertItem(27 + i, new ItemStack(Items.OAK_PLANKS, i < 4 ? 64 : 20), false);
        job.toggleMaterialSource(helper.absolutePos(leftRel));
        job.toggleMaterialSource(helper.absolutePos(rightRel));

        EntityMaid a = spawnBuilderMaid(helper, job, new BlockPos(1, 2, 4));
        EntityMaid[] b = new EntityMaid[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (count(a.getAvailableInv(false), Items.OAK_PLANKS) == 0) helper.fail("maid A took nothing from the double chest");
                })
                .thenExecute(() -> b[0] = spawnBuilderMaid(helper, job, new BlockPos(2, 2, 4)))
                .thenWaitUntil(() -> {
                    if (!job.isComplete()) helper.fail("progress " + job.count(BuildJob.DONE) + "/" + job.size());
                })
                .thenExecute(() -> {
                    int placedByB = job.placedBy(b[0].getUUID());
                    MaidBuilder.LOGGER.info("BENCH late joiner: A placed {}, B placed {}, {} ticks", job.placedBy(a.getUUID()), placedByB, helper.getTick());
                    if (placedByB < job.size() / 4) helper.fail("maid B only placed " + placedByB);
                    BuildJobManager.data(helper.getLevel().getServer()).remove(job.id());
                })
                .thenSucceed();
    }

    // ---- 14. the chest is empty when maid B joins: she gets half of what maid A carries ----

    @GameTest(template = FLOOR, timeoutTicks = 6000)
    public static void lateJoinerSharesTeammateMaterials(GameTestHelper helper) {
        BuildJob job = createJob(helper, hallSchematic(), HALL_ORIGIN);
        BlockPos chestRel = new BlockPos(1, 2, 1);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestAbs = helper.absolutePos(chestRel);
        IItemHandler chest = helper.getLevel().getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, chestAbs, null);
        if (chest == null) throw new IllegalStateException("chest has no item handler");
        ItemHandlerHelper.insertItemStacked(chest, new ItemStack(Items.OAK_PLANKS, 100), false);
        job.toggleMaterialSource(chestAbs);

        EntityMaid a = spawnBuilderMaid(helper, job, new BlockPos(1, 2, 4));
        EntityMaid[] b = new EntityMaid[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (count(chest, Items.OAK_PLANKS) > 0) helper.fail("maid A has not emptied the chest yet");
                })
                .thenExecute(() -> b[0] = spawnBuilderMaid(helper, job, new BlockPos(2, 2, 4)))
                .thenWaitUntil(() -> {
                    if (job.placedBy(b[0].getUUID()) < 20) helper.fail("maid B placed only " + job.placedBy(b[0].getUUID()));
                })
                .thenExecute(() -> {
                    MaidBuilder.LOGGER.info("BENCH share: A placed {}, B placed {} by tick {}", job.placedBy(a.getUUID()),
                            job.placedBy(b[0].getUUID()), helper.getTick());
                    BuildJobManager.data(helper.getLevel().getServer()).remove(job.id());
                })
                .thenSucceed();
    }

    // ---- 15. binding a double chest: either half is the same container ----

    @GameTest(template = FLOOR)
    public static void doubleChestBindsOnce(GameTestHelper helper) {
        String hash = storeSchematic(helper, hutSchematic());
        net.minecraft.server.level.ServerPlayer player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(
                helper.getLevel(), new com.mojang.authlib.GameProfile(UUID.randomUUID(), "maidbuilder_test2"));
        BuildJob job = new BuildJob(UUID.randomUUID(), player.getUUID(), "gametest", "hut", hash, helper.getLevel().dimension(),
                helper.absolutePos(HUT_ORIGIN), net.minecraft.world.level.block.Rotation.NONE, net.minecraft.world.level.block.Mirror.NONE, false, null);
        BuildJobManager.data(helper.getLevel().getServer()).add(job);
        ItemStack wand = new ItemStack(com.maidbuilder.init.ModItems.BLUEPRINT_WAND.get());
        wand.set(com.maidbuilder.init.ModDataComponents.BUILD_JOB.get(), job.id());
        BlockPos left = helper.absolutePos(new BlockPos(1, 2, 1)), right = helper.absolutePos(new BlockPos(1, 2, 2));
        helper.getLevel().setBlock(left, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.WEST)
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT), Block.UPDATE_CLIENTS);
        helper.getLevel().setBlock(right, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.WEST)
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT), Block.UPDATE_CLIENTS);
        com.maidbuilder.common.wand.WandActions.toggleMaterialSource(player, wand, left);
        if (job.materialSources().size() != 1) helper.fail("expected one source, got " + job.materialSources());
        com.maidbuilder.common.wand.WandActions.toggleMaterialSource(player, wand, right);
        if (!job.materialSources().isEmpty()) helper.fail("clicking the other half should unbind the chest, got " + job.materialSources());
        BuildJobManager.data(helper.getLevel().getServer()).remove(job.id());
        helper.succeed();
    }

    // ---- maid test helpers ----

    private static Schematic hutSchematic() {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("hut", IntPos.ZERO, new IntPos(3, 3, 3));
        for (int x = 0; x < 3; x++)
            for (int z = 0; z < 3; z++)
                rb.set(x, 0, z, "minecraft:oak_planks");
        rb.set(0, 1, 0, "minecraft:oak_log[axis=y]");
        rb.set(0, 2, 0, "minecraft:oak_log[axis=y]");
        rb.set(0, 2, 1, "minecraft:wall_torch[facing=south]");
        rb.set(2, 1, 2, "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]");
        rb.set(2, 2, 2, "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]");
        return LitematicWriter.schematic("hut", 3955, List.of(rb.build()));
    }

    private static String storeSchematic(GameTestHelper helper, Schematic schematic) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LitematicWriter.write(schematic, out);
            return SchematicStore.storeBytes(helper.getLevel().getServer(), out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BuildJob createHutJob(GameTestHelper helper) {
        return createJob(helper, hutSchematic(), HUT_ORIGIN);
    }

    private static BuildJob createJob(GameTestHelper helper, Schematic schematic, BlockPos relativeOrigin) {
        String hash = storeSchematic(helper, schematic);
        BuildJob job = new BuildJob(UUID.randomUUID(), UUID.randomUUID(), "gametest", schematic.metadata().name(), hash,
                helper.getLevel().dimension(), helper.absolutePos(relativeOrigin), net.minecraft.world.level.block.Rotation.NONE,
                net.minecraft.world.level.block.Mirror.NONE, false, null);
        try {
            job.ensureLoaded(helper.getLevel().getServer());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        BuildJobManager.data(helper.getLevel().getServer()).add(job);
        return job;
    }

    private static EntityMaid spawnBuilderMaid(GameTestHelper helper, BuildJob job) {
        return spawnBuilderMaid(helper, job, new BlockPos(3, 2, 3));
    }

    private static EntityMaid spawnBuilderMaid(GameTestHelper helper, BuildJob job, BlockPos spawn) {
        EntityMaid maid = helper.spawn(InitEntities.MAID.get(), spawn);
        maid.setSchedule(MaidSchedule.ALL);
        // Same as TLM's own game tests: home mode centred on the spawn point (otherwise the schedule
        // position defaults to 0,0,0 and she teleports there).
        maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
        maid.setHomeModeEnable(true);
        BuilderMaidData.bind(maid, job.id());
        TaskManager.findTask(TaskBuilder.UID).ifPresent(maid::setTask);
        return maid;
    }

    private static void assertHutBuilt(GameTestHelper helper, BuildJob job) {
        if (!job.isComplete()) {
            helper.fail("progress " + job.count(BuildJob.DONE) + "/" + job.size() + ", needs player " + job.count(BuildJob.NEEDS_PLAYER));
        }
        if (job.count(BuildJob.DONE) != job.size()) {
            helper.fail("finished with " + job.count(BuildJob.NEEDS_PLAYER) + " left for the player and "
                    + job.count(BuildJob.FAILED) + " failed");
        }
        helper.assertBlockPresent(Blocks.WALL_TORCH, HUT_ORIGIN.offset(0, 2, 1));
        helper.assertBlockPresent(Blocks.OAK_DOOR, HUT_ORIGIN.offset(2, 2, 2));
    }

    private static void assertHoldsNoHutMaterials(GameTestHelper helper, IItemHandler inv) {
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.is(Items.OAK_PLANKS) || s.is(Items.OAK_LOG) || s.is(Items.TORCH) || s.is(Items.OAK_DOOR)) {
                helper.fail("materials left over: " + s);
            }
        }
    }

    // ---- helpers ----

    /** Schematic with orientation-sensitive blocks; door at (2,1,0)-(2,2,0), bed foot (0,1,1) head (0,1,2). */
    private static Schematic sampleHouse() {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("house", IntPos.ZERO, new IntPos(4, 3, 3));
        for (int x = 0; x < 4; x++)
            for (int z = 0; z < 3; z++)
                rb.set(x, 0, z, "minecraft:stone_bricks");
        rb.set(1, 1, 0, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        rb.set(2, 1, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
        rb.set(2, 2, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        rb.set(0, 1, 1, "minecraft:red_bed[facing=south,occupied=false,part=foot]");
        rb.set(0, 1, 2, "minecraft:red_bed[facing=south,occupied=false,part=head]");
        rb.set(3, 1, 1, "minecraft:oak_log[axis=x]");
        rb.set(3, 1, 2, "minecraft:rail[shape=south_east,waterlogged=false]");
        rb.set(1, 1, 2, "minecraft:oak_sign[rotation=3,waterlogged=false]");
        rb.set(2, 1, 2, "minecraft:chest[facing=west,type=left,waterlogged=false]");
        return LitematicWriter.schematic("house", 3955, List.of(rb.build()));
    }

    private static BlockStateData toData(BlockState state) {
        Map<String, String> props = new LinkedHashMap<>();
        for (Property<?> p : state.getProperties()) props.put(p.getName(), valueName(state, p));
        return new BlockStateData(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), props);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
