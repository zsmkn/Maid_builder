package com.maidbuilder.common.capture;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * Finds the building a block belongs to: every block connected to it (also diagonally) that is
 * not terrain. Grass, dirt, natural stone, sand, gravel, ores, water, plants and leaves are
 * terrain, so a building standing on or against the ground stops there.
 */
public final class StructureDetector {
    public enum Outcome {
        /** A building of more than one block was found. */
        FOUND,
        /** The clicked block is terrain or stands alone. */
        NONE,
        /** It goes on beyond the limits (or into unloaded chunks): too big to tell. */
        TOO_BIG
    }

    public record Result(Outcome outcome, BlockPos min, BlockPos max, int blocks) {
    }

    private StructureDetector() {
    }

    public static boolean isTerrain(BlockState state) {
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) return true;
        // short and tall grass, ferns, vines, snow layers, dead bushes...
        if (state.canBeReplaced()) return true;
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER) || state.is(Tags.Blocks.GRAVELS) || state.is(Tags.Blocks.ORES)
                || state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE) || state.is(Blocks.BEDROCK) || state.is(Blocks.CLAY)
                || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.CACTUS) || state.is(Blocks.BAMBOO) || state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT) || state.is(Blocks.SEAGRASS) || state.is(Blocks.TALL_SEAGRASS);
    }

    /**
     * @param maxBlocks stop (TOO_BIG) after this many building blocks
     * @param maxVolume stop (TOO_BIG) once the box around them grows beyond this
     */
    public static Result detect(Level level, BlockPos start, int maxBlocks, long maxVolume) {
        if (!level.isLoaded(start) || isTerrain(level.getBlockState(start))) return new Result(Outcome.NONE, start, start, 0);
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(start.asLong());
        queue.enqueue(start.asLong());
        int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
        int maxX = minX, maxY = minY, maxZ = minZ;
        int blocks = 0;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty()) {
            long packed = queue.dequeueLong();
            int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
            if (++blocks > maxBlocks) return new Result(Outcome.TOO_BIG, start, start, blocks);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > maxVolume) {
                return new Result(Outcome.TOO_BIG, start, start, blocks);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        next.set(x + dx, y + dy, z + dz);
                        long key = next.asLong();
                        // terrain is remembered too, so it is looked at only once
                        if (!seen.add(key) || level.isOutsideBuildHeight(next)) continue;
                        if (!level.isLoaded(next)) return new Result(Outcome.TOO_BIG, start, start, blocks);
                        if (isTerrain(level.getBlockState(next))) continue;
                        queue.enqueue(key);
                    }
                }
            }
        }
        if (blocks <= 1) return new Result(Outcome.NONE, start, start, blocks);
        return new Result(Outcome.FOUND, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), blocks);
    }
}
