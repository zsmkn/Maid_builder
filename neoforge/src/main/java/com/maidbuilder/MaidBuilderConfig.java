package com.maidbuilder;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side settings (saved per world in serverconfig/). */
public final class MaidBuilderConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.DoubleValue PLACE_REACH;
    public static final ModConfigSpec.BooleanValue PLACE_FLUIDS;
    public static final ModConfigSpec.IntValue MAX_SCHEMATIC_VOLUME;
    public static final ModConfigSpec.IntValue MAX_PASTE_BLOCKS;
    public static final ModConfigSpec.IntValue MAX_SCAN_PER_SEARCH;
    public static final ModConfigSpec.IntValue MAX_ATTEMPTS;
    public static final ModConfigSpec.IntValue OWNER_RANGE_WITHOUT_HOME;
    public static final ModConfigSpec.IntValue MATERIAL_SOURCE_RANGE;
    public static final ModConfigSpec.IntValue MAX_UPLOAD_BYTES;
    public static final ModConfigSpec.IntValue WORK_AREA_MARGIN;
    public static final ModConfigSpec.BooleanValue USE_SCAFFOLDING;
    public static final ModConfigSpec.IntValue MAX_SCAFFOLD_HEIGHT;
    public static final ModConfigSpec.EnumValue<com.maidbuilder.common.job.ClearMode> CLEAR_MODE;
    public static final ModConfigSpec.IntValue CLEAR_RADIUS;
    public static final ModConfigSpec.DoubleValue MAX_BREAK_HARDNESS;
    public static final ModConfigSpec.IntValue MAX_CAPTURE_VOLUME;
    public static final ModConfigSpec.IntValue MAX_FLAGS_PER_PLAYER;
    public static final ModConfigSpec.IntValue MIN_FLAG_DISTANCE_CHUNKS;
    public static final ModConfigSpec.DoubleValue RESUME_FREE_FRACTION;
    public static final ModConfigSpec.BooleanValue BONUS_NO_HOSTILE_SPAWNS;
    public static final ModConfigSpec.BooleanValue BONUS_NO_SPAWNER_MOBS;
    public static final ModConfigSpec.BooleanValue BONUS_MAID_REGEN;
    public static final ModConfigSpec.IntValue MAID_REGEN_INTERVAL;
    public static final ModConfigSpec.BooleanValue BONUS_CROP_GROWTH;
    public static final ModConfigSpec.DoubleValue CROP_GROWTH_CHANCE;
    public static final ModConfigSpec.BooleanValue BONUS_ANIMAL_BREEDING;
    public static final ModConfigSpec.DoubleValue BREEDING_COOLDOWN_FACTOR;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("building");
        PLACE_REACH = b.comment("How far (in blocks) a maid can place blocks from.")
                .defineInRange("placeReach", 4.5, 1.0, 6.0);
        PLACE_FLUIDS = b.comment("Whether water/lava source blocks and waterlogged states from schematics are built.")
                .define("placeFluids", false);
        MAX_ATTEMPTS = b.comment("How many times a maid retries a block (unreachable / cannot survive) before leaving it to the player.")
                .defineInRange("maxAttempts", 3, 1, 100);
        MAX_SCAN_PER_SEARCH = b.comment("Maximum build steps inspected each time a maid looks for her next block.")
                .defineInRange("maxScanPerSearch", 512, 16, 65536);
        OWNER_RANGE_WITHOUT_HOME = b.comment("When a builder maid is not in home mode, she only builds blocks this close to her owner.")
                .defineInRange("ownerRangeWithoutHome", 16, 4, 256);
        MATERIAL_SOURCE_RANGE = b.comment("How far a builder maid walks to a bound material container.")
                .defineInRange("materialSourceRange", 48, 4, 256);
        WORK_AREA_MARGIN = b.comment("When a block is out of reach, a builder maid looks for a way up only within this many blocks",
                        "around the structure's footprint (a circle centred on the structure). Beyond it she builds scaffolding instead.")
                .defineInRange("workAreaMargin", 8, 0, 64);
        USE_SCAFFOLDING = b.comment("Whether builder maids put up scaffolding (from their inventory or material containers)",
                        "to reach high blocks, and take it down again once the job is complete.")
                .define("useScaffolding", true);
        MAX_SCAFFOLD_HEIGHT = b.comment("Tallest scaffolding column a maid builds.")
                .defineInRange("maxScaffoldHeight", 32, 1, 256);
        CLEAR_MODE = b.comment("What builder maids break so the world matches the schematic. Applies to jobs created afterwards",
                        "(change an existing job with /maidbuilder job clearing).",
                        "OFF: nothing, wrong blocks are left for the player.",
                        "REPLACE: a wrong block where the schematic has a block is broken and replaced.",
                        "ALL: also break blocks standing in the schematic's air near the building (see clearRadius).",
                        "Containers and other block entities, unbreakable blocks and the maidbuilder:never_break tag are never broken.")
                .defineEnum("clearMode", com.maidbuilder.common.job.ClearMode.REPLACE);
        CLEAR_RADIUS = b.comment("With clearMode ALL, an air cell of the schematic is cleared only if a schematic block on the same layer",
                        "is within this horizontal distance (a circle), so a small building in a large schematic box does not",
                        "clear the whole box, and the ground below the building and the space above its roof stay as they are.")
                .defineInRange("clearRadius", 5, 1, 16);
        MAX_BREAK_HARDNESS = b.comment("Hardest block a maid breaks (stone 1.5, iron block 5, obsidian 50); harder ones are left for the player.")
                .defineInRange("maxBreakHardness", 5.0, 0.0, 100.0);
        b.pop();

        b.push("schematics");
        MAX_SCHEMATIC_VOLUME = b.comment("Largest schematic volume (x*y*z summed over regions) that will be loaded.")
                .defineInRange("maxSchematicVolume", 256 * 256 * 384, 1, Integer.MAX_VALUE);
        MAX_UPLOAD_BYTES = b.comment("Largest schematic file (bytes) a client may upload to the server.")
                .defineInRange("maxUploadBytes", 8 * 1024 * 1024, 1024, 256 * 1024 * 1024);
        MAX_PASTE_BLOCKS = b.comment("Largest number of blocks /maidbuilder paste places in one go.")
                .defineInRange("maxPasteBlocks", 1_000_000, 1, Integer.MAX_VALUE);
        MAX_CAPTURE_VOLUME = b.comment("Largest area (x*y*z) the Blueprint Quill or /maidbuilder save may capture into a .litematic.")
                .defineInRange("maxCaptureVolume", 256 * 256 * 256, 1, Integer.MAX_VALUE);
        b.pop();

        b.push("territory");
        MAX_FLAGS_PER_PLAYER = b.comment("How many territories (territory flags) one player may have, including territories whose flag was removed.")
                .defineInRange("maxFlagsPerPlayer", 10, 1, 1000);
        MIN_FLAG_DISTANCE_CHUNKS = b.comment("Minimum distance between two territory flags, in chunks (16 blocks each, measured along x or z).",
                        "Territory radii are limited to just under half of this, so territories never overlap.")
                .defineInRange("minFlagDistanceChunks", 10, 2, 128);
        RESUME_FREE_FRACTION = b.comment("A maid working at a building rests when her backpack is full, and goes back to work once this",
                        "fraction of her backpack is free again.")
                .defineInRange("resumeFreeFraction", 0.5, 0.05, 1.0);
        b.comment("Territory level bonuses (which level grants which bonus is set by the territory level data).").push("bonuses");
        BONUS_NO_HOSTILE_SPAWNS = b.comment("Hostile mobs do not spawn naturally in territories that have the no_hostile_spawns bonus.")
                .define("noHostileSpawns", true);
        BONUS_NO_SPAWNER_MOBS = b.comment("Also stop mob spawners (dungeon spawners, spawner blocks) in those territories.")
                .define("noSpawnerMobs", false);
        BONUS_MAID_REGEN = b.comment("The owner's maids slowly heal in territories that have the maid_regen bonus.")
                .define("maidRegen", true);
        MAID_REGEN_INTERVAL = b.comment("Ticks between two half-hearts of healing.")
                .defineInRange("maidRegenInterval", 100, 10, 12000);
        BONUS_CROP_GROWTH = b.comment("Crops in working greenhouse buildings grow faster in territories that have the crop_growth bonus.")
                .define("cropGrowth", true);
        CROP_GROWTH_CHANCE = b.comment("Chance that a crop's random tick in a greenhouse makes it grow regardless of its usual odds.")
                .defineInRange("cropGrowthChance", 0.2, 0.0, 1.0);
        BONUS_ANIMAL_BREEDING = b.comment("Animals in working ranch buildings can breed again sooner in territories that have the animal_breeding bonus.")
                .define("animalBreeding", true);
        BREEDING_COOLDOWN_FACTOR = b.comment("The parents' breeding cooldown is multiplied by this (vanilla: 5 minutes).")
                .defineInRange("breedingCooldownFactor", 0.5, 0.0, 1.0);
        b.pop();
        b.pop();
        SPEC = b.build();
    }

    private MaidBuilderConfig() {
    }
}
