package com.maidbuilder.common;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reading and taking items from material containers through the item handler capability. */
public final class MaterialContainers {
    private MaterialContainers() {
    }

    @Nullable
    public static IItemHandler handler(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        return level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
    }

    public static boolean isContainer(Level level, BlockPos pos) {
        return handler(level, pos) != null;
    }

    /** The block itself, plus the other half if it is part of a double chest. */
    public static List<BlockPos> halves(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return List.of(pos, pos.relative(ChestBlock.getConnectedDirection(state)));
        }
        return List.of(pos);
    }

    /** One position per container: a double chest is always represented by the same half. */
    public static BlockPos canonical(Level level, BlockPos pos) {
        BlockPos best = pos;
        for (BlockPos half : halves(level, pos)) if (half.asLong() < best.asLong()) best = half;
        return best.immutable();
    }

    /**
     * The handlers of the given containers, each container once (both halves of a double chest
     * give the same inventory), keyed by position; unloaded or missing ones are left out.
     */
    public static Map<BlockPos, IItemHandler> distinct(Level level, Collection<BlockPos> positions) {
        Map<BlockPos, IItemHandler> result = new LinkedHashMap<>();
        for (BlockPos pos : positions) {
            if (!level.isLoaded(pos)) continue;
            BlockPos key = canonical(level, pos);
            if (result.containsKey(key)) continue;
            IItemHandler handler = handler(level, key);
            if (handler != null) result.put(key, handler);
        }
        return result;
    }

    /** Adds the container's contents to {@code counts}. */
    public static void count(IItemHandler handler, Map<Item, Integer> counts) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    public static boolean containsAny(IItemHandler handler, Map<Item, Integer> wanted) {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty() && wanted.containsKey(stack.getItem())) return true;
        }
        return false;
    }

    /**
     * Moves up to the wanted amounts from {@code from} into {@code to}, limited by the space in
     * {@code to}. Decrements {@code wanted} and returns the number of items moved.
     */
    public static int transfer(IItemHandler from, IItemHandler to, Map<Item, Integer> wanted) {
        int moved = 0;
        for (int slot = 0; slot < from.getSlots() && !wanted.isEmpty(); slot++) {
            ItemStack inSlot = from.getStackInSlot(slot);
            // capture the item now: the slot's stack object may be emptied by the extraction below
            Item item = inSlot.getItem();
            Integer need = inSlot.isEmpty() ? null : wanted.get(item);
            if (need == null) continue;
            ItemStack simulated = from.extractItem(slot, need, true);
            if (simulated.isEmpty()) continue;
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(to, simulated.copy(), true);
            int count = simulated.getCount() - leftover.getCount();
            if (count <= 0) continue; // no room for this item
            ItemStack extracted = from.extractItem(slot, count, false);
            ItemStack notInserted = ItemHandlerHelper.insertItemStacked(to, extracted, false);
            if (!notInserted.isEmpty()) {
                // should not happen after simulation; put it back rather than lose it
                from.insertItem(slot, notInserted, false);
            }
            int done = extracted.getCount() - notInserted.getCount();
            moved += done;
            int remaining = need - done;
            if (remaining <= 0) wanted.remove(item);
            else wanted.put(item, remaining);
        }
        return moved;
    }
}
