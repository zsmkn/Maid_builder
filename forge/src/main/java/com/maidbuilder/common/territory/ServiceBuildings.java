package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the service buildings do while they work (intact, in an active territory):
 * <ul>
 *   <li>trading post: a wandering trader with two llamas comes every
 *       {@link MaidBuilderConfig#TRADING_POST_INTERVAL} ticks and stays around it (travel between
 *       posts: {@link TravelActions});</li>
 *   <li>village hall: a villager moves in every {@link MaidBuilderConfig#VILLAGE_HALL_INTERVAL}
 *       ticks while fewer than {@link MaidBuilderConfig#VILLAGE_HALL_CAP} live around it;</li>
 *   <li>watchtower: hostile mobs within its radius glow, and the owner hears of raids nearby;</li>
 *   <li>shrine and canteen: maid favorability and healing, see {@link TerritoryBonuses}.</li>
 * </ul>
 * Runs every {@link #INTERVAL} ticks and only for buildings whose chunks are loaded and ticking.
 */
@Mod.EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class ServiceBuildings {
    private static final int INTERVAL = 40;
    /** Traders and villagers within this many blocks of the building count as its own. */
    private static final int NEARBY = 16;
    private static final int GLOW_TICKS = INTERVAL * 2 + 10;

    /** Raids the owner of a watchtower has been told about (by raid id and dimension). */
    private static final Set<String> REPORTED_RAIDS = new HashSet<>();

    private ServiceBuildings() {
    }

    /** Whether the territory is active and has a working building of the given kind. */
    public static boolean hasWorking(Territory territory, String bonus) {
        if (!territory.isActive()) return false;
        for (RegisteredBuilding b : territory.buildings()) {
            if (b.isWorking() && bonus.equals(bonusOf(b))) return true;
        }
        return false;
    }

    /** The working building of the given kind containing {@code pos}, or null. */
    @Nullable
    public static RegisteredBuilding workingAt(Territory territory, BlockPos pos, String bonus) {
        if (!territory.isActive()) return null;
        for (RegisteredBuilding b : territory.buildings()) {
            if (b.isWorking() && b.contains(pos) && bonus.equals(bonusOf(b))) return b;
        }
        return null;
    }

    static String bonusOf(RegisteredBuilding building) {
        TemplateDef def = TemplateRegistry.get(building.templateId());
        return def == null ? "" : def.bonus();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % INTERVAL != 0) return;
        for (Territory territory : List.copyOf(TerritoryManager.data(server).all())) {
            if (!territory.isActive()) continue;
            ServerLevel level = server.getLevel(territory.dimension());
            if (level == null) continue;
            for (RegisteredBuilding building : List.copyOf(territory.buildings())) {
                if (!building.isWorking() || !level.isPositionEntityTicking(building.center())) continue;
                switch (bonusOf(building)) {
                    case TemplateDef.BONUS_TRADING_POST -> tradingPost(level, building);
                    case TemplateDef.BONUS_VILLAGE_HALL -> villageHall(level, building);
                    case TemplateDef.BONUS_WATCHTOWER -> watchtower(level, territory, building);
                    default -> {
                    }
                }
            }
        }
    }

    // ---- trading post ----

    static void tradingPost(ServerLevel level, RegisteredBuilding building) {
        if (!MaidBuilderConfig.TRADING_POST_TRADERS.get() || !due(level, building)) return;
        schedule(level, building, MaidBuilderConfig.TRADING_POST_INTERVAL.get());
        if (!level.getEntitiesOfClass(WanderingTrader.class, building.box().inflate(NEARBY)).isEmpty()) return;
        spawnTrader(level, building);
    }

    /** A trader with two llamas at a free spot of the building; null if there is no room. */
    @Nullable
    static WanderingTrader spawnTrader(ServerLevel level, RegisteredBuilding building) {
        BlockPos spot = standSpot(level, building, building.center());
        if (spot == null) return null;
        WanderingTrader trader = EntityType.WANDERING_TRADER.spawn(level, spot, MobSpawnType.EVENT);
        if (trader == null) return null;
        trader.setDespawnDelay(MaidBuilderConfig.TRADING_POST_STAY.get());
        trader.setWanderTarget(building.center());
        trader.restrictTo(building.center(), NEARBY);
        for (int i = 0; i < 2; i++) {
            TraderLlama llama = EntityType.TRADER_LLAMA.spawn(level, spot, MobSpawnType.EVENT);
            if (llama != null) llama.setLeashedTo(trader, true);
        }
        return trader;
    }

    // ---- village hall ----

    static void villageHall(ServerLevel level, RegisteredBuilding building) {
        if (!MaidBuilderConfig.VILLAGE_HALL_VILLAGERS.get() || !due(level, building)) return;
        schedule(level, building, MaidBuilderConfig.VILLAGE_HALL_INTERVAL.get());
        if (level.getEntitiesOfClass(Villager.class, building.box().inflate(NEARBY)).size() >= MaidBuilderConfig.VILLAGE_HALL_CAP.get()) return;
        BlockPos spot = standSpot(level, building, building.center());
        if (spot != null) EntityType.VILLAGER.spawn(level, spot, MobSpawnType.EVENT);
    }

    // ---- watchtower ----

    static void watchtower(ServerLevel level, Territory territory, RegisteredBuilding building) {
        if (!MaidBuilderConfig.WATCHTOWER.get()) return;
        int r = MaidBuilderConfig.WATCHTOWER_RADIUS.get();
        AABB area = building.box().inflate(r, r / 2.0, r);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, area, m -> m instanceof Enemy && m.isAlive())) {
            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TICKS, 0, true, false));
        }
        Raid raid = level.getRaidAt(building.center());
        if (raid == null || raid.isOver()) return;
        String key = level.dimension().location() + "#" + raid.getId();
        if (!REPORTED_RAIDS.add(key)) return;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(territory.owner());
        if (owner != null) {
            BlockPos at = raid.getCenter();
            owner.sendSystemMessage(Component.translatable("message.maidbuilder.watchtower.raid", at.getX(), at.getY(), at.getZ()));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        REPORTED_RAIDS.clear();
    }

    // ---- shared ----

    private static boolean due(Level level, RegisteredBuilding building) {
        return level.getGameTime() >= building.nextEvent();
    }

    private static void schedule(Level level, RegisteredBuilding building, int interval) {
        building.setNextEvent(level.getGameTime() + interval);
        TerritoryManager.data(level.getServer()).setDirty();
    }

    /**
     * A spot inside the building where a person can stand (solid floor, two free blocks): on the
     * lowest floor, nearest to {@code near}; null if there is none.
     */
    @Nullable
    public static BlockPos standSpot(Level level, RegisteredBuilding building, BlockPos near) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos min = building.min(), max = building.max();
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos feet = new BlockPos(x, y, z);
                    if (!canStand(level, feet)) continue;
                    // low first (the floor, not the roof), then near
                    double score = feet.distSqr(near.atY(y)) + (y - min.getY()) * 64.0;
                    if (score < bestScore) {
                        best = feet;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    private static boolean canStand(Level level, BlockPos feet) {
        BlockPos below = feet.below();
        return level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
                && free(level, feet) && free(level, feet.above());
    }

    private static boolean free(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }
}
