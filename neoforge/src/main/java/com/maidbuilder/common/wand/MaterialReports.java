package com.maidbuilder.common.wand;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidbuilder.common.MaterialContainers;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.common.maid.BuilderMaidData;
import com.maidbuilder.init.ModDataComponents;
import com.maidbuilder.network.Payloads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Material list of a build job for the material screen: what is still needed, and how much of it
 * the player, the material containers and the job's maids have.
 */
public final class MaterialReports {
    /** A player gets at most one report per this many ticks (the screen asks once a second). */
    private static final int MIN_INTERVAL_TICKS = 10;
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();

    private MaterialReports() {
    }

    /** The player asked for the list of the job linked to the wand they hold. */
    public static void onRequest(ServerPlayer player, boolean open) {
        ItemStack wand = WandActions.heldWand(player);
        if (wand == null) return;
        UUID jobId = wand.get(ModDataComponents.BUILD_JOB.get());
        if (jobId != null) send(player, jobId, open);
    }

    /** Sends the report unless the player got one very recently; opening the screen is never throttled. */
    public static void send(ServerPlayer player, UUID jobId, boolean open) {
        long now = player.server.getTickCount();
        Long last = LAST_SENT.get(player.getUUID());
        if (!open && last != null && now - last < MIN_INTERVAL_TICKS && now >= last) return;
        BuildJob job = BuildJobManager.loaded(player.server, jobId).orElse(null);
        if (job == null) {
            if (open) player.sendSystemMessage(Component.translatable("message.maidbuilder.job.not_found", jobId.toString()));
            return;
        }
        LAST_SENT.put(player.getUUID(), now);
        PacketDistributor.sendToPlayer(player, build(player.server.getLevel(job.dimension()), player.getInventory(), job, open));
    }

    public static void forget(UUID player) {
        LAST_SENT.remove(player);
    }

    /**
     * Builds the report.
     *
     * @param level  the job's level, where its containers and maids are looked for; null to count neither
     * @param player whose inventory counts; null for none
     */
    public static Payloads.MaterialReport build(@Nullable ServerLevel level, @Nullable Inventory player, BuildJob job, boolean open) {
        Map<Item, Integer> inPlayer = new HashMap<>();
        if (player != null) count(player, inPlayer);
        Map<Item, Integer> inContainers = new HashMap<>();
        Map<Item, Integer> inMaids = new HashMap<>();
        int unloaded = 0, maids = 0;
        if (level != null) {
            for (BlockPos pos : job.materialSources()) if (!level.isLoaded(pos)) unloaded++;
            for (IItemHandler handler : MaterialContainers.distinct(level, job.materialSources()).values()) {
                MaterialContainers.count(handler, inContainers);
            }
            for (EntityMaid maid : boundMaids(level, job)) {
                MaterialContainers.count(maid.getAvailableInv(false), inMaids);
                maids++;
            }
        }

        Map<Item, Integer> remaining = job.remainingMaterials();
        List<Payloads.MaterialRow> rows = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : job.totalMaterials().entrySet()) {
            if (rows.size() == Payloads.MAX_MATERIAL_ROWS) break;
            Item item = e.getKey();
            rows.add(new Payloads.MaterialRow(item, remaining.getOrDefault(item, 0), e.getValue(),
                    inPlayer.getOrDefault(item, 0), inContainers.getOrDefault(item, 0), inMaids.getOrDefault(item, 0)));
        }
        return new Payloads.MaterialReport(open, job.id(), job.schematicName(), job.count(BuildJob.DONE), job.size(),
                job.count(BuildJob.NEEDS_PLAYER), job.materialSources().size(), unloaded, maids, rows);
    }

    /** Maids in loaded chunks of the job's level that are bound to it. */
    private static List<? extends EntityMaid> boundMaids(ServerLevel level, BuildJob job) {
        return level.getEntities(EntityTypeTest.forClass(EntityMaid.class), m -> job.id().equals(BuilderMaidData.jobOf(m)));
    }

    public static void count(Inventory inventory, Map<Item, Integer> counts) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }
}
