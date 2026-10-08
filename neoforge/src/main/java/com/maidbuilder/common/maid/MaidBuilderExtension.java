package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.api.entity.data.TaskDataKey;
import com.github.tartaricacid.touhoulittlemaid.entity.data.TaskDataRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.maidbuilder.MaidBuilder;

/** Discovered by Touhou Little Maid through the {@link LittleMaidExtension} annotation. */
@LittleMaidExtension
public class MaidBuilderExtension implements ILittleMaid {
    public static TaskDataKey<BuilderMaidData> BUILDER_DATA;
    public static TaskDataKey<WorkplaceMaidData> WORKPLACE_DATA;

    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new TaskBuilder());
        manager.add(new TaskDeposit());
    }

    @Override
    public void registerTaskData(TaskDataRegister register) {
        BUILDER_DATA = register.register(MaidBuilder.id("builder"), BuilderMaidData.CODEC);
        WORKPLACE_DATA = register.register(MaidBuilder.id("workplace"), WorkplaceMaidData.CODEC);
    }
}
