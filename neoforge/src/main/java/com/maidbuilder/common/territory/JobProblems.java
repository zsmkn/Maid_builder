package com.maidbuilder.common.territory;

import com.maidbuilder.common.BlockPlacer;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.network.TerritoryPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** The blocks of a queued job the maids left for the player: the details list, retrying and skipping them. */
public final class JobProblems {
    /** Rows sent at most (nearest first). */
    static final int MAX_ROWS = 200;

    private JobProblems() {
    }

    /** Sends the job's blocks left for the player, after dropping those the player has seen to already. */
    public static void send(ServerPlayer player, Territory territory, @Nullable UUID jobId) {
        BuildJob job = queued(player, territory, jobId);
        if (job == null) return;
        ServerLevel level = player.server.getLevel(job.dimension());
        if (level == null) return;
        job.recheckNeedsPlayer(level);
        PacketDistributor.sendToPlayer(player, details(level, territory, job, player.blockPosition()));
    }

    public static TerritoryPayloads.JobProblems details(ServerLevel level, Territory territory, BuildJob job, BlockPos near) {
        List<Integer> steps = new ArrayList<>();
        for (int i = 0; i < job.size(); i++) if (job.status(i) == BuildJob.NEEDS_PLAYER) steps.add(i);
        steps.sort(Comparator.comparingDouble(i -> job.pos(i).distSqr(near)));
        List<TerritoryPayloads.ProblemRow> rows = new ArrayList<>();
        for (int i : steps.subList(0, Math.min(MAX_ROWS, steps.size()))) {
            BlockPos pos = job.pos(i);
            BlockState target = job.isClearStep(i) ? Blocks.AIR.defaultBlockState() : job.target(i);
            rows.add(new TerritoryPayloads.ProblemRow(pos, target, current(level, pos, target), job.problem(i)));
        }
        return new TerritoryPayloads.JobProblems(territory.id(), job.id(), job.schematicName(), job.templateId() == null ? "" : job.templateId(),
                job.dimension().location(), steps.size(), rows);
    }

    /** What stands where the block goes: the first of its cells (e.g. a door's two) that is not simply replaced. */
    private static BlockState current(ServerLevel level, BlockPos pos, BlockState target) {
        if (!level.isLoaded(pos)) return Blocks.AIR.defaultBlockState();
        for (BlockPlacer.Part part : BlockPlacer.partsOf(pos, target)) {
            BlockState there = level.getBlockState(part.pos());
            if (!there.canBeReplaced()) return there;
        }
        return level.getBlockState(pos);
    }

    /** Hands the blocks back to the maids ({@code skip} false), or gives up on them so the job can finish. */
    public static void resolve(ServerPlayer player, Territory territory, @Nullable UUID jobId, boolean skip) {
        BuildJob job = queued(player, territory, jobId);
        if (job == null) return;
        int n = skip ? job.skipNeedsPlayer() : job.retryNeedsPlayer();
        if (n == 0) return;
        player.displayClientMessage(Component.translatable(skip ? "message.maidbuilder.territory.problems_skipped"
                : "message.maidbuilder.territory.problems_retried", n), true);
        if (skip) TerritoryTicker.updateQueue(player.server, territory);
    }

    @Nullable
    private static BuildJob queued(ServerPlayer player, Territory territory, @Nullable UUID jobId) {
        if (jobId == null || !territory.jobQueue().contains(jobId)) return null;
        return BuildJobManager.loaded(player.server, jobId).orElse(null);
    }
}
