package com.maidbuilder.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-side settings (config/maidbuilder-client.toml); editable in game, also on servers. */
public final class MaidBuilderClientConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue SHOW_HUD;
    public static final ModConfigSpec.DoubleValue GHOST_OPACITY;
    public static final ModConfigSpec.IntValue PREVIEW_RENDER_DISTANCE;
    public static final ModConfigSpec.BooleanValue SHOW_WRONG_BLOCKS;
    public static final ModConfigSpec.BooleanValue SHOW_BOUNDING_BOX;
    public static final ModConfigSpec.BooleanValue SHADER_OVERLAY;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        SHOW_HUD = b.comment("Show the Blueprint Wand overlay (placement, progress, missing materials) in the top-left corner.")
                .define("showHud", true);
        GHOST_OPACITY = b.comment("Opacity of the ghost blocks of a placed schematic (1 = solid).")
                .defineInRange("ghostOpacity", 0.45, 0.1, 1.0);
        PREVIEW_RENDER_DISTANCE = b.comment("Parts of the preview farther away than this many blocks are not drawn.")
                .defineInRange("previewRenderDistance", 160, 16, 512);
        SHOW_WRONG_BLOCKS = b.comment("Outline blocks in red where the world has a different block than the schematic, and in orange",
                        "the blocks the maids would clear from the schematic's air (when the server clears air).")
                .define("showWrongBlocks", true);
        SHOW_BOUNDING_BOX = b.comment("Draw a white box around the whole schematic.")
                .define("showBoundingBox", true);
        SHADER_OVERLAY = b.comment("With a shader pack (Iris) active, draw the preview on top of the shaded image so the pack",
                        "does not light it like real blocks. Turn off to let the pack shade the preview.")
                .define("shaderOverlay", true);
        SPEC = b.build();
    }

    private MaidBuilderClientConfig() {
    }
}
