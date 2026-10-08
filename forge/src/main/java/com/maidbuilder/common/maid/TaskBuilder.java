package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitSounds;
import com.github.tartaricacid.touhoulittlemaid.util.SoundUtil;
import com.google.common.collect.Lists;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.init.ModItems;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Predicate;

/** The "Builder" maid task: builds the job bound with a Blueprint Wand from her own inventory. */
public class TaskBuilder implements IMaidTask {
    public static final ResourceLocation UID = MaidBuilder.id("builder");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return ModItems.BLUEPRINT_WAND.get().getDefaultInstance();
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return SoundUtil.environmentSound(maid, InitSounds.MAID_IDLE.get(), 0.5f);
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        BuilderSession session = new BuilderSession();
        return Lists.newArrayList(
                Pair.of(5, new BuilderFindTargetTask(session, 0.6f)),
                Pair.of(5, new BuilderFetchMaterialTask(session, 0.6f)),
                Pair.of(5, new BuilderScaffoldTask(session, 0.6f)),
                Pair.of(5, new BuilderTeardownTask(session, 0.6f)),
                Pair.of(6, new BuilderPlaceTask(session)));
    }

    /** Wandering around would keep interrupting the walk to the next block. */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    @Override
    public List<Pair<String, Predicate<EntityMaid>>> getConditionDescription(EntityMaid maid) {
        return List.of(Pair.of("has_job", m -> BuilderMaidData.jobOf(m) != null),
                Pair.of("has_scaffolding", TaskBuilder::hasScaffolding));
    }

    /** Two lines: what the task does, and that scaffolding lets her reach high blocks. */
    @Override
    public List<String> getDescription(EntityMaid maid) {
        return List.of("task.maidbuilder.builder.desc", "task.maidbuilder.builder.desc.scaffolding");
    }

    private static boolean hasScaffolding(EntityMaid maid) {
        var inv = maid.getAvailableInv(false);
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).is(Items.SCAFFOLDING)) return true;
        }
        return false;
    }

    @Override
    public String getMaidActionSummary() {
        return "Build the structure bound with a Maid Builder blueprint wand, using blocks from the maid's inventory; "
                + "puts up scaffolding (from her inventory) to reach high blocks and takes it down afterwards";
    }
}
