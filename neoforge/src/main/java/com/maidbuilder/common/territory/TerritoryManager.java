package com.maidbuilder.common.territory;

import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.core.territory.TerritoryRules;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** Server-side territory operations: placing and removing flags, lookups. */
public final class TerritoryManager {
    private TerritoryManager() {
    }

    public static TerritoryData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TerritoryData.FACTORY, TerritoryData.NAME);
    }

    /** The territory (active or not) containing {@code pos}. */
    @Nullable
    public static Territory at(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
        return data(server).at(dimension, pos);
    }

    /** The active territory containing {@code pos}. */
    @Nullable
    public static Territory activeAt(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
        Territory t = at(server, dimension, pos);
        return t != null && t.isActive() ? t : null;
    }

    /** Material containers of an active territory; empty otherwise. */
    public static List<BlockPos> materialSourcesOf(MinecraftServer server, UUID territory) {
        Territory t = data(server).get(territory);
        return t == null || !t.isActive() ? List.of() : t.materialSources();
    }

    public static int maxFlags() {
        return MaidBuilderConfig.MAX_FLAGS_PER_PLAYER.get();
    }

    /**
     * Whether a flag may go at {@code pos}.
     *
     * @param error  why not; null if it may
     * @param retake the player's own inactive territory this flag takes back over, if any
     */
    public record PlacementCheck(@Nullable Component error, @Nullable Territory retake) {
        public boolean ok() {
            return error == null;
        }
    }

    public static PlacementCheck check(MinecraftServer server, UUID player, ResourceKey<Level> dimension, BlockPos pos) {
        TerritoryData data = data(server);
        Territory retake = null;
        Territory here = data.at(dimension, pos);
        if (here != null) {
            if (here.isActive() || !here.isOwnedBy(player)) {
                return new PlacementCheck(Component.translatable("message.maidbuilder.territory.inside", here.ownerName()), null);
            }
            retake = here;
        }
        if (retake == null && data.ownedBy(player).size() >= maxFlags()) {
            return new PlacementCheck(Component.translatable("message.maidbuilder.territory.limit", maxFlags()), null);
        }
        int min = TerritoryLevels.minFlagDistance();
        for (Territory t : data.all()) {
            if (t == retake || !t.dimension().equals(dimension)) continue;
            int d = TerritoryRules.flagDistance(t.flagPos().getX(), t.flagPos().getZ(), pos.getX(), pos.getZ());
            if (d < min) {
                return new PlacementCheck(Component.translatable("message.maidbuilder.territory.too_close", t.ownerName(),
                        t.flagPos().getX(), t.flagPos().getZ(), d, min), null);
            }
        }
        return new PlacementCheck(null, retake);
    }

    /** A flag was placed at {@code pos} after {@link #check} allowed it: create the territory or take one back over. */
    public static Territory place(MinecraftServer server, ServerPlayer player, ResourceKey<Level> dimension, BlockPos pos,
                                  PlacementCheck check) {
        TerritoryData data = data(server);
        Territory territory = check.retake();
        if (territory != null) {
            territory.setFlagPos(pos);
            territory.setActive(true);
            territory.setOwnerName(player.getGameProfile().getName());
            data.reindex();
            setJobsSuspended(server, territory, false);
            player.sendSystemMessage(Component.translatable("message.maidbuilder.territory.retaken", territory.level()));
        } else {
            territory = new Territory(UUID.randomUUID(), player.getUUID(), player.getGameProfile().getName(), dimension, pos);
            data.add(territory);
            player.sendSystemMessage(Component.translatable("message.maidbuilder.territory.created", territory.radius(),
                    data.ownedBy(player.getUUID()).size(), maxFlags()));
        }
        return territory;
    }

    /** The flag block at {@code pos} is gone: its territory stops working until a flag is back. */
    public static void onFlagRemoved(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
        Territory territory = data(server).atFlag(dimension, pos);
        if (territory == null || !territory.isActive()) return;
        territory.setActive(false);
        setJobsSuspended(server, territory, true);
        ServerPlayer owner = server.getPlayerList().getPlayer(territory.owner());
        if (owner != null) owner.sendSystemMessage(Component.translatable("message.maidbuilder.territory.deactivated"));
    }

    private static void setJobsSuspended(MinecraftServer server, Territory territory, boolean suspended) {
        for (UUID id : territory.jobQueue()) {
            BuildJob job = BuildJobManager.data(server).get(id);
            if (job != null) job.setSuspended(suspended);
        }
    }

    /** Deletes a territory for good; its queued jobs are cancelled. */
    public static void remove(MinecraftServer server, Territory territory) {
        for (UUID id : territory.jobQueue()) {
            BuildJob job = BuildJobManager.data(server).get(id);
            if (job != null) BuildJobManager.cancel(server, job);
        }
        data(server).remove(territory.id());
    }

    /**
     * A maid of {@code owner} reports where she lives: she is a resident of the active territory of
     * her owner that contains {@code home}, and of no other one; null home = not living anywhere.
     */
    public static void reportResident(MinecraftServer server, UUID maid, @Nullable UUID owner, ResourceKey<Level> dimension,
                                      @Nullable BlockPos home) {
        if (owner == null) return;
        long now = server.overworld().getGameTime();
        Territory living = home == null ? null : activeAt(server, dimension, home);
        if (living != null && !living.isOwnedBy(owner)) living = null;
        for (Territory t : data(server).ownedBy(owner)) {
            if (t == living) t.reportResident(maid, now);
            else t.removeResident(maid);
        }
    }

    /** Sets the level (upgrades, commands); the radius follows the level table. */
    public static void setLevel(MinecraftServer server, Territory territory, int level) {
        territory.setLevel(Math.max(1, level));
    }

    /** Whether the player may manage the territory (its owner, or an operator). */
    public static boolean mayManage(ServerPlayer player, Territory territory) {
        return territory.isOwnedBy(player.getUUID()) || player.hasPermissions(2);
    }
}
