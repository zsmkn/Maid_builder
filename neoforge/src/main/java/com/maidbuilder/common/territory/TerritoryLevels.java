package com.maidbuilder.common.territory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.core.territory.BuildingRequirement;
import com.maidbuilder.core.territory.LevelDef;
import com.maidbuilder.core.territory.LevelTable;
import com.maidbuilder.core.territory.TerritoryRules;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Territory levels from data packs: {@code data/<namespace>/maidbuilder/territory_levels/*.json},
 * one level per file (a data pack overrides a level by using the same file id).
 * <pre>
 * { "level": 2, "radius": 56, "prosperity": 30,
 *   "required_buildings": [ { "group": "warehouse" }, { "category": "production", "count": 3 } ],
 *   "upgrade_items": { "#minecraft:logs": 64, "minecraft:iron_ingot": 16 },
 *   "resident_points": 3, "resident_cap": 4, "bonuses": [ "maid_regen" ] }
 * </pre>
 * The radius limit depends on the server config (flag spacing), so the table is built on first use
 * after a reload or a config change.
 */
public final class TerritoryLevels extends SimpleJsonResourceReloadListener {
    public static final String DIRECTORY = "maidbuilder/territory_levels";

    private static final Codec<BuildingRequirement> REQUIREMENT = RecordCodecBuilder.<RequirementJson>create(i -> i.group(
            Codec.STRING.optionalFieldOf("group").forGetter(RequirementJson::group),
            Codec.STRING.optionalFieldOf("category").forGetter(RequirementJson::category),
            Codec.intRange(1, 1000).optionalFieldOf("count", 1).forGetter(RequirementJson::count)
    ).apply(i, RequirementJson::new)).comapFlatMap(RequirementJson::toRequirement, RequirementJson::of);

    private record RequirementJson(Optional<String> group, Optional<String> category, int count) {
        DataResult<BuildingRequirement> toRequirement() {
            if (group.isPresent() == category.isPresent()) return DataResult.error(() -> "set exactly one of \"group\" and \"category\"");
            return DataResult.success(new BuildingRequirement(group.orElse(null), category.orElse(null), count));
        }

        static RequirementJson of(BuildingRequirement r) {
            return new RequirementJson(Optional.ofNullable(r.group()), Optional.ofNullable(r.category()), r.count());
        }
    }

    public static final Codec<LevelDef> LEVEL = RecordCodecBuilder.create(i -> i.group(
            Codec.intRange(1, 1000).fieldOf("level").forGetter(LevelDef::level),
            Codec.intRange(1, 10000).fieldOf("radius").forGetter(LevelDef::radius),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("prosperity", 0).forGetter(LevelDef::prosperity),
            REQUIREMENT.listOf().optionalFieldOf("required_buildings", List.of()).forGetter(LevelDef::requiredBuildings),
            Codec.unboundedMap(Codec.STRING, Codec.intRange(1, Integer.MAX_VALUE)).optionalFieldOf("upgrade_items", Map.of())
                    .forGetter(LevelDef::upgradeItems),
            Codec.intRange(0, 10000).optionalFieldOf("resident_points", 0).forGetter(LevelDef::residentPoints),
            Codec.intRange(0, 10000).optionalFieldOf("resident_cap", 0).forGetter(LevelDef::residentCap),
            Codec.STRING.listOf().xmap(Set::copyOf, List::copyOf).optionalFieldOf("bonuses", Set.of()).forGetter(LevelDef::bonuses)
    ).apply(i, LevelDef::new));

    private static volatile List<LevelDef> loaded = List.of();
    private static volatile LevelTable table;
    private static volatile int tableMaxRadius = -1;

    public TerritoryLevels() {
        super(new Gson(), DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
        List<LevelDef> defs = new ArrayList<>();
        files.forEach((id, json) -> LEVEL.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> MaidBuilder.LOGGER.error("Bad territory level {}: {}", id, error))
                .ifPresent(defs::add));
        setDefinitions(defs);
        MaidBuilder.LOGGER.info("Loaded {} territory levels", defs.size());
    }

    /** Replaces the definitions (also used by game tests). */
    public static synchronized void setDefinitions(List<LevelDef> defs) {
        loaded = List.copyOf(defs);
        table = null;
    }

    public static List<LevelDef> definitions() {
        return loaded;
    }

    /** Largest radius the current flag spacing allows. */
    public static int maxRadius() {
        return TerritoryRules.maxRadius(minFlagDistance());
    }

    /** Minimum distance between two flags, in blocks. */
    public static int minFlagDistance() {
        int chunks = MaidBuilderConfig.SPEC.isLoaded() ? MaidBuilderConfig.MIN_FLAG_DISTANCE_CHUNKS.get() : 10;
        return chunks * 16;
    }

    public static LevelTable table() {
        LevelTable t = table;
        int max = maxRadius();
        if (t != null && tableMaxRadius == max) return t;
        synchronized (TerritoryLevels.class) {
            List<String> warnings = new ArrayList<>();
            t = LevelTable.of(loaded, max, warnings);
            warnings.forEach(w -> MaidBuilder.LOGGER.warn("Territory levels: {}", w));
            table = t;
            tableMaxRadius = max;
            return t;
        }
    }
}
