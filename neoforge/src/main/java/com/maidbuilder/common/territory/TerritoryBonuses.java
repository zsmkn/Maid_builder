package com.maidbuilder.common.territory;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.favorability.Type;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Level bonuses: no hostile spawns, maid regeneration (whole territory), faster crops in
 * greenhouses and shorter breeding cooldowns in ranches (inside working buildings of that kind);
 * also the maid side of the service buildings: healing from a canteen, favorability from a shrine.
 * Each can be switched off in the server config. Territory lookups use the chunk index, so the
 * frequent spawn and crop events stay cheap.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class TerritoryBonuses {
    public static final String NO_HOSTILE_SPAWNS = "no_hostile_spawns";
    public static final String MAID_REGEN = "maid_regen";
    public static final String CROP_GROWTH = "crop_growth";
    public static final String ANIMAL_BREEDING = "animal_breeding";
    private static final int VANILLA_BREEDING_COOLDOWN = 6000;

    /** Parents that just bred inside a ranch; vanilla sets their cooldown right after the event, so it is shortened a tick later. */
    private static final List<Animal> BRED = new ArrayList<>();

    private TerritoryBonuses() {
    }

    /** The active territory at {@code pos} if it has {@code bonus}. */
    @Nullable
    public static Territory withBonus(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos, String bonus) {
        Territory t = TerritoryManager.data(server).at(dimension, pos);
        return t != null && t.hasBonus(bonus) ? t : null;
    }

    /** Whether {@code pos} is inside a working building of the territory with the given range bonus. */
    public static boolean inBonusBuilding(Territory territory, BlockPos pos, String buildingBonus) {
        for (RegisteredBuilding b : territory.buildings()) {
            if (!b.isWorking() || !b.contains(pos)) continue;
            TemplateDef def = TemplateRegistry.get(b.templateId());
            if (def != null && buildingBonus.equals(def.bonus())) return true;
        }
        return false;
    }

    // ---- no hostile spawns ----

    @SubscribeEvent
    public static void onSpawnPosition(MobSpawnEvent.PositionCheck event) {
        if (!MaidBuilderConfig.BONUS_NO_HOSTILE_SPAWNS.get()) return;
        Mob mob = event.getEntity();
        if (mob.getType().getCategory() != MobCategory.MONSTER) return;
        MobSpawnType type = event.getSpawnType();
        boolean natural = type == MobSpawnType.NATURAL || type == MobSpawnType.CHUNK_GENERATION || type == MobSpawnType.PATROL
                || type == MobSpawnType.REINFORCEMENT || type == MobSpawnType.EVENT;
        boolean spawner = type == MobSpawnType.SPAWNER || type == MobSpawnType.TRIAL_SPAWNER;
        if (!natural && !(spawner && MaidBuilderConfig.BONUS_NO_SPAWNER_MOBS.get())) return;
        ServerLevel level = event.getLevel().getLevel();
        if (withBonus(level.getServer(), level.dimension(), BlockPos.containing(event.getX(), event.getY(), event.getZ()), NO_HOSTILE_SPAWNS) != null) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    // ---- maid regeneration ----

    /** Half a heart per interval from the maid_regen bonus, and as much again from a working canteen. */
    @SubscribeEvent
    public static void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        if (!(maid.level() instanceof ServerLevel level)) return;
        if ((maid.tickCount + maid.getId()) % SHRINE_INTERVAL == 0) shrine(level, maid);
        if ((maid.tickCount + maid.getId()) % MaidBuilderConfig.MAID_REGEN_INTERVAL.get() != 0) return;
        if (!maid.isAlive() || maid.getHealth() >= maid.getMaxHealth()) return;
        Territory t = ownTerritory(level, maid);
        if (t == null) return;
        float heal = 0;
        if (MaidBuilderConfig.BONUS_MAID_REGEN.get() && t.hasBonus(MAID_REGEN)) heal++;
        if (MaidBuilderConfig.CANTEEN_REGEN.get() && ServiceBuildings.hasWorking(t, TemplateDef.BONUS_CANTEEN)) heal++;
        if (heal > 0) maid.heal(heal);
    }

    /** The territory the maid stands in, if her owner owns it. */
    @Nullable
    private static Territory ownTerritory(ServerLevel level, EntityMaid maid) {
        Territory t = TerritoryManager.data(level.getServer()).at(level.dimension(), maid.blockPosition());
        return t != null && t.owner().equals(maid.getOwnerUUID()) ? t : null;
    }

    // ---- shrine favorability ----

    private static final int SHRINE_INTERVAL = 200;
    /** TLM favorability gained at a shrine: as much as sleeping, at most once a day (TLM keeps the cooldown per maid). */
    public static final Type SHRINE_PRAYER = new Type("MaidBuilderShrine", 2, 24000);

    private static void shrine(ServerLevel level, EntityMaid maid) {
        if (!MaidBuilderConfig.SHRINE_FAVORABILITY.get() || !maid.isAlive()) return;
        Territory t = ownTerritory(level, maid);
        if (t != null && ServiceBuildings.hasWorking(t, TemplateDef.BONUS_SHRINE)) maid.getFavorabilityManager().apply(SHRINE_PRAYER);
    }

    // ---- greenhouse crops ----

    @SubscribeEvent
    public static void onCropGrow(CropGrowEvent.Pre event) {
        if (!MaidBuilderConfig.BONUS_CROP_GROWTH.get() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getResult() != CropGrowEvent.Pre.Result.DEFAULT) return;
        Territory t = withBonus(level.getServer(), level.dimension(), event.getPos(), CROP_GROWTH);
        if (t == null || !inBonusBuilding(t, event.getPos(), TemplateDef.BONUS_GREENHOUSE)) return;
        if (level.random.nextDouble() < MaidBuilderConfig.CROP_GROWTH_CHANCE.get()) event.setResult(CropGrowEvent.Pre.Result.GROW);
    }

    // ---- ranch breeding ----

    @SubscribeEvent
    public static void onBabySpawn(BabyEntitySpawnEvent event) {
        if (!MaidBuilderConfig.BONUS_ANIMAL_BREEDING.get()) return;
        if (!(event.getParentA() instanceof Animal a) || !(a.level() instanceof ServerLevel level)) return;
        Territory t = withBonus(level.getServer(), level.dimension(), a.blockPosition(), ANIMAL_BREEDING);
        if (t == null || !inBonusBuilding(t, a.blockPosition(), TemplateDef.BONUS_RANCH)) return;
        BRED.add(a);
        if (event.getParentB() instanceof Animal b) BRED.add(b);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (BRED.isEmpty()) return;
        int cooldown = (int) Math.round(VANILLA_BREEDING_COOLDOWN * MaidBuilderConfig.BREEDING_COOLDOWN_FACTOR.get());
        for (Animal animal : BRED) {
            if (animal.isAlive() && animal.getAge() > cooldown) animal.setAge(cooldown);
        }
        BRED.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BRED.clear();
    }
}
