package com.maidbuilder.init;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.item.CaptureArea;
import com.maidbuilder.item.WandPlacement;
import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Data the mod keeps on its items. 1.20.1 has no data components, so each value lives in the
 * stack's NBT under {@code maidbuilder.<name>}, encoded with the same codec (and name) as the
 * 1.21.1 data component.
 */
public final class ModItemData {
    private static final String ROOT = MaidBuilder.MOD_ID;

    /** The build job a Blueprint Wand is linked to. */
    public static final ItemData<UUID> BUILD_JOB = new ItemData<>("build_job", UUIDUtil.CODEC);

    /** The schematic placement a Blueprint Wand is positioning or has built. */
    public static final ItemData<WandPlacement> WAND_PLACEMENT = new ItemData<>("wand_placement", WandPlacement.CODEC);

    /** The corners a Blueprint Quill has marked. */
    public static final ItemData<CaptureArea> CAPTURE_AREA = new ItemData<>("capture_area", CaptureArea.CODEC);

    private ModItemData() {
    }

    public static final class ItemData<T> {
        private final String name;
        private final Codec<T> codec;

        private ItemData(String name, Codec<T> codec) {
            this.name = name;
            this.codec = codec;
        }

        public boolean has(ItemStack stack) {
            CompoundTag root = stack.getTagElement(ROOT);
            return root != null && root.contains(name);
        }

        @Nullable
        public T get(ItemStack stack) {
            CompoundTag root = stack.getTagElement(ROOT);
            if (root == null || !root.contains(name)) return null;
            return codec.parse(NbtOps.INSTANCE, root.get(name)).result().orElse(null);
        }

        public void set(ItemStack stack, @Nullable T value) {
            if (value == null) {
                remove(stack);
                return;
            }
            Tag tag = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow(false, MaidBuilder.LOGGER::error);
            stack.getOrCreateTagElement(ROOT).put(name, tag);
        }

        public void remove(ItemStack stack) {
            CompoundTag root = stack.getTagElement(ROOT);
            if (root == null) return;
            root.remove(name);
            if (root.isEmpty()) stack.removeTagKey(ROOT);
        }
    }
}
