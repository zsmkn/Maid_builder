package com.maidbuilder.common.maid;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidbuilder.MaidBuilderConfig;
import com.maidbuilder.common.job.BuildJob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds a way to get within reach of a block: first a spot the maid can walk to, then (if none
 * exists inside the job's work area) a scaffolding column to climb. All searches are bounded:
 * a few dozen block lookups plus at most {@link #STAND_PATH_TRIES} / {@link #COLUMN_PATH_TRIES}
 * path computations.
 */
final class ReachPlanner {
    private static final int STAND_PATH_TRIES = 4;
    private static final int COLUMN_PATH_TRIES = 3;
    /** Columns are put up at most this far (horizontally) from the block they are for. */
    private static final int COLUMN_OFFSET = 3;
    /** Keep a little slack so standing slightly off-centre still reaches. */
    private static final double REACH_SLACK = 0.3;

    private ReachPlanner() {
    }

    static double reach() {
        return MaidBuilderConfig.PLACE_REACH.get() - REACH_SLACK;
    }

    static boolean inReach(EntityMaid maid, BlockPos pos) {
        double reach = MaidBuilderConfig.PLACE_REACH.get();
        return maid.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
    }

    private static boolean reaches(BlockPos feet, double eyeHeight, BlockPos target) {
        double reach = reach();
        return new Vec3(feet.getX() + 0.5, feet.getY() + eyeHeight, feet.getZ() + 0.5).distanceToSqr(Vec3.atCenterOf(target)) <= reach * reach;
    }

    /** Nothing to bump into, and nothing that hurts. */
    static boolean passable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty()
                && !state.is(BlockTags.FIRE) && !state.getFluidState().is(FluidTags.LAVA);
    }

    /** A cell a scaffold may go into: empty or replaceable (grass, snow layer...) and not dangerous. */
    private static boolean buildable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.canBeReplaced() && state.getFluidState().isEmpty() && !state.is(BlockTags.FIRE);
    }

    private static boolean canStandAt(Level level, EntityMaid maid, BlockPos feet) {
        BlockPos below = feet.below();
        return level.getBlockState(below).entityCanStandOn(level, below, maid)
                && passable(level, feet) && passable(level, feet.above());
    }

    /**
     * Whether a path stays inside the job's work area. The first stretch may lie outside (the
     * maid can come from further away); once the path has entered the area it must not leave it,
     * so she never goes round the outside looking for a way up.
     */
    private static boolean staysInArea(Path path, BuildJob job, double margin) {
        boolean entered = false;
        for (int i = 0; i < path.getNodeCount(); i++) {
            boolean inside = job.inWorkArea(path.getNodePos(i), margin);
            if (inside) entered = true;
            else if (entered) return false;
        }
        return entered;
    }

    @Nullable
    private static Path pathTo(EntityMaid maid, BlockPos pos, int accuracy, BuildJob job, double margin) {
        Path path = maid.getNavigation().createPath(pos, accuracy);
        return path != null && path.canReach() && staysInArea(path, job, margin) ? path : null;
    }

    // ---- 1. a walkable spot ----

    /**
     * A spot within reach of {@code target} that the maid can walk to without leaving the work area,
     * nearest first; null if there is none.
     */
    @Nullable
    static BlockPos findStand(ServerLevel level, EntityMaid maid, BuildJob job, BlockPos target) {
        double margin = MaidBuilderConfig.WORK_AREA_MARGIN.get();
        double eye = maid.getEyeHeight();
        int r = (int) Math.ceil(reach());
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -r - (int) Math.ceil(eye); dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (!reaches(feet, eye, target)) continue;
                    if (feet.equals(target) || feet.above().equals(target)) continue;
                    if (job.isStructurePos(feet) || job.isStructurePos(feet.above())) continue;
                    if (!job.inWorkArea(feet, margin) || !level.isLoaded(feet)) continue;
                    if (canStandAt(level, maid, feet)) candidates.add(feet);
                }
            }
        }
        Vec3 from = maid.position();
        candidates.sort(Comparator.comparingDouble(p -> Vec3.atBottomCenterOf(p).distanceToSqr(from)));
        for (int i = 0; i < Math.min(STAND_PATH_TRIES, candidates.size()); i++) {
            if (pathTo(maid, candidates.get(i), 0, job, margin) != null) return candidates.get(i);
        }
        return null;
    }

    // ---- 2. a scaffolding column ----

    /**
     * A scaffolding column at {@code x/z}: blocks {@code base.y .. standY - 1}; the maid stands on
     * top at {@code standY}. {@code newBlocks} of them still have to be placed.
     */
    record Column(BlockPos base, int standY, int newBlocks) {
        int height() {
            return standY - base.getY();
        }

        BlockPos top() {
            return base.atY(standY - 1);
        }

        BlockPos stand() {
            return base.atY(standY);
        }

        long key() {
            return BuildJob.columnKey(base);
        }

        Vec3 standCenter() {
            return Vec3.atBottomCenterOf(stand());
        }
    }

    /** Scaffolding to climb to reach {@code target}, reachable on foot inside the work area; null if none fits. */
    @Nullable
    static Column planColumn(ServerLevel level, EntityMaid maid, BuildJob job, BlockPos target) {
        double margin = MaidBuilderConfig.WORK_AREA_MARGIN.get();
        double eye = maid.getEyeHeight();
        int maxHeight = MaidBuilderConfig.MAX_SCAFFOLD_HEIGHT.get();
        int r = (int) Math.ceil(reach());
        List<Column> candidates = new ArrayList<>();
        for (int dx = -COLUMN_OFFSET; dx <= COLUMN_OFFSET; dx++) {
            for (int dz = -COLUMN_OFFSET; dz <= COLUMN_OFFSET; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos column = target.offset(dx, 0, dz);
                if (!job.inWorkArea(column, margin) || !level.isLoaded(column)) continue;
                if (!job.columnFreeFor(BuildJob.columnKey(column), maid.getUUID())) continue; // a teammate is on it
                // Stand with her eyes level with the target if possible: the blocks above it (usually
                // next in the build order) are then in reach from the same spot. Lower if that fails.
                int eyeLevel = (int) Math.floor(target.getY() + 1.0 - eye);
                for (int standY = eyeLevel; standY >= target.getY() - r - (int) Math.ceil(eye); standY--) {
                    BlockPos stand = column.atY(standY);
                    if (!reaches(stand, eye, target)) continue;
                    Column c = columnBelow(level, job, stand, maxHeight);
                    if (c != null) {
                        candidates.add(c);
                        break;
                    }
                }
            }
        }
        Vec3 from = maid.position();
        // fewest new blocks first (reuses existing columns), then closest to the maid
        candidates.sort(Comparator.<Column>comparingInt(Column::newBlocks)
                .thenComparingDouble(c -> Vec3.atBottomCenterOf(c.base()).distanceToSqr(from)));
        int tries = 0;
        for (Column c : candidates) {
            if (tries >= COLUMN_PATH_TRIES) break;
            if (maid.distanceToSqr(Vec3.atBottomCenterOf(c.base())) <= 2.0) return c;
            tries++;
            if (pathTo(maid, c.base(), 1, job, margin) != null) return c;
        }
        return null;
    }

    /**
     * The column at {@code base} raised so that, standing on top, the maid reaches {@code target};
     * null if raising this column (above {@code standY}) does not do.
     */
    @Nullable
    static Column raiseColumn(ServerLevel level, EntityMaid maid, BuildJob job, BlockPos base, int standY, BlockPos target) {
        double eye = maid.getEyeHeight();
        int maxHeight = MaidBuilderConfig.MAX_SCAFFOLD_HEIGHT.get();
        // as in planColumn: eyes level with the target if possible, lower otherwise
        for (int y = (int) Math.floor(target.getY() + 1.0 - eye); y > standY; y--) {
            BlockPos stand = base.atY(y);
            if (!reaches(stand, eye, target)) continue;
            Column c = columnBelow(level, job, stand, maxHeight);
            if (c != null && c.base().equals(base)) return c;
        }
        return null;
    }

    /**
     * Checks the cells under a stand position down to firm ground: they must be empty (or already
     * scaffolding) and not part of the structure, and the stand position must leave head room.
     */
    @Nullable
    private static Column columnBelow(Level level, BuildJob job, BlockPos stand, int maxHeight) {
        if (job.isStructurePos(stand) || job.isStructurePos(stand.above())) return null;
        if (!passable(level, stand) || !passable(level, stand.above())) return null;
        int newBlocks = 0;
        for (int y = stand.getY() - 1; y >= stand.getY() - maxHeight; y--) {
            BlockPos cell = stand.atY(y);
            if (!level.isLoaded(cell) || level.isOutsideBuildHeight(cell)) return null;
            BlockState state = level.getBlockState(cell);
            if (state.is(Blocks.SCAFFOLDING)) {
                // an existing column: fine to reuse
            } else if (buildable(level, cell) && !job.isStructurePos(cell)) {
                newBlocks++;
            } else {
                return null;
            }
            BlockPos below = cell.below();
            BlockState belowState = level.getBlockState(below);
            if (!belowState.is(Blocks.SCAFFOLDING) && belowState.isFaceSturdy(level, below, Direction.UP)) {
                return new Column(cell, stand.getY(), newBlocks);
            }
        }
        return null;
    }

    /** Whether a teammate currently uses one of the job's scaffold columns. */
    static boolean otherColumnsInUse(BuildJob job, EntityMaid maid) {
        for (long key : job.scaffoldColumns().keySet()) {
            if (!job.columnFreeFor(key, maid.getUUID())) return true;
        }
        return false;
    }

    /** The state a scaffold placed by hand at {@code pos} would get. */
    static BlockState scaffoldState(Level level, BlockPos pos) {
        int distance = ScaffoldingBlock.getDistance(level, pos);
        return Blocks.SCAFFOLDING.defaultBlockState()
                .setValue(ScaffoldingBlock.DISTANCE, distance)
                .setValue(ScaffoldingBlock.BOTTOM, distance > 0 && !level.getBlockState(pos.below()).is(Blocks.SCAFFOLDING));
    }

    // ---- where the maid is ----

    /**
     * The tracked column the maid is standing on or climbing in, if any. Standing in its bottom
     * block on the ground does not count: that is where she enters and leaves it.
     */
    @Nullable
    static List<BlockPos> columnAt(EntityMaid maid, BuildJob job) {
        if (job.scaffolds().isEmpty()) return null;
        BlockPos feet = maid.blockPosition();
        if (!job.isScaffold(feet) && !job.isScaffold(feet.below())) return null;
        List<BlockPos> column = job.scaffoldColumns().get(BuildJob.columnKey(feet));
        return column != null && maid.getY() > column.getFirst().getY() + 0.5 ? column : null;
    }

    /** Whether the maid's box sits inside the column's x/z cell (she is climbing or standing on it). */
    static boolean overColumn(EntityMaid maid, BlockPos base) {
        AABB box = maid.getBoundingBox();
        double cx = base.getX() + 0.5, cz = base.getZ() + 0.5;
        return Math.abs((box.minX + box.maxX) / 2 - cx) < 0.5 && Math.abs((box.minZ + box.maxZ) / 2 - cz) < 0.5;
    }
}
