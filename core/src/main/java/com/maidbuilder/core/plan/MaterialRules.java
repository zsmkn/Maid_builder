package com.maidbuilder.core.plan;

import com.maidbuilder.core.schematic.BlockStateData;

import java.util.List;
import java.util.Map;

/**
 * Which items a block state consumes. The mod side resolves the base item with
 * {@code Block.asItem()}; {@link #countFor} and {@link #extraItems} cover what that misses.
 * {@link #itemFor} is a name-based fallback for when the game registry is unavailable.
 */
public final class MaterialRules {
    /** Properties whose integer value is the number of items stacked in the block. */
    private static final Map<String, String> COUNT_PROPERTIES = Map.of(
            "minecraft:candle", "candles",
            "minecraft:sea_pickle", "pickles",
            "minecraft:snow", "layers",
            "minecraft:pink_petals", "flower_amount",
            "minecraft:turtle_egg", "eggs");

    private static final Map<String, String> ITEM_OVERRIDES = Map.ofEntries(
            Map.entry("minecraft:redstone_wire", "minecraft:redstone"),
            Map.entry("minecraft:tripwire", "minecraft:string"),
            Map.entry("minecraft:wheat", "minecraft:wheat_seeds"),
            Map.entry("minecraft:carrots", "minecraft:carrot"),
            Map.entry("minecraft:potatoes", "minecraft:potato"),
            Map.entry("minecraft:beetroots", "minecraft:beetroot_seeds"),
            Map.entry("minecraft:pumpkin_stem", "minecraft:pumpkin_seeds"),
            Map.entry("minecraft:attached_pumpkin_stem", "minecraft:pumpkin_seeds"),
            Map.entry("minecraft:melon_stem", "minecraft:melon_seeds"),
            Map.entry("minecraft:attached_melon_stem", "minecraft:melon_seeds"),
            Map.entry("minecraft:cocoa", "minecraft:cocoa_beans"),
            Map.entry("minecraft:sweet_berry_bush", "minecraft:sweet_berries"),
            Map.entry("minecraft:cave_vines", "minecraft:glow_berries"),
            Map.entry("minecraft:cave_vines_plant", "minecraft:glow_berries"),
            Map.entry("minecraft:kelp_plant", "minecraft:kelp"),
            Map.entry("minecraft:twisting_vines_plant", "minecraft:twisting_vines"),
            Map.entry("minecraft:weeping_vines_plant", "minecraft:weeping_vines"),
            Map.entry("minecraft:bamboo_sapling", "minecraft:bamboo"),
            Map.entry("minecraft:tall_seagrass", "minecraft:seagrass"),
            Map.entry("minecraft:big_dripleaf_stem", "minecraft:big_dripleaf"),
            Map.entry("minecraft:torchflower_crop", "minecraft:torchflower_seeds"),
            Map.entry("minecraft:pitcher_crop", "minecraft:pitcher_pod"),
            Map.entry("minecraft:nether_wart", "minecraft:nether_wart"),
            Map.entry("minecraft:farmland", "minecraft:dirt"),
            Map.entry("minecraft:dirt_path", "minecraft:dirt"),
            Map.entry("minecraft:water", "minecraft:water_bucket"),
            Map.entry("minecraft:lava", "minecraft:lava_bucket"),
            Map.entry("minecraft:powder_snow", "minecraft:powder_snow_bucket"));

    private MaterialRules() {
    }

    /** How many of the block's base item one placement consumes. */
    public static int countFor(BlockStateData s) {
        if ("double".equals(s.get("type")) && s.path().endsWith("_slab")) return 2;
        String countProperty = COUNT_PROPERTIES.get(s.name());
        if (countProperty == null && s.path().endsWith("_candle")) countProperty = "candles";
        if (countProperty != null) {
            try {
                return Math.max(1, Integer.parseInt(s.getOrDefault(countProperty, "1")));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        return 1;
    }

    /** Items consumed besides the base item, e.g. the plant in a flower pot. */
    public static List<String> extraItems(BlockStateData s) {
        if (s.name().startsWith("minecraft:potted_")) {
            return List.of("minecraft:" + s.path().substring("potted_".length()));
        }
        return List.of();
    }

    /** Name-based guess of the base item id, without access to the game registry. */
    public static String itemFor(BlockStateData s) {
        String override = ITEM_OVERRIDES.get(s.name());
        if (override != null) return override;
        String name = s.name();
        if (name.startsWith("minecraft:potted_")) return "minecraft:flower_pot";
        if (name.endsWith("wall_torch")) return name.replace("wall_torch", "torch");
        if (name.contains("_wall_")) return name.replace("_wall_", "_");
        return name;
    }
}
