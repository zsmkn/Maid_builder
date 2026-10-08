package com.maidbuilder.core.plan;

import com.maidbuilder.core.schematic.BlockStateData;

import java.util.List;
import java.util.Set;

/**
 * Name/property based heuristics for how a block must be placed. They only decide build
 * order; the mod side additionally checks {@code canSurvive} at placement time.
 */
public final class BlockCategories {
    /** Blocks whose name contains one of these fragments need a supporting neighbour. */
    private static final List<String> ATTACHED_FRAGMENTS = List.of(
            "torch", "button", "lever", "carpet", "pressure_plate", "redstone_wire", "repeater", "comparator",
            "_door", "_bed", "sapling", "propagule", "ladder", "vine", "rail", "_sign", "banner", "lantern",
            "candle", "tripwire", "sea_pickle", "coral", "kelp", "seagrass", "lichen", "sculk_vein", "roots",
            "fungus", "_tulip", "dripleaf", "petals", "mushroom", "_bush", "fern", "_crop", "_stem", "_head",
            "_skull", "amethyst_bud", "amethyst_cluster", "pointed_dripstone", "spore_blossom", "frogspawn",
            "lily_pad", "azalea", "sprouts", "cocoa", "cactus", "sugar_cane", "bamboo", "chorus", "nether_wart",
            "bell", "hook", "scaffolding", "chain");
    private static final Set<String> ATTACHED_EXACT = Set.of(
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:grass", "minecraft:dandelion",
            "minecraft:poppy", "minecraft:blue_orchid", "minecraft:allium", "minecraft:azure_bluet",
            "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley", "minecraft:wither_rose",
            "minecraft:sunflower", "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony",
            "minecraft:torchflower", "minecraft:pitcher_plant", "minecraft:wheat", "minecraft:carrots",
            "minecraft:potatoes", "minecraft:beetroots", "minecraft:snow", "minecraft:large_fern",
            "minecraft:hanging_roots", "minecraft:closed_eyeblossom", "minecraft:open_eyeblossom",
            "minecraft:flower_pot");
    /** Names containing these fragments are excluded from {@link #ATTACHED_FRAGMENTS} matches. */
    private static final List<String> NOT_ATTACHED_FRAGMENTS = List.of(
            "trapdoor", "_block", "mushroom_stem", "crimson_stem", "warped_stem", "bamboo_planks",
            "bamboo_mosaic", "bamboo_slab", "bamboo_stairs", "bamboo_fence", "chiseled_bookshelf", "redstone_lamp",
            "jack_o_lantern", "sea_lantern", "mangrove_roots", "_leaves", "_planks", "_log", "_wood", "hyphae");

    private static final Set<String> GRAVITY_EXACT = Set.of(
            "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:suspicious_sand",
            "minecraft:suspicious_gravel", "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil",
            "minecraft:dragon_egg");

    private static final Set<String> FLUIDS = Set.of("minecraft:water", "minecraft:lava", "minecraft:bubble_column");

    /** Blocks that can never be built by hand. */
    private static final Set<String> UNBUILDABLE = Set.of(
            "minecraft:piston_head", "minecraft:moving_piston", "minecraft:fire", "minecraft:soul_fire",
            "minecraft:nether_portal", "minecraft:end_portal", "minecraft:end_gateway", "minecraft:frosted_ice",
            "minecraft:barrier", "minecraft:structure_void", "minecraft:light", "minecraft:bedrock",
            "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault", "minecraft:command_block",
            "minecraft:chain_command_block", "minecraft:repeating_command_block", "minecraft:structure_block",
            "minecraft:jigsaw", "minecraft:end_portal_frame", "minecraft:reinforced_deepslate",
            "minecraft:budding_amethyst");

    private BlockCategories() {
    }

    /**
     * The second half of a two-block structure (upper door / tall plant half, bed head). It is placed
     * together with its primary half and never on its own.
     */
    public static boolean isSecondaryPart(BlockStateData s) {
        return "upper".equals(s.get("half")) || "head".equals(s.get("part"));
    }

    public static boolean isFluid(BlockStateData s) {
        return FLUIDS.contains(s.name());
    }

    public static boolean isUnbuildable(BlockStateData s) {
        return UNBUILDABLE.contains(s.name());
    }

    public static boolean isGravity(BlockStateData s) {
        return GRAVITY_EXACT.contains(s.name()) || s.name().endsWith("_concrete_powder");
    }

    public static boolean needsSupport(BlockStateData s) {
        if (ATTACHED_EXACT.contains(s.name())) return true;
        if (s.name().startsWith("minecraft:potted_")) return false;
        String path = s.path();
        for (String f : NOT_ATTACHED_FRAGMENTS) {
            if (path.contains(f)) return false;
        }
        for (String f : ATTACHED_FRAGMENTS) {
            if (path.contains(f)) return true;
        }
        return false;
    }

    public static BuildPhase phaseOf(BlockStateData s) {
        if (isFluid(s) || isGravity(s)) return BuildPhase.GRAVITY_AND_FLUIDS;
        if (needsSupport(s)) return BuildPhase.ATTACHED;
        return BuildPhase.SOLID;
    }
}
