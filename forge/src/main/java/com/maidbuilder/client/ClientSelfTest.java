package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.client.config.ConfigOverviewScreen;
import com.maidbuilder.client.preview.GhostPreview;
import com.maidbuilder.client.preview.ShaderCompat;
import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.item.WandPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Development-only rendering smoke test, enabled with {@code -Dmaidbuilder.selftest=true}:
 * once in a world it shows a ghost preview in front of the player, saves a screenshot to
 * {@code screenshots/maidbuilder_selftest.png}, then opens the material list for it, saves
 * {@code screenshots/maidbuilder_selftest_materials.png}, then the settings screens
 * ({@code maidbuilder_selftest_config0..3.png}; 4..6: territory, bonuses, clearing; 7..8: services), the preview after raising the ghost opacity
 * ({@code maidbuilder_selftest_opacity.png}), a Blueprint Quill selecting a hut
 * ({@code maidbuilder_selftest_quill.png}), a preview that clears the schematic's air
 * ({@code maidbuilder_selftest_clear.png}: orange boxes on the dirt inside and just outside the
 * house only) and closes the game.
 */
final class ClientSelfTest {
    static final boolean ENABLED = Boolean.getBoolean("maidbuilder.selftest");
    private static int ticks = -1;
    @Nullable
    static GhostPreview preview;

    private ClientSelfTest() {
    }

