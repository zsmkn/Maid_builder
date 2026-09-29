package com.maidbuilder.item;

import com.maidbuilder.common.Convert;
import com.maidbuilder.core.transform.Placement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * The schematic a Blueprint Wand is positioning: which file (by name in the player's
 * {@code schematics/} folder, plus its SHA-1 so the server can tell whether it has it),
 * and where / how it is placed.
 */
public record WandPlacement(String file, String sha1, BlockPos origin, Rotation rotation, Mirror mirror) {
    public static final Codec<WandPlacement> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("file").forGetter(WandPlacement::file),
            Codec.STRING.fieldOf("sha1").forGetter(WandPlacement::sha1),
            BlockPos.CODEC.fieldOf("origin").forGetter(WandPlacement::origin),
            Rotation.CODEC.optionalFieldOf("rotation", Rotation.NONE).forGetter(WandPlacement::rotation),
            Mirror.CODEC.optionalFieldOf("mirror", Mirror.NONE).forGetter(WandPlacement::mirror)
    ).apply(i, WandPlacement::new));

    public static final StreamCodec<ByteBuf, WandPlacement> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, WandPlacement::file,
            ByteBufCodecs.STRING_UTF8, WandPlacement::sha1,
            BlockPos.STREAM_CODEC, WandPlacement::origin,
            ByteBufCodecs.VAR_INT.map(i -> Rotation.values()[Math.floorMod(i, 4)], Rotation::ordinal), WandPlacement::rotation,
            ByteBufCodecs.VAR_INT.map(i -> Mirror.values()[Math.floorMod(i, 3)], Mirror::ordinal), WandPlacement::mirror,
            WandPlacement::new);

    public WandPlacement withOrigin(BlockPos pos) {
        return new WandPlacement(file, sha1, pos.immutable(), rotation, mirror);
    }

    public WandPlacement rotated() {
        return new WandPlacement(file, sha1, origin, rotation.getRotated(Rotation.CLOCKWISE_90), mirror);
    }

    public WandPlacement mirrored() {
        Mirror next = switch (mirror) {
            case NONE -> Mirror.LEFT_RIGHT;
            case LEFT_RIGHT -> Mirror.FRONT_BACK;
            case FRONT_BACK -> Mirror.NONE;
        };
        return new WandPlacement(file, sha1, origin, rotation, next);
    }

    public Placement toCore() {
        return Convert.placement(origin, rotation, mirror);
    }
}
