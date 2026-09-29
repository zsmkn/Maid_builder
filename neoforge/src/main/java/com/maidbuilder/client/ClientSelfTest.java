package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.client.preview.GhostPreview;
import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.item.WandPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Development-only rendering smoke test, enabled with {@code -Dmaidbuilder.selftest=true}:
 * once in a world it shows a ghost preview in front of the player, saves a screenshot to
 * {@code screenshots/maidbuilder_selftest.png} and closes the game.
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
        if (ticks == 140) {
            MaidBuilder.LOGGER.info("[selftest] done: {} missing, {} wrong", preview.count(GhostPreview.MISSING), preview.count(GhostPreview.WRONG));
            mc.stop();
        }
        return true;
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
