package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Lists;
import com.maidbuilder.MaidBuilder;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Hidden task: the maid takes the products of her workplace to the territory's warehouse, then
 * {@link WorkplaceHandler} puts her back on her work task. Started from the territory screen.
 */
public class TaskDeposit implements IMaidTask {
    public static final ResourceLocation UID = MaidBuilder.id("deposit");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return Items.CHEST.getDefaultInstance();
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return Lists.newArrayList(Pair.of(5, new DepositBehavior(0.6f)));
    }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    /** Only started by the territory screen, never picked in TLM's task list. */
    @Override
    public boolean isHidden(EntityMaid maid) {
        return true;
    }

    @Override
    public String getMaidActionSummary() {
        return "Carry the products of the maid's workplace to the territory warehouse";
    }
}
