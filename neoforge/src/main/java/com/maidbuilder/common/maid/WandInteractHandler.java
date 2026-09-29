package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.api.event.InteractMaidEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.maidbuilder.MaidBuilder;
import com.maidbuilder.common.job.BuildJob;
import com.maidbuilder.common.job.BuildJobManager;
import com.maidbuilder.init.ModDataComponents;
import com.maidbuilder.init.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Optional;
import java.util.UUID;

/** Right-clicking an owned maid with a linked Blueprint Wand binds her to the job and switches her to the Builder task. */
@EventBusSubscriber(modid = MaidBuilder.MOD_ID)
public final class WandInteractHandler {
    private WandInteractHandler() {
    }

    @SubscribeEvent
    public static void onInteractMaid(InteractMaidEvent event) {
        ItemStack stack = event.getStack();
        if (!stack.is(ModItems.BLUEPRINT_WAND.get())) return;
        event.setCanceled(true);
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        EntityMaid maid = event.getMaid();
        UUID jobId = stack.get(ModDataComponents.BUILD_JOB.get());
        if (jobId == null) {
            player.sendSystemMessage(Component.translatable("message.maidbuilder.wand.unlinked"));
            return;
        }
        if (!maid.isOwnedBy(player)) {
            player.sendSystemMessage(Component.translatable("message.maidbuilder.maid.not_owner"));
            return;
        }
        bind(player, maid, jobId);
    }

    /** Shared by the wand and {@code /maidbuilder job bind}. Returns whether the maid was bound. */
    public static boolean bind(Player feedback, EntityMaid maid, UUID jobId) {
        Optional<BuildJob> job = BuildJobManager.loaded(feedback.getServer(), jobId);
        if (job.isEmpty()) {
            feedback.sendSystemMessage(Component.translatable("message.maidbuilder.job.not_found", jobId.toString()));
            return false;
        }
        BuilderMaidData.bind(maid, jobId);
        TaskManager.findTask(TaskBuilder.UID).ifPresent(maid::setTask);
        feedback.sendSystemMessage(Component.translatable("message.maidbuilder.maid.bound",
                maid.getDisplayName(), job.get().schematicName(), job.get().shortId()));
        return true;
    }
}
