package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-maid data stored through TLM's task data system (it must encode to a compound tag and can
 * never be set to null, hence the optionals).
 *
 * @param job          the bound build job; empty once unbound
 * @param savedWorkPos her real TLM work point while {@link BuilderRangeHandler} overrides it
 */
public record BuilderMaidData(Optional<UUID> job, Optional<BlockPos> savedWorkPos) {
    public static final BuilderMaidData EMPTY = new BuilderMaidData(Optional.empty(), Optional.empty());

    public static final Codec<BuilderMaidData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.optionalFieldOf("job").forGetter(BuilderMaidData::job),
            BlockPos.CODEC.optionalFieldOf("saved_work_pos").forGetter(BuilderMaidData::savedWorkPos)
    ).apply(i, BuilderMaidData::new));

    public static BuilderMaidData of(EntityMaid maid) {
        if (MaidBuilderExtension.BUILDER_DATA == null) return EMPTY;
        BuilderMaidData data = maid.getData(MaidBuilderExtension.BUILDER_DATA);
        return data == null ? EMPTY : data;
    }

    @Nullable
    public static UUID jobOf(EntityMaid maid) {
        return of(maid).job().orElse(null);
    }

    public static void bind(EntityMaid maid, UUID job) {
        maid.setAndSyncData(MaidBuilderExtension.BUILDER_DATA, new BuilderMaidData(Optional.of(job), of(maid).savedWorkPos()));
    }

    public static void unbind(EntityMaid maid) {
        maid.setAndSyncData(MaidBuilderExtension.BUILDER_DATA, new BuilderMaidData(Optional.empty(), of(maid).savedWorkPos()));
    }

    static void setSavedWorkPos(EntityMaid maid, @Nullable BlockPos pos) {
        maid.setData(MaidBuilderExtension.BUILDER_DATA, new BuilderMaidData(of(maid).job(), Optional.ofNullable(pos)));
    }
}
