package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
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
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Development-only rendering smoke test, enabled with {@code -Dmaidbuilder.selftest=true}:
 * once in a world it shows a ghost preview in front of the player, saves a screenshot to
 * {@code screenshots/maidbuilder_selftest.png}, then opens the material list for it, saves
 * {@code screenshots/maidbuilder_selftest_materials.png}, then the settings screens
 * ({@code maidbuilder_selftest_config0..3.png}), a Blueprint Quill selecting a hut
 * ({@code maidbuilder_selftest_quill.png}) and closes the game.
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
            preview = GhostPreview.create(sample(), new WandPlacement("selftest", "", base, Rotation.NONE, Mirror.NONE));
            MaidBuilder.LOGGER.info("[selftest] preview with {} blocks at {}", preview.total(), base);
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
            mc.setScreen(new ConfigurationScreen(ModList.get().getModContainerById(MaidBuilder.MOD_ID).orElseThrow(), null));
        }
        if (ticks == 165 || ticks == 180 || ticks == 195) shot(mc, "maidbuilder_selftest_config" + (ticks - 165) / 15 + ".png");
        if (ticks == 170) openSection(mc, "client");
        if (ticks == 185) {
            mc.setScreen(new ConfigurationScreen(ModList.get().getModContainerById(MaidBuilder.MOD_ID).orElseThrow(), null));
        }
        if (ticks == 187) openSection(mc, "server");
        if (ticks == 197) {
            // first "Edit..." of the server page: the building section
            if (mc.screen == null || !press(mc.screen, Component.translatable("neoforge.configuration.uitext.sectiontext").getString())) {
                MaidBuilder.LOGGER.warn("[selftest] no edit button");
            }
        }
        if (ticks == 205) shot(mc, "maidbuilder_selftest_config3.png");
        // Blueprint Quill: a small hut with an overhang selected by one click (removed again afterwards).
        if (ticks == 210) {
            mc.setScreen(null);
            MaidBuilder.LOGGER.info("[selftest] done: {} missing, {} wrong", preview.count(GhostPreview.MISSING), preview.count(GhostPreview.WRONG));
            preview.close();
            preview = null;
            quillScene(mc, true);
        }
        if (ticks == 230) shot(mc, "maidbuilder_selftest_quill.png");
        if (ticks == 232) quillScene(mc, false);
        if (ticks == 240) mc.stop();
        return true;
    }

    private static ItemStack savedHand = ItemStack.EMPTY;

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
