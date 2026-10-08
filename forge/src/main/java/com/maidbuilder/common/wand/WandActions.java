package com.maidbuilder.common.wand;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.MaterialContainers;
import com.maidbuilder.common.SchematicStore;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobFactory;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.init.ModItemData;
import com.maidbuilder.init.ModItems;
import com.maidbuilder.item.WandPlacement;
import com.maidbuilder.network.Payloads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import com.maidbuilder.network.ModNetwork;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Server-side Blueprint Wand operations (item use, key presses, selection screen). */
public final class WandActions {
    private WandActions() {
    }

    @Nullable
    public static ItemStack heldWand(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(ModItems.BLUEPRINT_WAND.get())) return stack;
        }
        return null;
    }

    public static boolean isLinked(ItemStack wand) {
        return ModItemData.BUILD_JOB.has(wand);
    }

    /** The player picked a file in the selection screen. */
    public static void select(ServerPlayer player, Payloads.SelectSchematic msg) {
        ItemStack wand = heldWand(player);
        if (wand == null) return;
        if (isLinked(wand)) {
            player.sendSystemMessage(Component.translatable("message.maidbuilder.wand.linked_locked"));
            return;
        }
        ModItemData.WAND_PLACEMENT.set(wand, new WandPlacement(msg.file(), msg.sha1(), msg.origin().immutable(),
                net.minecraft.world.level.block.Rotation.NONE, net.minecraft.world.level.block.Mirror.NONE));
        player.displayClientMessage(Component.translatable("message.maidbuilder.wand.selected", msg.file()), true);
    }

    public static void adjust(ServerPlayer player, Payloads.Adjustment adjustment) {
        ItemStack wand = heldWand(player);
        if (wand == null) return;
        WandPlacement placement = ModItemData.WAND_PLACEMENT.get(wand);
        if (placement == null) return;
        if (isLinked(wand)) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.wand.linked_locked"), true);
            return;
        }
        Direction facing = player.getDirection();
        WandPlacement updated = switch (adjustment) {
            case ROTATE -> placement.rotated();
            case MIRROR -> placement.mirrored();
            case UP -> placement.withOrigin(placement.origin().above());
            case DOWN -> placement.withOrigin(placement.origin().below());
            case FORWARD -> placement.withOrigin(placement.origin().relative(facing));
            case BACK -> placement.withOrigin(placement.origin().relative(facing.getOpposite()));
            case LEFT -> placement.withOrigin(placement.origin().relative(facing.getCounterClockWise()));
            case RIGHT -> placement.withOrigin(placement.origin().relative(facing.getClockWise()));
        };
        ModItemData.WAND_PLACEMENT.set(wand, updated);
    }

    public static void setOrigin(ServerPlayer player, ItemStack wand, BlockPos pos) {
        WandPlacement placement = ModItemData.WAND_PLACEMENT.get(wand);
        if (placement == null) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.wand.select_first"), true);
            return;
        }
        ModItemData.WAND_PLACEMENT.set(wand, placement.withOrigin(pos));
        player.displayClientMessage(Component.translatable("message.maidbuilder.wand.origin", pos.toShortString()), true);
    }

    /** Sneak + use: turn the positioned schematic into a build job, uploading it first if needed. */
    public static void confirm(ServerPlayer player, ItemStack wand) {
        WandPlacement placement = ModItemData.WAND_PLACEMENT.get(wand);
        if (placement == null) {
            player.displayClientMessage(Component.translatable("message.maidbuilder.wand.select_first"), true);
            return;
        }
        String hash = placement.sha1();
        if (!SchematicStore.hasStored(player.server, hash)) {
            // Single player / file present on the server: copy it if it is the very same file.
            try {
                Path file = SchematicStore.resolveUserFile(placement.file());
                if (SchematicStore.sha1(file).equals(hash)) SchematicStore.storeCopy(player.server, file);
            } catch (IOException ignored) {
                // not available locally; ask the client
            }
        }
        if (!SchematicStore.hasStored(player.server, hash)) {
            UploadManager.expect(player, hash, placement.file());
            ModNetwork.sendToPlayer(player, new Payloads.RequestUpload(placement.file(), hash));
            player.displayClientMessage(Component.translatable("message.maidbuilder.upload.started", placement.file()), true);
            return;
        }
        try {
            BuildJob job = BuildJobFactory.create(player, placement.file(), hash, placement.origin(), placement.rotation(), placement.mirror());
            ModItemData.BUILD_JOB.set(wand, job.id());
            player.sendSystemMessage(Component.translatable("message.maidbuilder.wand.job_created", job.shortId(), placement.file(), job.size()));
        } catch (IOException e) {
            MaidBuilder.LOGGER.warn("Cannot create build job from {}", placement.file(), e);
            player.sendSystemMessage(Component.translatable("command.maidbuilder.file_error", placement.file(), e.getMessage()));
        }
    }

    /** Called when an upload finished: confirm again if the player still holds the matching wand. */
    public static void onUploadComplete(ServerPlayer player, String hash) {
        ItemStack wand = heldWand(player);
        if (wand == null || isLinked(wand)) return;
        WandPlacement placement = ModItemData.WAND_PLACEMENT.get(wand);
        if (placement != null && placement.sha1().equals(hash)) confirm(player, wand);
    }

    /** Ticks within which a second sneak + use confirms cancelling the job. */
    private static final int CONFIRM_TICKS = 60;
    private static final java.util.Map<UUID, Long> PENDING_CANCEL = new java.util.HashMap<>();

    /**
     * Sneak + use on a linked wand. An unfinished job the player may cancel needs a second click
     * within a few seconds; the job is then cancelled (its maids stop and take their scaffolding
     * down) and the wand is free for a new placement.
     */
    public static void unlink(ServerPlayer player, ItemStack wand) {
        UUID jobId = ModItemData.BUILD_JOB.get(wand);
        BuildJob job = jobId == null ? null : BuildJobManager.data(player.server).get(jobId);
        boolean mayCancel = job != null && !job.isFinished()
                && (job.owner().equals(player.getUUID()) || player.hasPermissions(2));
        if (mayCancel) {
            long now = player.serverLevel().getGameTime();
            Long first = PENDING_CANCEL.get(player.getUUID());
            if (first == null || now - first > CONFIRM_TICKS || now < first) {
                PENDING_CANCEL.put(player.getUUID(), now);
                player.displayClientMessage(Component.translatable("message.maidbuilder.wand.confirm_cancel", job.schematicName()), true);
                return;
            }
            PENDING_CANCEL.remove(player.getUUID());
            BuildJobManager.cancel(player.server, job);
            player.sendSystemMessage(Component.translatable("message.maidbuilder.wand.cancelled", job.schematicName(), job.shortId()));
        }
        ModItemData.BUILD_JOB.remove(wand);
        player.displayClientMessage(Component.translatable("message.maidbuilder.wand.unlinked_done"), true);
    }

    public static void forget(UUID player) {
        PENDING_CANCEL.remove(player);
    }

    /** Opens the material screen of the linked job (the chat summary is {@code /maidbuilder job info}). */
    public static void showMaterials(ServerPlayer player, ItemStack wand) {
        UUID jobId = ModItemData.BUILD_JOB.get(wand);
        if (jobId != null) MaterialReports.send(player, jobId, true);
    }

    /** Linked wand on a container: add it to / remove it from the job's material containers. */
    public static boolean toggleMaterialSource(ServerPlayer player, ItemStack wand, BlockPos pos) {
        UUID jobId = ModItemData.BUILD_JOB.get(wand);
        if (jobId == null || !MaterialContainers.isContainer(player.level(), pos)) return false;
        Optional<BuildJob> job = BuildJobManager.loaded(player.server, jobId);
        if (job.isEmpty()) return false;
        if (!job.get().owner().equals(player.getUUID()) && !player.hasPermissions(2)) {
            player.displayClientMessage(Component.translatable("command.maidbuilder.job.not_owner"), true);
            return true;
        }
        if (!job.get().dimension().equals(player.level().dimension())) return false;
        // A double chest is one container, whichever half is clicked.
        List<BlockPos> halves = MaterialContainers.halves(player.level(), pos);
        boolean bound = halves.stream().anyMatch(job.get().materialSources()::contains);
        halves.forEach(job.get()::removeMaterialSource);
        if (!bound) job.get().addMaterialSource(MaterialContainers.canonical(player.level(), pos));
        boolean added = !bound;
        player.displayClientMessage(Component.translatable(added
                ? "message.maidbuilder.source.added" : "message.maidbuilder.source.removed", pos.toShortString()), true);
        return true;
    }
}
