package com.maidbuilder.common.territory;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.MaterialContainers;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.common.maid.BuilderMaidData;
import com.maidbuilder.common.maid.TaskBuilder;
import com.maidbuilder.common.maid.TerritoryMaidHandler;
import com.maidbuilder.common.wand.MaterialReports;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Buttons of the territory screen, and the "bind material containers" mode they can start. */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class TerritoryActions {
    /** How long binding mode lasts without a click. */
    private static final int BINDING_TICKS = 20 * 120;

    private record Binding(UUID territory, long until) {
    }

    private static final Map<UUID, Binding> BINDING = new HashMap<>();

    private TerritoryActions() {
    }

    public static void handle(ServerPlayer player, TerritoryPayloads.TerritoryAction msg) {
        Territory territory = TerritoryManager.data(player.server).get(msg.territory());
        if (territory == null || !TerritoryManager.mayManage(player, territory)) return;
        UUID target = msg.target().orElse(null);
        switch (msg.action()) {
            case JOB_UP, JOB_DOWN -> {
                if (target != null) territory.moveJob(target, msg.action() == TerritoryPayloads.Action.JOB_UP ? -1 : 1);
            }
            case JOB_CANCEL -> cancelJob(player, territory, target);
            case JOB_MATERIALS -> {
                if (target != null && territory.jobQueue().contains(target)) MaterialReports.send(player, target, true);
            }
            case BIND_SOURCES -> toggleBinding(player, territory);
            case TOGGLE_BUILDER -> toggleBuilder(player, territory, target);
            case UPGRADE -> TerritoryUpgrades.upgrade(player, territory);
            case REPAIR -> TerritoryUpgrades.repair(player, territory, target);
            case REMOVE_BUILDING -> TerritoryUpgrades.unregister(player, territory, target);
            case ASSIGN, UNASSIGN, DEPOSIT, RESUME -> WorkplaceActions.handle(player, territory, msg.action(), target, msg.other().orElse(null));
        }
        TerritoryReports.send(player, territory, false);
    }

    private static void cancelJob(ServerPlayer player, Territory territory, @Nullable UUID jobId) {
        if (jobId == null || !territory.jobQueue().contains(jobId)) return;
        BuildJob job = BuildJobManager.data(player.server).get(jobId);
        territory.dequeue(jobId);
        if (job != null) {
            BuildJobManager.cancel(player.server, job);
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.job_cancelled", job.shortId()), true);
        }
    }

    @Nullable
    static EntityMaid ownedMaid(ServerPlayer player, Territory territory, @Nullable UUID maidId) {
        if (maidId == null) return null;
        ServerLevel level = player.server.getLevel(territory.dimension());
        Entity entity = level == null ? null : level.getEntity(maidId);
        if (entity instanceof EntityMaid maid && territory.owner().equals(maid.getOwnerUUID())) return maid;
        player.displayClientMessage(Component.translatable("message.maidbuilder.territory.maid_unavailable"), true);
        return null;
    }

    private static void toggleBuilder(ServerPlayer player, Territory territory, @Nullable UUID maidId) {
        EntityMaid maid = ownedMaid(player, territory, maidId);
        if (maid == null) return;
        if (maid.getTask().getUid().equals(TaskBuilder.UID)) {
            maid.setTask(TaskManager.getIdleTask());
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.builder_off", maid.getDisplayName()), true);
        } else {
            WorkplaceActions.release(player.server, maid);
            TaskManager.findTask(TaskBuilder.UID).ifPresent(maid::setTask);
            if (maid.level() instanceof ServerLevel level) TerritoryMaidHandler.updateBuilder(level, maid);
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.builder_on", maid.getDisplayName()), true);
        }
    }

    // ---- binding material containers ----

    private static void toggleBinding(ServerPlayer player, Territory territory) {
        Binding current = BINDING.get(player.getUUID());
        if (current != null && current.territory().equals(territory.id())) {
            BINDING.remove(player.getUUID());
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.binding_off"), true);
        } else {
            BINDING.put(player.getUUID(), new Binding(territory.id(), player.server.getTickCount() + BINDING_TICKS));
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.binding_on"), false);
        }
    }

    public static boolean isBinding(ServerPlayer player, Territory territory) {
        Binding b = BINDING.get(player.getUUID());
        return b != null && b.territory().equals(territory.id()) && b.until() > player.server.getTickCount();
    }

    /** In binding mode, a click on a container adds it to / removes it from the territory's material containers. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Binding b = BINDING.get(player.getUUID());
        if (b == null) return;
        if (b.until() <= player.server.getTickCount()) {
            BINDING.remove(player.getUUID());
            return;
        }
        Territory territory = TerritoryManager.data(player.server).get(b.territory());
        if (territory == null) {
            BINDING.remove(player.getUUID());
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player.isSecondaryUseActive()) {
            BINDING.remove(player.getUUID());
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.binding_off"), true);
            return;
        }
        toggleSource(player, territory, event.getPos());
        BINDING.put(player.getUUID(), new Binding(territory.id(), player.server.getTickCount() + BINDING_TICKS));
    }

    /** Adds or removes a container (a double chest counts once, whichever half). Returns true if it is now a source. */
    public static boolean toggleSource(ServerPlayer player, Territory territory, BlockPos pos) {
        if (!player.level().dimension().equals(territory.dimension()) || !MaterialContainers.isContainer(player.level(), pos)) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.not_container"), true);
            return false;
        }
        if (!territory.contains(pos)) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.territory.source_outside"), true);
            return false;
        }
        List<BlockPos> halves = MaterialContainers.halves(player.level(), pos);
        boolean bound = halves.stream().anyMatch(territory.materialSources()::contains);
        halves.forEach(territory::removeMaterialSource);
        if (!bound) territory.addMaterialSource(MaterialContainers.canonical(player.level(), pos));
        player.displayClientMessage(Component.translatable(bound ? "message.maidbuilder.source.removed" : "message.maidbuilder.source.added",
                pos.toShortString()), true);
        return !bound;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        BINDING.remove(event.getEntity().getUUID());
    }
}
