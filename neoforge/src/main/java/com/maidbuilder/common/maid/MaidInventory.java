package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidbuilder.common.job.BuildJob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Counting and consuming building materials from a maid's inventory. */
final class MaidInventory {
    private MaidInventory() {
    }

    static Map<Item, Integer> count(EntityMaid maid) {
        IItemHandler inv = maid.getAvailableInv(false);
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    static boolean has(Map<Item, Integer> counts, List<BuildJob.Requirement> requirements) {
        for (BuildJob.Requirement r : requirements) {
            if (counts.getOrDefault(r.item(), 0) < r.count()) return false;
        }
        return true;
    }

    static boolean has(EntityMaid maid, List<BuildJob.Requirement> requirements) {
        return has(count(maid), requirements);
    }

    static int count(EntityMaid maid, Item item) {
        return count(maid).getOrDefault(item, 0);
    }

    /** Puts items into the maid's inventory; what does not fit is dropped at her feet. */
    static void give(EntityMaid maid, ItemStack stack) {
        ItemStack rest = ItemHandlerHelper.insertItemStacked(maid.getAvailableInv(false), stack, false);
        if (!rest.isEmpty()) maid.spawnAtLocation(rest);
    }

    /** Removes the items; callers check {@link #has} first. */
    static void consume(EntityMaid maid, List<BuildJob.Requirement> requirements) {
        IItemHandler inv = maid.getAvailableInv(false);
        for (BuildJob.Requirement r : requirements) {
            int remaining = r.count();
            for (int i = 0; i < inv.getSlots() && remaining > 0; i++) {
                if (inv.getStackInSlot(i).is(r.item())) {
                    remaining -= inv.extractItem(i, remaining, false).getCount();
                }
            }
        }
    }
}
