package com.maidbuilder.client;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.StateResolver;
import com.maidbuilder.common.territory.TemplateRegistry;
import com.maidbuilder.core.math.IntPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Development aid for template art ({@code ./gradlew :neoforge:runTemplateShowcase}, world
 * "selftest"): pastes every built-in template on a grass platform high above the player, saves
 * two views of each as {@code screenshots/maidbuilder_showcase_<id>_<a|b>.png}, removes everything
 * again, puts the player back and quits.
 */
final class TemplateShowcase {
    static final boolean ENABLED = Boolean.getBoolean("maidbuilder.showcase");
    private static final int START = 60, PER_TEMPLATE = 80, MARGIN = 3, SPACING = 40;

    private record Shown(String id, BlockPos origin, IntPos size) {
        Vec3 center() {
            return new Vec3(origin.getX() + size.x() / 2.0, origin.getY() + size.y() / 3.0, origin.getZ() + size.z() / 2.0);
        }
    }

    private static volatile List<Shown> shown = List.of();
    private static int ticks = -1;
    private static Vec3 startPos = Vec3.ZERO;
    private static float startYaw, startPitch;
    private static GameType startMode = GameType.CREATIVE;

    private TemplateShowcase() {
    }

    static void tick(Minecraft mc) {
        if (!ENABLED || mc.player == null || mc.level == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) return;
        ticks++;
        UUID playerId = mc.player.getUUID();
        if (ticks == 0) {
            mc.options.hideGui = true;
            server.execute(() -> setUp(server, playerId));
            return;
        }
        List<Shown> list = shown;
        int end = START + list.size() * PER_TEMPLATE;
        if (ticks >= START && ticks < end) {
            int i = (ticks - START) / PER_TEMPLATE, step = (ticks - START) % PER_TEMPLATE;
            Shown s = list.get(i);
            if (step == 0) server.execute(() -> look(server, playerId, s, false));
            if (step == 40) shot(mc, s.id() + "_a");
            if (step == 42) server.execute(() -> look(server, playerId, s, true));
            if (step == 75) shot(mc, s.id() + "_b");
        }
        if (ticks == end && !list.isEmpty()) {
            mc.options.hideGui = false;
            server.execute(() -> tearDown(server, playerId, list));
        }
        if (ticks == end + 20) mc.stop();
    }

    private static void setUp(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        startPos = player.position();
        startYaw = player.getYRot();
        startPitch = player.getXRot();
        startMode = player.gameMode.getGameModeForPlayer();
        player.setGameMode(GameType.SPECTATOR);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "weather clear");

        ServerLevel level = player.serverLevel();
        BlockPos base = BlockPos.containing(startPos).atY(Math.min(level.getMaxBuildHeight() - 40, 200));
        List<TemplateRegistry.Entry> entries = new ArrayList<>(TemplateRegistry.all());
        entries.removeIf(e -> !e.def().id().getNamespace().equals(MaidBuilder.MOD_ID) || e.def().id().getPath().startsWith("test/"));
        entries.sort(Comparator.comparingInt((TemplateRegistry.Entry e) -> e.def().requiredLevel()).thenComparingInt(e -> e.def().order()));
        purgeFluids(level, base, entries.size());
        List<Shown> list = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            TemplateRegistry.Entry e = entries.get(i);
            BlockPos origin = base.offset((i % 4) * SPACING, 0, (i / 4) * SPACING);
            IntPos size = e.size();
            boolean dock = e.def().id().getPath().contains("fishing");
            BlockPos lo = origin.offset(-MARGIN, -1, -MARGIN), hi = origin.offset(size.x() - 1 + MARGIN, -1, size.z() - 1 + MARGIN);
            for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
                level.setBlock(p, (dock ? Blocks.SANDSTONE : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
                if (!dock) continue;
                // The dock stands in a pool: a sandstone rim holds the water (sand would fall), the strip in front of it is the shore.
                boolean rim = p.getX() == lo.getX() || p.getX() == hi.getX() || p.getZ() == hi.getZ() || p.getZ() < origin.getZ() + 2;
                level.setBlock(p.above(), (rim ? Blocks.SANDSTONE : Blocks.WATER).defaultBlockState(), 2);
            }
            StateResolver resolver = new StateResolver(e.schematic().minecraftDataVersion());
            IntPos min = e.schematic().minCorner();
            e.schematic().forEachBlock((pos, state, local) -> {
                BlockState target = resolver.resolve(state);
                BlockPlacer.placeExact(level, origin.offset(pos.x() - min.x(), pos.y() - min.y(), pos.z() - min.z()), target);
            });
            list.add(new Shown(e.def().id().getPath(), origin, size));
        }
        shown = List.copyOf(list);
        MaidBuilder.LOGGER.info("[showcase] {} templates at {}", list.size(), base);
    }

    /** Front-left view from above, or the back-right one. */
    private static void look(MinecraftServer server, UUID playerId, Shown s, boolean back) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        Vec3 c = s.center();
        double dist = Math.max(s.size().x(), s.size().z()) * 0.9 + 7;
        Vec3 dir = new Vec3(back ? 0.7 : -0.7, 0.75, back ? 1 : -1).normalize();
        Vec3 eye = c.add(dir.scale(dist));
        Vec3 to = c.subtract(eye);
        float yaw = (float) (Math.toDegrees(Math.atan2(-to.x, to.z)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z))));
        player.teleportTo(player.serverLevel(), eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
    }

    private static void tearDown(MinecraftServer server, UUID playerId, List<Shown> list) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        for (Shown s : list) {
            for (BlockPos p : BlockPos.betweenClosed(s.origin().offset(-MARGIN, -1, -MARGIN),
                    s.origin().offset(s.size().x() - 1 + MARGIN, s.size().y(), s.size().z() - 1 + MARGIN))) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        }
        purgeFluids(level, list.get(0).origin(), list.size());
        player.setGameMode(startMode);
        player.teleportTo(level, startPos.x, startPos.y, startPos.z, startYaw, startPitch);
    }

    /**
     * Removes the water of the grid and anything that spilled from it: every fluid at the grid's
     * height and flowing water below it (natural sources further down are left alone).
     */
    private static void purgeFluids(ServerLevel level, BlockPos base, int count) {
        int rows = (count + 3) / 4;
        BlockPos lo = base.offset(-SPACING / 2, 0, -SPACING / 2).atY(level.getMinBuildHeight());
        BlockPos hi = base.offset(4 * SPACING, 40, rows * SPACING);
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
            var fluid = level.getFluidState(p);
            if (fluid.isEmpty() || (fluid.isSource() && p.getY() < base.getY() - 1)) continue;
            BlockState state = level.getBlockState(p);
            if (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
            }
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "maidbuilder_showcase_" + name + ".png", mc.getMainRenderTarget(),
                msg -> MaidBuilder.LOGGER.info("[showcase] {}", msg.getString()));
    }
}
