package com.maidbuilder.client.config;

import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.client.MaidBuilderClientConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;

/** The settings shown on the settings screen, grouped like the 1.21.1 screen. */
final class ConfigSections {
    private ConfigSections() {
    }

    static ConfigEntry.Section client() {
        return section(MaidBuilderClientConfig.SPEC, "maidbuilder.configuration.section.maidbuilder.client.toml",
                MaidBuilderClientConfig.SHOW_HUD, MaidBuilderClientConfig.GHOST_OPACITY, MaidBuilderClientConfig.PREVIEW_RENDER_DISTANCE,
                MaidBuilderClientConfig.SHOW_WRONG_BLOCKS, MaidBuilderClientConfig.SHOW_BOUNDING_BOX, MaidBuilderClientConfig.SHADER_OVERLAY);
    }

    static List<ConfigEntry.Section> server() {
        return List.of(
                section(MaidBuilderConfig.SPEC, "maidbuilder.configuration.building",
                        MaidBuilderConfig.PLACE_REACH, MaidBuilderConfig.PLACE_FLUIDS, MaidBuilderConfig.MAX_ATTEMPTS,
                        MaidBuilderConfig.MAX_SCAN_PER_SEARCH, MaidBuilderConfig.OWNER_RANGE_WITHOUT_HOME,
                        MaidBuilderConfig.MATERIAL_SOURCE_RANGE, MaidBuilderConfig.WORK_AREA_MARGIN,
                        MaidBuilderConfig.USE_SCAFFOLDING, MaidBuilderConfig.MAX_SCAFFOLD_HEIGHT,
                        MaidBuilderConfig.CLEAR_MODE, MaidBuilderConfig.CLEAR_RADIUS, MaidBuilderConfig.MAX_BREAK_HARDNESS),
                section(MaidBuilderConfig.SPEC, "maidbuilder.configuration.schematics",
                        MaidBuilderConfig.MAX_SCHEMATIC_VOLUME, MaidBuilderConfig.MAX_UPLOAD_BYTES,
                        MaidBuilderConfig.MAX_PASTE_BLOCKS, MaidBuilderConfig.MAX_CAPTURE_VOLUME),
                withSubsections(section(MaidBuilderConfig.SPEC, "maidbuilder.configuration.territory",
                                MaidBuilderConfig.MAX_FLAGS_PER_PLAYER, MaidBuilderConfig.MIN_FLAG_DISTANCE_CHUNKS,
                                MaidBuilderConfig.RESUME_FREE_FRACTION),
                        section(MaidBuilderConfig.SPEC, "maidbuilder.configuration.bonuses",
                                MaidBuilderConfig.BONUS_NO_HOSTILE_SPAWNS, MaidBuilderConfig.BONUS_NO_SPAWNER_MOBS,
                                MaidBuilderConfig.BONUS_MAID_REGEN, MaidBuilderConfig.MAID_REGEN_INTERVAL,
                                MaidBuilderConfig.BONUS_CROP_GROWTH, MaidBuilderConfig.CROP_GROWTH_CHANCE,
                                MaidBuilderConfig.BONUS_ANIMAL_BREEDING, MaidBuilderConfig.BREEDING_COOLDOWN_FACTOR),
                        section(MaidBuilderConfig.SPEC, "maidbuilder.configuration.services",
                                MaidBuilderConfig.TRADING_POST_TRADERS, MaidBuilderConfig.TRADING_POST_INTERVAL, MaidBuilderConfig.TRADING_POST_STAY,
                                MaidBuilderConfig.TRAVEL, MaidBuilderConfig.TRAVEL_COST_ITEM, MaidBuilderConfig.TRAVEL_COST_COUNT,
                                MaidBuilderConfig.VILLAGE_HALL_VILLAGERS, MaidBuilderConfig.VILLAGE_HALL_INTERVAL, MaidBuilderConfig.VILLAGE_HALL_CAP,
                                MaidBuilderConfig.WATCHTOWER, MaidBuilderConfig.WATCHTOWER_RADIUS,
                                MaidBuilderConfig.SHRINE_FAVORABILITY, MaidBuilderConfig.CANTEEN_REGEN, MaidBuilderConfig.SHOP)));
    }

    private static ConfigEntry.Section withSubsections(ConfigEntry.Section section, ConfigEntry.Section... subsections) {
        return new ConfigEntry.Section(section.key(), section.entries(), List.of(subsections));
    }

    private static ConfigEntry.Section section(ForgeConfigSpec spec, String key, ForgeConfigSpec.ConfigValue<?>... values) {
        List<ConfigEntry> entries = new ArrayList<>(values.length);
        for (ForgeConfigSpec.ConfigValue<?> value : values) entries.add(new ConfigEntry(spec, value));
        return new ConfigEntry.Section(key, entries);
    }
}
