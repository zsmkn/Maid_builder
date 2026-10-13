package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.common.territory.Territory;
import com.maidbuilder.common.territory.TerritoryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Territory duties of a maid, checked once a second: a Builder maid without an unfinished job of
 * her own is bound to the first unfinished job in the queue of the territory she lives in (or
 * stands in, without home mode). She stays on a finished job while its scaffolding comes down.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class TerritoryMaidHandler {
    private static final int INTERVAL = 20;
    /** How often a maid reports where she lives (a multiple of {@link #INTERVAL}). */
    private static final int RESIDENT_INTERVAL = 100;

    private TerritoryMaidHandler() {
    }

    @SubscribeEvent
    public static void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        if (!(maid.level() instanceof ServerLevel level) || MaidBuilderExtension.BUILDER_DATA == null) return;
        int phase = (maid.tickCount + maid.getId()) % RESIDENT_INTERVAL;
        if (phase % INTERVAL != 0) return;
        updateBuilder(level, maid);
        if (phase == 0) reportResidence(level, maid);
    }

    /** A maid in home mode lives where her work point is (her real one while a handler borrows it). */
    public static void reportResidence(ServerLevel level, EntityMaid maid) {
        BlockPos home = null;
        if (maid.isHomeModeEnable()) {
            home = BuilderMaidData.of(maid).savedWorkPos().orElse(maid.getSchedulePos().getWorkPos());
        }
        TerritoryManager.reportResident(level.getServer(), maid.getUUID(), maid.getOwnerUUID(), level.dimension(), home);
    }

    /** The owner's active territory the maid belongs to: where her home is (home mode), else where she stands. */
    @Nullable
    public static Territory territoryFor(ServerLevel level, EntityMaid maid) {
        UUID owner = maid.getOwnerUUID();
        if (owner == null) return null;
        BlockPos pos = maid.isHomeModeEnable()
                ? BuilderMaidData.of(maid).savedWorkPos().orElse(maid.getSchedulePos().getWorkPos()) : maid.blockPosition();
        if (pos == null) pos = maid.blockPosition();
        Territory territory = TerritoryManager.activeAt(level.getServer(), level.dimension(), pos);
        return territory != null && territory.isOwnedBy(owner) ? territory : null;
    }

    /** Binds a Builder maid that has nothing (left) to build to the next job of her territory. */
    public static void updateBuilder(ServerLevel level, EntityMaid maid) {
        if (!maid.getTask().getUid().equals(TaskBuilder.UID)) return;
        UUID current = BuilderMaidData.jobOf(maid);
        BuildJob currentJob = current == null ? null : BuildJobManager.data(level.getServer()).get(current);
        // her own job (from a wand, or a territory job she is on) comes first, scaffolding included
        if (currentJob != null && currentJob.needsMaids()) return;
        Territory territory = territoryFor(level, maid);
        if (territory == null) return;
        // the first job of the queue that still has blocks to build in her dimension
        for (UUID id : territory.jobQueue()) {
            BuildJob job = BuildJobManager.data(level.getServer()).get(id);
            if (job == null || job.isFinished() || !job.dimension().equals(level.dimension())) continue;
            if (!id.equals(current)) BuilderMaidData.bind(maid, id);
            return;
        }
        // nothing to build: wait
        if (current != null) BuilderMaidData.unbind(maid);
    }
}
