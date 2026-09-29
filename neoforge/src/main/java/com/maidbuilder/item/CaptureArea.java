package com.maidbuilder.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Optional;

/** The corners a Blueprint Quill has marked; {@code second} is empty until the second click. */
public record CaptureArea(ResourceKey<Level> dimension, BlockPos first, Optional<BlockPos> second) {
    public static final Codec<CaptureArea> CODEC = RecordCodecBuilder.create(i -> i.group(
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(CaptureArea::dimension),
            BlockPos.CODEC.fieldOf("first").forGetter(CaptureArea::first),
            BlockPos.CODEC.optionalFieldOf("second").forGetter(CaptureArea::second)
    ).apply(i, CaptureArea::new));

    public static final StreamCodec<ByteBuf, CaptureArea> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), CaptureArea::dimension,
            BlockPos.STREAM_CODEC, CaptureArea::first,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), CaptureArea::second,
            CaptureArea::new);

    public boolean complete() {
        return second.isPresent();
    }

    public BlockPos min() {
        return second.map(s -> BlockPos.min(first, s)).orElse(first);
    }

    public BlockPos max() {
        return second.map(s -> BlockPos.max(first, s)).orElse(first);
    }

    public BlockPos size() {
        BlockPos min = min(), max = max();
        return new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
    }

    public long volume() {
        BlockPos s = size();
        return (long) s.getX() * s.getY() * s.getZ();
    }
}
