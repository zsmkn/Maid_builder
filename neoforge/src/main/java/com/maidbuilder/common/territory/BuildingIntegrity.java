package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.Convert;
import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.common.StateResolver;
import com.maidbuilder.core.plan.BuildPlan;
import com.maidbuilder.core.plan.BuildPlanner;
import com.maidbuilder.core.plan.BuildStep;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.core.territory.TerritoryRules;
import com.maidbuilder.core.transform.Placement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compares registered buildings with their templates. A building works while at least 80% of its
 * counted blocks are right; air, fluids and blocks in {@link #IGNORED} (decorations) are not counted.
 * <p>Checks run in the background: a block budget per tick, each building at most every
 * {@link #RECHECK_TICKS}; a building with any unloaded block is skipped (its last result stays).
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class BuildingIntegrity {
    public static final TagKey<Block> IGNORED = TagKey.create(Registries.BLOCK, MaidBuilder.id("integrity_ignored"));
    private static final int BUDGET_PER_TICK = 4096;
    private static final int RECHECK_TICKS = 200;

    private record Targets(BlockPos[] pos, BlockState[] state) {
    }

    /** Targets per building (template placement is fixed, so they never change). */
    private static final Map<UUID, Targets> TARGETS = new HashMap<>();
    /** Game tick each building was last checked. */
    private static final Map<UUID, Long> LAST_CHECK = new HashMap<>();

    /** The check in progress. */
    @Nullable
    private static UUID checking;
    private static int index, correct, counted;

    private BuildingIntegrity() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.getTickCount();
        int budget = BUDGET_PER_TICK;
        while (budget > 0) {
            if (checking == null && !pickNext(server, now)) return;
            Found found = find(server, checking);
            if (found == null) {
                checking = null;
                continue;
            }
            Targets targets = targets(server, found.building());
            ServerLevel level = server.getLevel(found.territory().dimension());
            if (targets == null || level == null) {
                finish(found, now, -1);
                continue;
            }
            boolean unloaded = false;
            while (index < targets.pos().length && budget > 0) {
                BlockPos pos = targets.pos()[index];
                if (!level.isLoaded(pos)) {
                    unloaded = true;
                    break;
                }
                if (matches(level.getBlockState(pos), targets.state()[index])) correct++;
                counted++;
                index++;
                budget--;
            }
            if (unloaded) finish(found, now, -1); // try again later
            else if (index >= targets.pos().length) finish(found, now, counted);
            // otherwise the budget ran out: carry on next tick
        }
    }

    private record Found(Territory territory, RegisteredBuilding building) {
    }

    @Nullable
    private static Found find(MinecraftServer server, @Nullable UUID building) {
        if (building == null) return null;
        for (Territory t : TerritoryManager.data(server).all()) {
            RegisteredBuilding b = t.building(building);
            if (b != null) return new Found(t, b);
        }
        return null;
    }

    /** Starts checking the building whose last check is the oldest (and old enough). */
    private static boolean pickNext(MinecraftServer server, long now) {
        UUID best = null;
        long bestTime = Long.MAX_VALUE;
        for (Territory t : TerritoryManager.data(server).all()) {
            if (!t.isActive()) continue;
            for (RegisteredBuilding b : t.buildings()) {
                long last = LAST_CHECK.getOrDefault(b.id(), Long.MIN_VALUE);
                if (now - last < RECHECK_TICKS && last <= now) continue;
                if (last < bestTime) {
                    best = b.id();
                    bestTime = last;
                }
            }
        }
        if (best == null) return false;
        checking = best;
        index = correct = counted = 0;
        return true;
    }

    /** {@code countedTotal} -1: aborted (unloaded or unreadable), keep the old result. */
    private static void finish(Found found, long now, int countedTotal) {
        LAST_CHECK.put(found.building().id(), now);
        if (countedTotal >= 0) apply(found.territory(), found.building(), correct, countedTotal);
        checking = null;
    }

    private static void apply(Territory territory, RegisteredBuilding building, int ok, int total) {
        boolean wasWorking = building.isWorking();
        territory.updateIntegrity(building, TerritoryRules.percent(ok, total), TerritoryRules.intact(ok, total));
        if (wasWorking != building.isWorking()) {
            MaidBuilder.LOGGER.debug("Building {} ({}) is now {} at {}%", building.shortId(), building.templateId(),
                    building.isWorking() ? "working" : "damaged", building.integrity());
        }
    }

    /**
     * Checks one building right away (all at once). Returns false if part of it is not loaded or its
     * schematic is unreadable; the old result is kept then.
     */
    public static boolean checkNow(MinecraftServer server, Territory territory, RegisteredBuilding building) {
        Targets targets = targets(server, building);
        ServerLevel level = server.getLevel(territory.dimension());
        if (targets == null || level == null) return false;
        int ok = 0;
        for (int i = 0; i < targets.pos().length; i++) {
            if (!level.isLoaded(targets.pos()[i])) return false;
            if (matches(level.getBlockState(targets.pos()[i]), targets.state()[i])) ok++;
        }
        LAST_CHECK.put(building.id(), (long) server.getTickCount());
        if (building.id().equals(checking)) checking = null;
        apply(territory, building, ok, targets.pos().length);
        return true;
    }

    /** Like the builders' check, but dirt, grass, farmland and paths stand in for each other (fields get tilled, paths trodden). */
    static boolean matches(BlockState world, BlockState target) {
        return BlockPlacer.matches(world, target) || soil(world) && soil(target);
    }

    private static boolean soil(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH);
    }

    /** Forces a check of the building soon (e.g. after a repair). */
    public static void recheckSoon(UUID building) {
        LAST_CHECK.remove(building);
    }

    @Nullable
    private static Targets targets(MinecraftServer server, RegisteredBuilding building) {
        Targets cached = TARGETS.get(building.id());
        if (cached != null) return cached;
        try {
            Schematic schematic = SchematicStore.loadStored(server, building.schematicHash());
            Placement placement = Convert.placement(building.origin(), building.rotation(), building.mirror());
            BuildPlan plan = BuildPlanner.plan(schematic, placement, new BuildPlanner.Options(true));
            StateResolver resolver = new StateResolver(schematic.minecraftDataVersion());
            List<BlockPos> pos = new ArrayList<>();
            List<BlockState> state = new ArrayList<>();
            for (BuildStep step : plan.steps()) {
                BlockState target = resolver.resolve(step.schematicState(), placement);
                if (target.isAir() || !target.getFluidState().isEmpty() && target.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                        || target.is(IGNORED)) {
                    continue;
                }
                pos.add(Convert.toBlockPos(step.worldPos()));
                state.add(target);
            }
            Targets targets = new Targets(pos.toArray(BlockPos[]::new), state.toArray(BlockState[]::new));
            TARGETS.put(building.id(), targets);
            return targets;
        } catch (IOException | RuntimeException e) {
            MaidBuilder.LOGGER.error("Cannot load the template of building {} ({})", building.shortId(), building.templateId(), e);
            return null;
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TARGETS.clear();
        LAST_CHECK.clear();
        checking = null;
    }
}
