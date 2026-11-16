package com.maidbuilder.client;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import com.maidbuilder.network.TerritoryPayloads;
import com.maidbuilder.client.territory.TerritoryScreen;
import com.maidbuilder.client.territory.ClientTerritories;
import com.maidbuilder.client.territory.BuildMode;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.LitematicWriter;
import com.maidbuilder.core.schematic.Schematic;
import com.maidbuilder.init.ModItemData;
import com.maidbuilder.init.ModItems;
import com.maidbuilder.network.ModNetwork;
import com.maidbuilder.network.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Development-only network smoke test against a dedicated server, enabled with
 * {@code -Dmaidbuilder.nettest=true} (run config {@code clientNetTest}; the player must be an
 * operator). Uses the real payloads end to end: picks a schematic the server does not have (big
 * enough to be uploaded in several chunks), confirms it with sneak + use so the server asks for
 * the upload, checks that the wand gets linked and that job status and the material report arrive,
 * then has the server capture an area with {@code /maidbuilder save} and checks the downloaded
 * file; then places a territory flag, opens its screen, downloads a template structure for build
 * mode and removes the territory again. Every result is logged as {@code [nettest] PASS/FAIL ...}; the game closes at the end.
 */
final class ClientNetSelfTest {
    static final boolean ENABLED = Boolean.getBoolean("maidbuilder.nettest");
    /** Second pass after the server was restarted: the job created by the first pass must still be listed. */
    private static final boolean RELOAD = Boolean.getBoolean("maidbuilder.nettest.reload");
    private static final String FILE = "nettest_upload.litematic";
    private static final String CAPTURE = "nettest_capture";
    private static int ticks = -1;
    private static boolean failed;
    private static BlockPos origin = BlockPos.ZERO;
    private static BlockPos flag = BlockPos.ZERO;
    @javax.annotation.Nullable
    private static UUID territory;

    private ClientNetSelfTest() {
    }

    static void tick(Minecraft mc) {
        if (!ENABLED || mc.player == null || mc.level == null || mc.getSingleplayerServer() != null) return;
        ticks++;
        try {
            step(mc);
        } catch (RuntimeException | IOException e) {
            fail("exception " + e);
            MaidBuilder.LOGGER.error("[nettest]", e);
            ticks = 699; // the next tick closes the game
        }
    }

