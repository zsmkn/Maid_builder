package com.maidbuilder;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server-side settings (saved per world in serverconfig/). */
public final class MaidBuilderConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.DoubleValue PLACE_REACH;
    public static final ForgeConfigSpec.BooleanValue PLACE_FLUIDS;
    public static final ForgeConfigSpec.IntValue MAX_SCHEMATIC_VOLUME;
    public static final ForgeConfigSpec.IntValue MAX_PASTE_BLOCKS;
    public static final ForgeConfigSpec.IntValue MAX_SCAN_PER_SEARCH;
    public static final ForgeConfigSpec.IntValue MAX_ATTEMPTS;
    public static final ForgeConfigSpec.IntValue OWNER_RANGE_WITHOUT_HOME;
    public static final ForgeConfigSpec.IntValue MATERIAL_SOURCE_RANGE;
    public static final ForgeConfigSpec.IntValue MAX_UPLOAD_BYTES;
    public static final ForgeConfigSpec.IntValue WORK_AREA_MARGIN;
    public static final ForgeConfigSpec.BooleanValue USE_SCAFFOLDING;
    public static final ForgeConfigSpec.IntValue MAX_SCAFFOLD_HEIGHT;
    public static final ForgeConfigSpec.EnumValue<com.maidbuilder.common.job.ClearMode> CLEAR_MODE;
    public static final ForgeConfigSpec.IntValue CLEAR_RADIUS;
    public static final ForgeConfigSpec.DoubleValue MAX_BREAK_HARDNESS;
    public static final ForgeConfigSpec.IntValue MAX_CAPTURE_VOLUME;
    public static final ForgeConfigSpec.IntValue MAX_FLAGS_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue MIN_FLAG_DISTANCE_CHUNKS;
    public static final ForgeConfigSpec.DoubleValue RESUME_FREE_FRACTION;
    public static final ForgeConfigSpec.BooleanValue BONUS_NO_HOSTILE_SPAWNS;
    public static final ForgeConfigSpec.BooleanValue BONUS_NO_SPAWNER_MOBS;
    public static final ForgeConfigSpec.BooleanValue BONUS_MAID_REGEN;
    public static final ForgeConfigSpec.IntValue MAID_REGEN_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue BONUS_CROP_GROWTH;
    public static final ForgeConfigSpec.DoubleValue CROP_GROWTH_CHANCE;
    public static final ForgeConfigSpec.BooleanValue BONUS_ANIMAL_BREEDING;
    public static final ForgeConfigSpec.DoubleValue BREEDING_COOLDOWN_FACTOR;
    public static final ForgeConfigSpec.BooleanValue TRADING_POST_TRADERS;
    public static final ForgeConfigSpec.IntValue TRADING_POST_INTERVAL;
    public static final ForgeConfigSpec.IntValue TRADING_POST_STAY;
    public static final ForgeConfigSpec.BooleanValue TRAVEL;
    public static final ForgeConfigSpec.ConfigValue<String> TRAVEL_COST_ITEM;
    public static final ForgeConfigSpec.IntValue TRAVEL_COST_COUNT;
    public static final ForgeConfigSpec.BooleanValue VILLAGE_HALL_VILLAGERS;
    public static final ForgeConfigSpec.IntValue VILLAGE_HALL_INTERVAL;
    public static final ForgeConfigSpec.IntValue VILLAGE_HALL_CAP;
    public static final ForgeConfigSpec.BooleanValue WATCHTOWER;
    public static final ForgeConfigSpec.IntValue WATCHTOWER_RADIUS;
    public static final ForgeConfigSpec.BooleanValue SHRINE_FAVORABILITY;
    public static final ForgeConfigSpec.BooleanValue CANTEEN_REGEN;
    public static final ForgeConfigSpec.BooleanValue SHOP;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("building");
        PLACE_REACH = b.comment("How far (in blocks) a maid can place blocks from.")
                .defineInRange("placeReach", 4.5, 1.0, 6.0);
        PLACE_FLUIDS = b.comment("Whether water/lava source blocks and waterlogged states from schematics are built. Territory templates always build theirs.")
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
        b.comment("What the service buildings (trading post, village hall, watchtower, shrine, canteen) do while they are intact.").push("services");
        TRADING_POST_TRADERS = b.comment("A wandering trader (with two llamas) comes to each working trading post.")
                .define("tradingPostTraders", true);
        TRADING_POST_INTERVAL = b.comment("Ticks between two traders at a trading post (36000 = 30 minutes).")
                .defineInRange("tradingPostInterval", 36000, 1200, 1728000);
        TRADING_POST_STAY = b.comment("How long (ticks) a trader stays before leaving (vanilla traders: 48000).")
                .defineInRange("tradingPostStay", 24000, 1200, 1728000);
        TRAVEL = b.comment("Using the cartography table of a working trading post takes the owner to another of their trading posts.",
                        "Sneak to use the table as usual.")
                .define("travel", true);
        TRAVEL_COST_ITEM = b.comment("Item paid for each trip (from the player's inventory; free in creative mode).")
                .define("travelCostItem", "minecraft:emerald");
        TRAVEL_COST_COUNT = b.comment("How many of that item a trip costs (0: free).")
                .defineInRange("travelCostCount", 1, 0, 64);
        VILLAGE_HALL_VILLAGERS = b.comment("A villager moves into each working village hall now and then, up to a limit.")
                .define("villageHallVillagers", true);
        VILLAGE_HALL_INTERVAL = b.comment("Ticks between two villagers moving in (24000 = 20 minutes).")
                .defineInRange("villageHallInterval", 24000, 1200, 1728000);
        VILLAGE_HALL_CAP = b.comment("No villager moves in while this many villagers live around the hall (within 16 blocks).")
                .defineInRange("villageHallCap", 3, 1, 64);
        WATCHTOWER = b.comment("Working watchtowers make hostile mobs nearby glow and tell the owner when a raid starts nearby.")
                .define("watchtower", true);
        WATCHTOWER_RADIUS = b.comment("How far (blocks, horizontally) a watchtower sees.")
                .defineInRange("watchtowerRadius", 48, 8, 128);
        SHRINE_FAVORABILITY = b.comment("The owner's maids in a territory with a working shrine gain favorability once a day (TLM's +2, like sleeping).")
                .define("shrineFavorability", true);
        CANTEEN_REGEN = b.comment("The owner's maids in a territory with a working canteen heal (as the maid_regen bonus; twice as fast with both).")
                .define("canteenRegen", true);
        SHOP = b.comment("Working building supplies shops sell building blocks for emeralds while their clerk (a maid working there) is in.",
                        "Goods, prices and daily limits come from data packs (maidbuilder/shop/*.json).")
                .define("shop", true);
        b.pop();
        b.pop();
        SPEC = b.build();
    }

    private MaidBuilderConfig() {
    }
}
