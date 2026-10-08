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
                        MaidBuilderConfig.USE_SCAFFOLDING, MaidBuilderConfig.MAX_SCAFFOLD_HEIGHT),
                section(MaidBuilderConfig.SPEC, "maidbuilder.configuration.schematics",
                        MaidBuilderConfig.MAX_SCHEMATIC_VOLUME, MaidBuilderConfig.MAX_UPLOAD_BYTES,
                        MaidBuilderConfig.MAX_PASTE_BLOCKS, MaidBuilderConfig.MAX_CAPTURE_VOLUME));
    }

    private static ConfigEntry.Section section(ForgeConfigSpec spec, String key, ForgeConfigSpec.ConfigValue<?>... values) {
        List<ConfigEntry> entries = new ArrayList<>(values.length);
        for (ForgeConfigSpec.ConfigValue<?> value : values) entries.add(new ConfigEntry(spec, value));
        return new ConfigEntry.Section(key, entries);
    }
}