    private static void step(Minecraft mc) throws IOException {
        if (RELOAD) {
            if (ticks == 40) mc.player.connection.sendCommand("maidbuilder job list");
            if (ticks == 80) mc.stop(); // the chat answer is in the client log
            return;
        }
        switch (ticks) {
            case 20 -> {
                Path folder = ClientSchematics.folder();
                Files.createDirectories(folder);
                Files.deleteIfExists(folder.resolve(CAPTURE + ".litematic"));
                try (OutputStream out = Files.newOutputStream(folder.resolve(FILE))) {
                    LitematicWriter.write(bigSchematic(), out);
                }
                long size = Files.size(folder.resolve(FILE));
                check(size > 2L * Payloads.UPLOAD_CHUNK_BYTES, "schematic of " + size + " bytes needs several upload chunks");
                mc.player.getInventory().selected = 0;
                mc.player.connection.sendCommand("clear @s");
                mc.player.connection.sendCommand("give @s maidbuilder:blueprint_wand");
            }
            case 50 -> {
                check(wand(mc) != null, "the server gave a Blueprint Wand");
                origin = mc.player.blockPosition().relative(mc.player.getDirection(), 6);
                ClientSchematics.clear();
                ClientSchematics.load(FILE).whenComplete((loaded, error) -> mc.execute(() -> {
                    if (loaded == null) {
                        fail("could not load " + FILE + ": " + error);
                        return;
                    }
                    ModNetwork.sendToServer(new Payloads.SelectSchematic(FILE, loaded.sha1(), origin));
                }));
            }
            case 80 -> {
                ItemStack wand = wand(mc);
                check(wand != null && ModItemData.WAND_PLACEMENT.has(wand) && origin.equals(ModItemData.WAND_PLACEMENT.get(wand).origin()),
                        "SelectSchematic reached the server and the placement synced back");
                ModNetwork.sendToServer(new Payloads.AdjustPlacement(Payloads.Adjustment.ROTATE));
            }
            case 100 -> {
                ItemStack wand = wand(mc);
                check(wand != null && ModItemData.WAND_PLACEMENT.get(wand).rotation() == net.minecraft.world.level.block.Rotation.CLOCKWISE_90,
                        "AdjustPlacement rotated the placement");
                mc.options.keyShift.setDown(true);
            }
            case 105 -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); // confirm: the server lacks the file
            case 110 -> mc.options.keyShift.setDown(false);
            case 200 -> {
                ItemStack wand = wand(mc);
                UUID job = wand == null ? null : ModItemData.BUILD_JOB.get(wand);
                check(job != null, "upload finished and the wand is linked to a new job");
                if (job != null) {
                    Payloads.JobStatus status = ClientPayloadHandler.status(job);
                    check(status != null && status.total() > 0, "JobStatus received: " + (status == null ? null : status.done() + "/" + status.total()));
                }
                ModNetwork.sendToServer(new Payloads.RequestMaterialReport(java.util.Optional.empty(), true));
            }
            case 230 -> {
                check(mc.screen instanceof MaterialListScreen, "MaterialReport opened the material list (" + mc.screen + ")");
                mc.setScreen(null);
                BlockPos a = origin.offset(-2, 0, -2), b = origin.offset(2, 3, 2);
                mc.player.connection.sendCommand("maidbuilder save " + CAPTURE + " " + a.getX() + " " + a.getY() + " " + a.getZ()
                        + " " + b.getX() + " " + b.getY() + " " + b.getZ());
            }
            case 300 -> {
                Path file = ClientSchematics.folder().resolve(CAPTURE + ".litematic");
                check(Files.isRegularFile(file) && Files.size(file) > 0, "captured blueprint downloaded to " + file);
                mc.player.connection.sendCommand("clear @s");
                mc.player.connection.sendCommand("give @s maidbuilder:territory_flag");
            }
            // territory: place a flag, open its screen, download a template's structure, remove it again
            case 330 -> {
                BlockPos ground = mc.player.blockPosition().relative(mc.player.getDirection(), 3).below();
                flag = ground.above();
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false));
            }
            case 370 -> {
                TerritoryPayloads.TerritoryInfo info = ClientTerritories.at(flag);
                check(info != null && info.flag().equals(flag), "TerritoryList synced the new territory at " + flag);
                territory = info == null ? null : info.id();
                check(!ClientTerritories.templates().isEmpty(), "TemplateList received: " + ClientTerritories.templates().size() + " templates");
                mc.player.connection.sendCommand("clear @s");
            }
            case 380 -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(flag), mc.player.getDirection().getOpposite(), flag, false));
            case 410 -> {
                check(mc.screen instanceof TerritoryScreen, "TerritoryReport opened the territory screen (" + mc.screen + ")");
                mc.setScreen(null);
                TerritoryPayloads.TemplateInfo dormitory = ClientTerritories.template(MaidBuilder.id("dormitory"));
                check(dormitory != null, "the dormitory template is listed");
                if (territory != null && dormitory != null) BuildMode.start(territory, dormitory);
            }
            case 450 -> {
                check(BuildMode.loaded() != null, "TemplateStructure downloaded for build mode");
                BuildMode.stop();
                if (territory != null) mc.player.connection.sendCommand("maidbuilder territory remove " + territory);
                mc.player.connection.sendCommand("setblock " + flag.getX() + " " + flag.getY() + " " + flag.getZ() + " air");
                mc.player.connection.sendCommand("give @s maidbuilder:blueprint_wand");
            }
            // 1.3.0: uploads are checked off the server thread. A broken one (a few bytes claiming a
            // huge array) must be turned down without a job and without holding the server up.
            case 480 -> {
                check(territory != null && ClientTerritories.get(territory) == null, "territory removed and the list synced");
                check(wand(mc) != null && !ModItemData.BUILD_JOB.has(wand(mc)), "a fresh, unlinked Blueprint Wand");
                badUpload = brokenUpload();
                ModNetwork.sendToServer(new Payloads.SelectSchematic(BAD_FILE, ClientSchematics.sha1(badUpload), origin.offset(64, 0, 0)));
            }
            case 500 -> mc.options.keyShift.setDown(true);
            case 505 -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); // confirm: the server asks for the file
            case 510 -> mc.options.keyShift.setDown(false);
            case 520 -> ModNetwork.sendToServer(new Payloads.UploadChunk(ClientSchematics.sha1(badUpload), 0, 1, badUpload));
            case 560 -> {
                ItemStack wand = wand(mc);
                check(wand != null && ModItemData.WAND_PLACEMENT.has(wand) && BAD_FILE.equals(ModItemData.WAND_PLACEMENT.get(wand).file()),
                        "the broken schematic was selected on the server");
                check(wand != null && !ModItemData.BUILD_JOB.has(wand), "the broken upload made no job");
                mc.player.connection.sendCommand("maidbuilder job list"); // the server still answers (see the chat lines in the log)
            }
            case 580 -> {
                MaidBuilder.LOGGER.info("[nettest] {}", failed ? "SOME CHECKS FAILED" : "ALL CHECKS PASSED");
                mc.player.connection.sendCommand("stop"); // saves the world: the job must survive the restart
            }
            case 600 -> {
                mc.stop();
            }
            case 700 -> mc.stop();
            default -> {
            }
        }
    }

    private static final String BAD_FILE = "nettest_broken.litematic";
    private static byte[] badUpload = new byte[0];

    /** A gzipped NBT compound whose only entry is a long array said to hold two billion longs, and then nothing. */
    private static byte[] brokenUpload() throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (OutputStream out = new java.util.zip.GZIPOutputStream(bytes)) {
            out.write(new byte[] {10, 0, 0, 12, 0, 1, 'x', 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        }
        return bytes.toByteArray();
    }

    private static ItemStack wand(Minecraft mc) {
        ItemStack stack = mc.player.getMainHandItem();
        return stack.is(ModItems.BLUEPRINT_WAND.get()) ? stack : null;
    }

    private static void check(boolean ok, String what) {
        if (ok) MaidBuilder.LOGGER.info("[nettest] PASS {}", what);
        else fail(what);
    }

    private static void fail(String what) {
        failed = true;
        MaidBuilder.LOGGER.error("[nettest] FAIL {}", what);
    }

    /** 48^3 random blocks out of 64 states: compresses badly, so the file takes several upload chunks. */
    private static Schematic bigSchematic() {
        String[] colors = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
                "purple", "blue", "brown", "green", "red", "black"};
        String[] kinds = {"_wool", "_concrete", "_terracotta", "_stained_glass"};
        String[] blocks = new String[colors.length * kinds.length];
        for (int i = 0; i < blocks.length; i++) blocks[i] = "minecraft:" + colors[i % colors.length] + kinds[i / colors.length];
        Random random = new Random(42);
        int n = 48;
        LitematicWriter.RegionBuilder rb = new LitematicWriter.RegionBuilder("nettest", IntPos.ZERO, new IntPos(n, n, n));
        for (int x = 0; x < n; x++)
            for (int y = 0; y < n; y++)
                for (int z = 0; z < n; z++)
                    rb.set(x, y, z, blocks[random.nextInt(blocks.length)]);
        return LitematicWriter.schematic("nettest", 3465, List.of(rb.build()));
    }
}
