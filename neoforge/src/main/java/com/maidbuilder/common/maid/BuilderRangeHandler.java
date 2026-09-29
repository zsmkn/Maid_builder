package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Lets a builder maid in home mode work on the whole structure, however big, without changing
 * TLM's settings.
 * <p>TLM re-applies the work range (work point + {@code MaidWorkRange}) every 40 ticks and
 * teleports a maid that is outside it back to the work point. While she works on a job, this
 * handler (before each maid tick):
 * <ul>
 *   <li>moves her TLM work point to where she stands, so that check always passes, and</li>
 *   <li>sets her movement range to a sphere around the structure (plus the work area margin and
 *       its material containers), which is what TLM's path finder keeps her inside.</li>
 * </ul>
 * Her real work point is kept in {@link BuilderMaidData} and put back as soon as the job no longer
 * needs her, her task or schedule changes, or home mode is switched off. Also unbinds maids whose
 * job was deleted.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class BuilderRangeHandler {
    /** The work point this handler last wrote, to notice when the player moves it meanwhile. */
    private static final Map<EntityMaid, BlockPos> WRITTEN = new WeakHashMap<>();

    private BuilderRangeHandler() {
    }

    @SubscribeEvent
    public static void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        if (!(maid.level() instanceof ServerLevel level) || MaidBuilderExtension.BUILDER_DATA == null) return;
        BuilderMaidData data = BuilderMaidData.of(maid);
        if (data == BuilderMaidData.EMPTY) return;

        BuildJob job = null;
        UUID jobId = data.job().orElse(null);
        if (jobId != null) {
            job = BuildJobManager.data(level.getServer()).get(jobId);
            if (job == null) {
                BuilderMaidData.unbind(maid);
                data = BuilderMaidData.of(maid);
            }
        }

        SchedulePos schedule = maid.getSchedulePos();
        boolean active = job != null && job.isLoaded() && job.needsMaids()
                && job.dimension().equals(level.dimension())
                && maid.isHomeModeEnable() && maid.getScheduleDetail() == Activity.WORK
                && maid.getTask().getUid().equals(TaskBuilder.UID)
                && schedule.getWorkPos() != null;
        if (active) {
            BlockPos current = schedule.getWorkPos();
            BlockPos written = WRITTEN.get(maid);
            if (data.savedWorkPos().isEmpty() || written != null && !written.equals(current)) {
                // first tick of the job, or the player set a new work point meanwhile
                BuilderMaidData.setSavedWorkPos(maid, current);
            }
            BlockPos here = maid.blockPosition();
            schedule.setWorkPos(here);
            WRITTEN.put(maid, here);
            maid.restrictTo(BlockPos.containing(job.center()), (int) Math.ceil(range(maid, job)));
        } else if (data.savedWorkPos().isPresent()) {
            BlockPos written = WRITTEN.remove(maid);
            BlockPos current = schedule.getWorkPos();
            // keep a work point the player chose meanwhile; otherwise put hers back
            if (written == null || written.equals(current)) schedule.setWorkPos(data.savedWorkPos().get());
            BuilderMaidData.setSavedWorkPos(maid, null);
            schedule.restrictTo(maid);
        }
    }

    /** Covers the structure, the area she may look for a way up in, and the job's material containers. */
    private static double range(EntityMaid maid, BuildJob job) {
        Vec3 center = job.center();
        double range = Math.max(job.radius() + MaidBuilderConfig.WORK_AREA_MARGIN.get() + 2, maid.getRestrictRadius());
        for (BlockPos source : job.materialSources()) {
            range = Math.max(range, Math.sqrt(Vec3.atCenterOf(source).distanceToSqr(center)) + 3);
        }
        return range;
    }
}
