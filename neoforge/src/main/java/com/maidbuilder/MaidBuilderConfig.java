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
    public static final ModConfigSpec.IntValue MAX_CAPTURE_VOLUME;

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
        SPEC = b.build();
    }

    private MaidBuilderConfig() {
    }
}
