package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.SchedulePos;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskIdle;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.territory.RegisteredBuilding;
import com.maidbuilder.common.territory.Territory;
import com.maidbuilder.common.territory.TerritoryManager;
import com.maidbuilder.common.territory.WorkplaceActions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * Maids working at territory buildings, checked every two seconds:
 * <ul>
 *   <li>a full backpack: she rests (idle task) and remembers her work task;</li>
 *   <li>resting with at least {@code resumeFreeFraction} of the backpack free again: back to work
 *       (unless the player gave her another task meanwhile);</li>
 *   <li>done depositing: back to the task she had.</li>
 * </ul>
 * While she takes products to the warehouse (every tick), her schedule points follow her and her
 * movement range covers the container, so TLM neither keeps her near her work point nor teleports
 * her back; they are restored afterwards.
 */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class WorkplaceHandler {
    private static final int INTERVAL = 40;
    private static final int DETECT_INTERVAL = 60;

    private WorkplaceHandler() {
    }

    @SubscribeEvent
    public static void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        if (!(maid.level() instanceof ServerLevel level) || MaidBuilderExtension.WORKPLACE_DATA == null) return;
        WorkplaceMaidData data = WorkplaceMaidData.of(maid);
        if (data.building().isEmpty() && !data.depositing() && data.savedSchedule().isEmpty()) {
            // set up by hand at a building? (every few seconds; cheap: one chunk lookup)
            if ((maid.tickCount + maid.getId()) % DETECT_INTERVAL == 0) WorkplaceActions.detect(level, maid);
            return;
        }

        if (data.depositing()) {
            data = borrowSchedule(maid, data);
        } else if (data.savedSchedule().isPresent()) {
            WorkplaceActions.restoreSchedule(maid, data);
            data = data.withSavedSchedule(null);
            WorkplaceMaidData.set(maid, data);
        }
        if ((maid.tickCount + maid.getId()) % INTERVAL != 0) return;
        check(level, maid, data);
    }

    /** Runs the periodic checks now (also used by game tests). */
    public static void check(ServerLevel level, EntityMaid maid, WorkplaceMaidData data) {
        ResourceLocation task = maid.getTask().getUid();
        if (task.equals(TaskDeposit.UID)) {
            if (!data.depositing()) backToWork(maid, data);
            return;
        }
        if (data.depositing()) {
            // the player switched her task while she was on her way
            WorkplaceMaidData.set(maid, data.withDeposit(false, null));
            return;
        }
        Territory territory = data.territory().map(id -> TerritoryManager.data(level.getServer()).get(id)).orElse(null);
        RegisteredBuilding building = territory == null || data.building().isEmpty() ? null : territory.building(data.building().get());
        if (building == null) {
            WorkplaceActions.release(level.getServer(), maid);
            return;
        }
        if (data.paused()) {
            if (!task.equals(TaskIdle.UID)) {
                // the player chose another task: she no longer works here (unless it is this building's)
                WorkplaceMaidData.set(maid, data.withPausedTask(null));
                if (!isWorkTask(building, task)) WorkplaceActions.release(level.getServer(), maid);
            } else if (freeFraction(maid) >= MaidBuilderConfig.RESUME_FREE_FRACTION.get()) {
                WorkplaceActions.resume(maid);
            }
        } else if (!isWorkTask(building, task) || !WorkplaceActions.isAt(building, WorkplaceActions.workSpot(maid))) {
            // the player gave her another task or moved her home: she no longer works here
            WorkplaceActions.release(level.getServer(), maid);
        } else if (freeFraction(maid) == 0 && building.assignedMaids().contains(maid.getUUID()) && !task.equals(TaskIdle.UID)) {
            WorkplaceMaidData.set(maid, data.withPausedTask(task.toString()));
            maid.setTask(TaskManager.getIdleTask());
            WorkplaceActions.notifyOwner(maid, Component.translatable("message.maidbuilder.workplace.paused", maid.getDisplayName()));
        }
    }

    private static boolean isWorkTask(RegisteredBuilding building, ResourceLocation task) {
        var workplace = WorkplaceActions.workplaceOf(building);
        return workplace != null && workplace.task().equals(task);
    }

    /** A maid that dies stops working at her building (her place is free again). */
    @SubscribeEvent
    public static void onDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof EntityMaid maid && maid.level() instanceof ServerLevel level
                && MaidBuilderExtension.WORKPLACE_DATA != null) {
            WorkplaceActions.release(level.getServer(), maid);
        }
    }

    private static void backToWork(EntityMaid maid, WorkplaceMaidData data) {
        String back = data.returnTask().orElse(null);
        WorkplaceMaidData.set(maid, data.withDeposit(false, null));
        if (back == null) {
            maid.setTask(TaskManager.getIdleTask());
        } else {
            maid.setTask(TaskManager.findTask(ResourceLocation.parse(back)).orElse(TaskManager.getIdleTask()));
        }
    }

    /** Share of the backpack's slots that are empty (0 when it has none). */
    public static double freeFraction(EntityMaid maid) {
        IItemHandler backpack = maid.getAvailableBackpackInv();
        if (backpack.getSlots() == 0) return 0;
        int free = 0;
        for (int i = 0; i < backpack.getSlots(); i++) if (backpack.getStackInSlot(i).isEmpty()) free++;
        return (double) free / backpack.getSlots();
    }

    private static WorkplaceMaidData borrowSchedule(EntityMaid maid, WorkplaceMaidData data) {
        SchedulePos schedule = maid.getSchedulePos();
        BlockPos here = maid.blockPosition();
        if (data.savedSchedule().isEmpty()) {
            data = data.withSavedSchedule(List.of(orHere(schedule.getWorkPos(), here), orHere(schedule.getIdlePos(), here),
                    orHere(schedule.getSleepPos(), here)));
            WorkplaceMaidData.set(maid, data);
        }
        schedule.setWorkPos(here);
        schedule.setIdlePos(here);
        schedule.setSleepPos(here);
        BlockPos target = DepositBehavior.TARGETS.get(maid);
        if (target != null) {
            maid.restrictTo(target, (int) Math.ceil(Math.sqrt(maid.distanceToSqr(Vec3.atCenterOf(target)))) + 8);
        } else {
            maid.restrictTo(here, 64);
        }
        return data;
    }

    private static BlockPos orHere(BlockPos pos, BlockPos here) {
        return pos == null ? here : pos;
    }
}