    /** Returns true while the self test owns the preview. */
    static boolean tick(Minecraft mc) {
        if (!ENABLED || mc.player == null || mc.level == null) return false;
        ticks++;
        if (ticks == 40) {
            BlockPos base = mc.player.blockPosition().relative(mc.player.getDirection(), 4);
            WandPlacement placement = new WandPlacement("selftest", "", base, Rotation.NONE, Mirror.NONE);
            preview = GhostPreview.create(sample(), placement);
            MaidBuilder.LOGGER.info("[selftest] preview with {} blocks at {}", preview.total(), base);
            wandScene(mc, placement);
        }
        if (preview != null) preview.tick(mc.level, 8192);
        if (ticks == 120) {
            Screenshot.grab(mc.gameDirectory, "maidbuilder_selftest.png", mc.getMainRenderTarget(),
                    msg -> MaidBuilder.LOGGER.info("[selftest] {}", msg.getString()));
        }
        // With a shader pack: the same view with the pack shading the preview, for comparison.
        if (ShaderCompat.shaderPackInUse()) {
            if (ticks == 121) MaidBuilderClientConfig.SHADER_OVERLAY.set(false);
            if (ticks == 124) shot(mc, "maidbuilder_selftest_inlevel.png");
            if (ticks == 125) MaidBuilderClientConfig.SHADER_OVERLAY.set(true);
        }
        if (ticks == 125) {
            // Every row state on one screen: some items missing, stone bricks partly there, the rest enough.
            for (ItemStack stack : List.of(new ItemStack(Items.OAK_PLANKS, 64), new ItemStack(Items.STONE_BRICKS, 5),
                    new ItemStack(Items.OAK_SLAB, 64), new ItemStack(Items.GLASS, 64), new ItemStack(Items.CHEST),
                    new ItemStack(Items.OAK_DOOR))) {
                mc.player.getInventory().add(stack);
            }
            mc.setScreen(MaterialListScreen.forPreview());
        }
        if (ticks == 145) {
            Screenshot.grab(mc.gameDirectory, "maidbuilder_selftest_materials.png", mc.getMainRenderTarget(),
                    msg -> MaidBuilder.LOGGER.info("[selftest] {}", msg.getString()));
        }
        // Settings screen: the overview, then the client and the server section.
        if (ticks == 160) {
            mc.setScreen(new ConfigOverviewScreen(null));
        }
        if (ticks == 165 || ticks == 180 || ticks == 195) shot(mc, "maidbuilder_selftest_config" + (ticks - 165) / 15 + ".png");
        if (ticks == 170) openSection(mc, "client");
        if (ticks == 185) {
            mc.setScreen(new ConfigOverviewScreen(null));
        }
        if (ticks == 187) openSection(mc, "server");
        if (ticks == 197) {
            // first section of the server page: building
            if (mc.screen == null || !press(mc.screen, Component.translatable("maidbuilder.configuration.building").getString())) {
                MaidBuilder.LOGGER.warn("[selftest] no edit button");
            }
        }
        if (ticks == 205) shot(mc, "maidbuilder_selftest_config3.png");
        // A client setting applies at once: the same preview, nearly opaque.
        if (ticks == 206) {
            mc.setScreen(null);
            MaidBuilderClientConfig.GHOST_OPACITY.set(0.9);
        }
        if (ticks == 209) shot(mc, "maidbuilder_selftest_opacity.png");
        // Blueprint Quill: a small hut with an overhang selected by one click (removed again afterwards).
        if (ticks == 210) {
            MaidBuilderClientConfig.GHOST_OPACITY.set(0.45);
            mc.setScreen(null);
            MaidBuilder.LOGGER.info("[selftest] done: {} missing, {} wrong", preview.count(GhostPreview.MISSING), preview.count(GhostPreview.WRONG));
            preview.close();
            preview = null;
            quillScene(mc, true);
        }
        if (ticks == 230) shot(mc, "maidbuilder_selftest_quill.png");
        if (ticks == 232) quillScene(mc, false);
        // Clearing preview: radius 3 around a house in a 13x4x13 box.
        if (ticks == 240) {
            BlockPos center = mc.player.blockPosition().relative(mc.player.getDirection(), 10);
            clearOrigin = center.offset(-6, 0, -6);
            clearScene(mc, true);
            preview = GhostPreview.create(clearSample(), new WandPlacement("selftest_clear", "", clearOrigin, Rotation.NONE, Mirror.NONE), 3);
        }
        if (ticks == 270) {
            MaidBuilder.LOGGER.info("[selftest] clearing preview: {} to clear (expected 2), {} wrong",
                    preview.count(GhostPreview.TO_CLEAR), preview.count(GhostPreview.WRONG));
            shot(mc, "maidbuilder_selftest_clear.png");
        }
        if (ticks == 272) {
            clearScene(mc, false);
            preview.close();
            preview = null;
        }
        // 1.20.1 settings screen: the territory page, its nested bonuses page and the clearing settings
        // at the end of the building page (config4..6).
        if (ticks == 274 || ticks == 294) mc.setScreen(new ConfigOverviewScreen(null));
        if (ticks == 276 || ticks == 296) openSection(mc, "server");
        if (ticks == 278) pressOrWarn(mc, "maidbuilder.configuration.territory");
        if (ticks == 284) shot(mc, "maidbuilder_selftest_config4.png");
        if (ticks == 286) pressOrWarn(mc, "maidbuilder.configuration.bonuses");
        if (ticks == 292) shot(mc, "maidbuilder_selftest_config5.png");
        if (ticks == 298) pressOrWarn(mc, "maidbuilder.configuration.building");
        if (ticks == 300 && mc.screen != null) {
            for (GuiEventListener child : mc.screen.children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractSelectionList<?> list) list.setScrollAmount(list.getMaxScroll());
            }
        }
        if (ticks == 304) shot(mc, "maidbuilder_selftest_config6.png");
        // 1.2.0: the territory page's nested services page, top (config7) and scrolled to the end (config8)
        if (ticks == 306) mc.setScreen(new ConfigOverviewScreen(null));
        if (ticks == 308) openSection(mc, "server");
        if (ticks == 310) pressOrWarn(mc, "maidbuilder.configuration.territory");
        if (ticks == 314) pressOrWarn(mc, "maidbuilder.configuration.services");
        if (ticks == 320) shot(mc, "maidbuilder_selftest_config7.png");
        if (ticks == 322 && mc.screen != null) {
            for (GuiEventListener child : mc.screen.children()) {
                if (child instanceof net.minecraft.client.gui.components.AbstractSelectionList<?> list) list.setScrollAmount(list.getMaxScroll());
            }
        }
        if (ticks == 326) shot(mc, "maidbuilder_selftest_config8.png");
        if (ticks == 328) mc.setScreen(null);
        if (ticks >= 332 && TerritorySelfTest.tick(mc, ticks - 332)) mc.stop();
        return true;
    }

    private static ItemStack savedHand = ItemStack.EMPTY;
    private static BlockPos clearOrigin = BlockPos.ZERO;

    /**
     * Puts (or removes) dirt where the clearing preview should and should not outline it: inside the
     * house and 2 blocks outside its wall (outlined), 4 blocks outside (beyond the radius) and a chest
     * within the radius (never broken).
     */
    private static void clearScene(Minecraft mc, boolean build) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        BlockPos o = clearOrigin;
        server.execute(() -> {
            var level = server.overworld();
            for (BlockPos rel : List.of(new BlockPos(6, 1, 6), new BlockPos(10, 1, 6), new BlockPos(12, 1, 6))) {
                level.setBlockAndUpdate(o.offset(rel), build ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(o.offset(10, 1, 8), build ? Blocks.CHEST.defaultBlockState() : Blocks.AIR.defaultBlockState());
        });
    }

    /** The sample house in the middle of a 13x4x13 box of air. */
    private static Schematic clearSample() {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("box", IntPos.ZERO, new IntPos(13, 4, 13));
        sample().regions().get(0).forEachBlock((pos, state, local) -> rb.set(pos.x() + 4, pos.y(), pos.z() + 4, state));
        return LitematicWriter.schematic("selftest_clear", 3955, List.of(rb.build()));
    }

    /** Holds a wand with the previewed placement (for the HUD) and puts a wrong block into the preview (red frame). */
    private static void wandScene(Minecraft mc, WandPlacement placement) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(id);
            if (player == null) return;
            ItemStack wand = new ItemStack(com.maidbuilder.init.ModItems.BLUEPRINT_WAND.get());
            com.maidbuilder.init.ModItemData.WAND_PLACEMENT.set(wand, placement);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, wand);
            // glass belongs here (west wall, second row)
            player.serverLevel().setBlockAndUpdate(placement.origin().offset(0, 2, 2), Blocks.COBBLESTONE.defaultBlockState());
        });
    }

    /** Builds (or removes) a hut 5 blocks ahead on the server and selects it with a quill. */
    private static void quillScene(Minecraft mc, boolean build) {
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        java.util.UUID id = mc.player.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(id);
            if (player == null) return;
            BlockPos base = player.blockPosition().relative(player.getDirection(), 5);
            var level = player.serverLevel();
            for (int x = -1; x <= 1; x++)
                for (int y = 0; y <= 2; y++)
                    for (int z = -1; z <= 1; z++)
                        level.setBlockAndUpdate(base.offset(x, y, z), build && (x != 0 || z != 0)
                                ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
            BlockPos overhang = base.offset(2, 3, 0);
            level.setBlockAndUpdate(overhang, build ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
            if (build) {
                savedHand = player.getMainHandItem().copy();
                ItemStack quill = new ItemStack(com.maidbuilder.init.ModItems.BLUEPRINT_QUILL.get());
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, quill);
                com.maidbuilder.common.capture.QuillActions.clickBlock(player, quill, base.offset(-1, 0, -1));
            } else {
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, savedHand);
            }
        });
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> MaidBuilder.LOGGER.info("[selftest] {}", msg.getString()));
    }

    /** Presses the settings screen's button for the client or server file. */
    private static void openSection(Minecraft mc, String type) {
        String label = Component.translatable("maidbuilder.configuration.section.maidbuilder." + type + ".toml").getString();
        if (mc.screen == null || !press(mc.screen, label)) MaidBuilder.LOGGER.warn("[selftest] no settings button for {}", type);
    }

    private static void pressOrWarn(Minecraft mc, String key) {
        if (mc.screen == null || !press(mc.screen, Component.translatable(key).getString())) {
            MaidBuilder.LOGGER.warn("[selftest] no button for {}", key);
        }
    }

    private static boolean press(ContainerEventHandler parent, String label) {
        for (GuiEventListener child : parent.children()) {
            if (child instanceof Button button && button.getMessage().getString().contains(label)) {
                button.onPress();
                return true;
            }
            if (child instanceof ContainerEventHandler nested && press(nested, label)) return true;
        }
        return false;
    }

    private static Schematic sample() {
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("house", IntPos.ZERO, new IntPos(5, 4, 5));
        for (int x = 0; x < 5; x++)
            for (int z = 0; z < 5; z++) {
                rb.set(x, 0, z, "minecraft:oak_planks");
                if (x == 0 || x == 4 || z == 0 || z == 4) {
                    rb.set(x, 1, z, "minecraft:stone_bricks");
                    rb.set(x, 2, z, z == 0 && x == 2 ? "minecraft:air" : "minecraft:glass");
                }
                rb.set(x, 3, z, "minecraft:oak_slab[type=bottom,waterlogged=false]");
            }
        rb.set(2, 1, 0, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
        rb.set(2, 2, 0, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        rb.set(1, 1, 1, "minecraft:red_bed[facing=south,occupied=false,part=foot]");
        rb.set(1, 1, 2, "minecraft:red_bed[facing=south,occupied=false,part=head]");
        rb.set(3, 1, 3, "minecraft:chest[facing=west,type=single,waterlogged=false]");
        rb.set(2, 2, 3, "minecraft:wall_torch[facing=north]");
        rb.set(3, 1, 1, "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]");
        rb.set(1, 1, 3, "minecraft:grass_block[snowy=false]");
        return LitematicWriter.schematic("selftest", 3955, List.of(rb.build()));
    }
}
