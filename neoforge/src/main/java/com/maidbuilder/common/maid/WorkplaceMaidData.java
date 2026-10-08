package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A maid working at a territory building (plan G4), stored through TLM's task data.
 *
 * @param territory     territory of the building
 * @param building      the building she works at
 * @param pausedTask    her work task while she rests because her backpack is full
 * @param depositing    she is taking her products to the warehouse
 * @param returnTask    the task she goes back to after depositing
 * @param savedSchedule her work, idle and sleep points while depositing borrows them
 */
public record WorkplaceMaidData(Optional<UUID> territory, Optional<UUID> building, Optional<String> pausedTask, boolean depositing,
                                Optional<String> returnTask, Optional<List<BlockPos>> savedSchedule) {
    public static final WorkplaceMaidData EMPTY = new WorkplaceMaidData(Optional.empty(), Optional.empty(), Optional.empty(), false,
            Optional.empty(), Optional.empty());

    public static final Codec<WorkplaceMaidData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.optionalFieldOf("territory").forGetter(WorkplaceMaidData::territory),
            UUIDUtil.CODEC.optionalFieldOf("building").forGetter(WorkplaceMaidData::building),
            Codec.STRING.optionalFieldOf("paused_task").forGetter(WorkplaceMaidData::pausedTask),
            Codec.BOOL.optionalFieldOf("depositing", false).forGetter(WorkplaceMaidData::depositing),
            Codec.STRING.optionalFieldOf("return_task").forGetter(WorkplaceMaidData::returnTask),
            BlockPos.CODEC.listOf().optionalFieldOf("saved_schedule").forGetter(WorkplaceMaidData::savedSchedule)
    ).apply(i, WorkplaceMaidData::new));

    public static WorkplaceMaidData of(EntityMaid maid) {
        if (MaidBuilderExtension.WORKPLACE_DATA == null) return EMPTY;
        WorkplaceMaidData data = maid.getData(MaidBuilderExtension.WORKPLACE_DATA);
        return data == null ? EMPTY : data;
    }

    public static void set(EntityMaid maid, WorkplaceMaidData data) {
        maid.setAndSyncData(MaidBuilderExtension.WORKPLACE_DATA, data);
    }

    @Nullable
    public UUID buildingId() {
        return building.orElse(null);
    }

    public boolean paused() {
        return pausedTask.isPresent();
    }

    public WorkplaceMaidData withPausedTask(@Nullable String task) {
        return new WorkplaceMaidData(territory, building, Optional.ofNullable(task), depositing, returnTask, savedSchedule);
    }

    public WorkplaceMaidData withDeposit(boolean value, @Nullable String task) {
        return new WorkplaceMaidData(territory, building, pausedTask, value, Optional.ofNullable(task), savedSchedule);
    }

    public WorkplaceMaidData withDepositing(boolean value) {
        return new WorkplaceMaidData(territory, building, pausedTask, value, returnTask, savedSchedule);
    }

    public WorkplaceMaidData withSavedSchedule(@Nullable List<BlockPos> schedule) {
        return new WorkplaceMaidData(territory, building, pausedTask, depositing, returnTask, Optional.ofNullable(schedule));
    }
}
