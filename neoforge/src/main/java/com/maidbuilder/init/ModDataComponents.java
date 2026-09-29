package com.maidbuilder.init;

import com.maidbuilder.MaidBuilder;
import com.maidbuilder.item.CaptureArea;
import com.maidbuilder.item.WandPlacement;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;

public final class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MaidBuilder.MOD_ID);

    /** The build job a Blueprint Wand is linked to. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> BUILD_JOB =
            COMPONENTS.register("build_job", () -> DataComponentType.<UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    /** The schematic placement a Blueprint Wand is positioning or has built. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<WandPlacement>> WAND_PLACEMENT =
            COMPONENTS.register("wand_placement", () -> DataComponentType.<WandPlacement>builder()
                    .persistent(WandPlacement.CODEC)
                    .networkSynchronized(WandPlacement.STREAM_CODEC)
                    .build());

    /** The corners a Blueprint Quill has marked. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CaptureArea>> CAPTURE_AREA =
            COMPONENTS.register("capture_area", () -> DataComponentType.<CaptureArea>builder()
                    .persistent(CaptureArea.CODEC)
                    .networkSynchronized(CaptureArea.STREAM_CODEC)
                    .build());

    private ModDataComponents() {
    }
}
