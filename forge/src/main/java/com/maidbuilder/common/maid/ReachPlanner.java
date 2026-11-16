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
import java.util.Map;

/**
 * Finds a way to get within reach of a block: first a spot the maid can walk to, then (if none
 * exists inside the job's work area) a scaffolding column to climb. All searches are bounded:
 * a few dozen block lookups plus at most {@link #STAND_PATH_TRIES} / {@link #COLUMN_PATH_TRIES}
 * path computations.
 */
final class ReachPlanner {
    private static final int STAND_PATH_TRIES = 4;
    private static final int COLUMN_PATH_TRIES = 3;
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

    /** A floor covering no higher than this (carpet, a few layers of snow) is walked over like bare floor. */
    private static final double LOW_COVER = 0.25;

    /**
     * Nothing to bump into, and nothing that hurts. A carpet or thin snow counts as nothing: she
     * stands in that cell, on the block under it.
     */
    static boolean passable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.FIRE) || state.getFluidState().is(FluidTags.LAVA)) return false;
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= LOW_COVER;
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
        return path != null && path.canReach() && staysInArea(path, job, margin) && !climbsScaffolding(path, job) ? path : null;
    }

    /**
     * Whether the path climbs one of the job's scaffold columns (TLM's path finder treats scaffolding
     * as a ladder). Columns belong to {@link BuilderScaffoldTask}, which would take her straight back
     * down; walking through a column's foot is fine.
     */
    private static boolean climbsScaffolding(Path path, BuildJob job) {
        for (int i = 0; i < path.getNodeCount(); i++) {
            if (job.isScaffold(path.getNodePos(i).below())) return true;
        }
        return false;
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
        // Candidates are judged from the middle of the block, but a maid stops walking as soon as she
        // enters it; if the block she stands in did not bring her in reach, choosing it again would
        // only send her nowhere (she is already "there"), so look for another spot.
        BlockPos here = inReach(maid, target) ? null : maid.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -r - (int) Math.ceil(eye); dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (!reaches(feet, eye, target)) continue;
                    if (feet.equals(target) || feet.above().equals(target) || feet.equals(here)) continue;
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

    /** Far columns (see {@link #planColumn}) checked per search for a way along the top to the target. */
    private static final int FAR_COLUMN_TRIES = 3;
    /** Pending blocks looked at when weighing where a column serves most of them. */
    private static final int COVER_SCAN = 512;

    /**
     * Scaffolding to climb to reach {@code target}, reachable on foot inside the work area; null if none fits.
     * <p>The column goes where it serves the most blocks still to be built at that height: scaffolding
     * hangs {@link #MAX_HANG} blocks to either side of it, so a column in the middle of a wall covers
     * twice what one at its end does, and she comes down half as often. It may therefore stand out of
     * reach of the target itself ({@code scaffolding} then has to cover the walk over to it); she
     * goes there along the top first (see {@link BuilderScaffoldTask}).
     *
     * @param scaffolding how much scaffolding she carries
     */
    @Nullable
    static Column planColumn(ServerLevel level, EntityMaid maid, BuildJob job, BlockPos target, int scaffolding) {
        double margin = MaidBuilderConfig.WORK_AREA_MARGIN.get();
        double eye = maid.getEyeHeight();
        int maxHeight = MaidBuilderConfig.MAX_SCAFFOLD_HEIGHT.get();
        int r = (int) Math.ceil(reach());
        // Stand with her eyes level with the target if possible: the blocks above it (usually
        // next in the build order) are then in reach from the same spot. Lower if that fails.
        int eyeLevel = (int) Math.floor(target.getY() + 1.0 - eye);
        List<BlockPos> ahead = pendingAround(level, job, target);
        record Candidate(Column column, boolean near, int score) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int dx = -MAX_HANG; dx <= MAX_HANG; dx++) {
            for (int dz = -MAX_HANG; dz <= MAX_HANG; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos column = target.offset(dx, 0, dz);
                if (!job.inWorkArea(column, margin) || !level.isLoaded(column)) continue;
                if (!job.columnFreeFor(BuildJob.columnKey(column), maid.getUUID())) continue; // a teammate is on it
                Column found = null;
                boolean near = false;
                for (int standY = eyeLevel; standY >= target.getY() - r - (int) Math.ceil(eye); standY--) {
                    BlockPos stand = column.atY(standY);
                    if (!reaches(stand, eye, target)) continue;
                    found = columnBelow(level, job, stand, maxHeight);
                    if (found != null) {
                        near = true;
                        break;
                    }
                }
                if (found == null && Math.abs(dx) + Math.abs(dz) <= MAX_HANG) found = columnBelow(level, job, column.atY(eyeLevel), maxHeight);
                if (found == null || found.newBlocks() > scaffolding) continue;
                // a new scaffold costs about what placing a block does
                candidates.add(new Candidate(found, near, covered(ahead, found.base()) - found.newBlocks()));
            }
        }
        Vec3 from = maid.position();
        // most blocks served first, then closest to the maid
        candidates.sort(Comparator.<Candidate>comparingInt(c -> -c.score())
                .thenComparingDouble(c -> Vec3.atBottomCenterOf(c.column().base()).distanceToSqr(from)));
        int tries = 0, farTries = 0;
        for (Candidate candidate : candidates) {
            Column c = candidate.column();
            if (tries >= COLUMN_PATH_TRIES) break;
            if (!candidate.near()) {
                // out of reach of the target: only if she can get over to it along the top
                if (farTries++ >= FAR_COLUMN_TRIES) continue;
                if (planShift(level, maid, job, c.stand(), target, scaffolding - c.newBlocks()) == null) continue;
            }
            if (maid.distanceToSqr(Vec3.atBottomCenterOf(c.base())) <= 2.0) return c;
            tries++;
            if (pathTo(maid, c.base(), 1, job, margin) != null) return c;
        }
        return null;
    }

    /** The blocks still to be built around {@code target} at the height a column for it serves. */
    private static List<BlockPos> pendingAround(ServerLevel level, BuildJob job, BlockPos target) {
        List<BlockPos> list = new ArrayList<>();
        int range = 2 * MAX_HANG + 2;
        job.findNext(level.getGameTime(), i -> {
            BlockPos pos = job.pos(i);
            if (pos.getY() >= target.getY() - 1 && pos.getY() <= target.getY() + 2
                    && Math.abs(pos.getX() - target.getX()) <= range && Math.abs(pos.getZ() - target.getZ()) <= range) list.add(pos);
            return false;
        }, COVER_SCAN);
        return list;
    }

    /**
     * How well a column at {@code base} serves {@code blocks}: two for each she reaches from the
     * column itself (no more scaffolding, and still in reach when the column is raised), one for
     * each next to where scaffolding hung from it gets to.
     */
    private static int covered(List<BlockPos> blocks, BlockPos base) {
        double direct = reach() - 1;
        int n = 0;
        for (BlockPos pos : blocks) {
            int dx = pos.getX() - base.getX(), dz = pos.getZ() - base.getZ();
            if (dx * dx + dz * dz <= direct * direct) n += 2;
            else if (Math.abs(dx) + Math.abs(dz) <= MAX_HANG + 1) n++;
        }
        return n;
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

    // ---- 3. moving sideways on top of the scaffolding ----

    /** Scaffolding hangs at most this many blocks sideways from a supported column (vanilla: distance 7 falls). */
    static final int MAX_HANG = 6;
    /** How far (horizontally, per axis) a sideways move may lead. */
    private static final int SHIFT_RADIUS = 8;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Feet positions to walk through, in order, and how many of the cells under them need new scaffolding. */
    record Walk(List<BlockPos> path, int newBlocks, BlockPos target) {
        BlockPos end() {
            return path.get(path.size() - 1);
        }
    }

    /**
     * A way along the top of the scaffolding, at the height she stands at, to a spot that reaches
     * {@code target}: over the job's scaffolding where it exists, otherwise over new scaffolding
     * hung from it (no further than {@link #MAX_HANG} from support). Fewest new blocks first, then
     * the shortest walk; null if none needs at most {@code maxNew} new blocks.
     * <p>She goes right next to the target if she can: the blocks beyond it (usually the next ones
     * along a wall) are then in reach too, instead of one more short move each.
     */
    @Nullable
    static Walk planShift(ServerLevel level, EntityMaid maid, BuildJob job, BlockPos stand, BlockPos target, int maxNew) {
        return planShift(level, maid, job, stand, List.of(target), maxNew);
    }

    /**
     * The same towards whichever of {@code targets} is best to go to ({@link Walk#target} tells which):
     * fewest new blocks first; among walks needing new scaffolding, the one whose end hangs closest to
     * its support (so she first builds what is near the column, to either side of it, before she
     * uses up the hang on one side or round a corner); then the shortest.
     */
    @Nullable
    static Walk planShift(ServerLevel level, EntityMaid maid, BuildJob job, BlockPos stand, List<BlockPos> targets, int maxNew) {
        if (targets.isEmpty()) return null;
        Walk close = planShift(level, maid, job, stand, targets, maxNew, 1);
        return close != null ? close : planShift(level, maid, job, stand, targets, maxNew, SHIFT_RADIUS * 2);
    }

    /** As above, ending within {@code near} blocks (horizontally, per axis) of the target. */
    @Nullable
    private static Walk planShift(ServerLevel level, EntityMaid maid, BuildJob job, BlockPos stand, List<BlockPos> targets, int maxNew, int near) {
        double eye = maid.getEyeHeight();
        double margin = MaidBuilderConfig.WORK_AREA_MARGIN.get();
        record Node(BlockPos pos, int newBlocks, int length, int hang, @Nullable Node from) {
        }
        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(
                Comparator.<Node>comparingInt(Node::newBlocks).thenComparingInt(Node::length));
        java.util.Map<BlockPos, Integer> best = new java.util.HashMap<>();
        open.add(new Node(stand, 0, 0, hang(level, stand.below()), null));
        best.put(stand, 0);
        Node goal = null;
        BlockPos goalTarget = null;
        long goalRank = Long.MAX_VALUE;
        while (!open.isEmpty()) {
            Node node = open.poll();
            // nodes come out by new blocks, then length: once a walk is found, more new blocks never win
            if (goal != null && node.newBlocks() > goal.newBlocks()) break;
            if (node.from() != null) {
                for (BlockPos target : targets) {
                    if (!reaches(node.pos(), eye, target)
                            || Math.abs(node.pos().getX() - target.getX()) > near || Math.abs(node.pos().getZ() - target.getZ()) > near
                            || node.pos().equals(target) || node.pos().above().equals(target) || node.pos().below().equals(target)) continue;
                    long rank = (node.newBlocks() > 0 ? node.hang() * 100_000L : 0) + node.length();
                    if (rank < goalRank) {
                        goal = node;
                        goalTarget = target;
                        goalRank = rank;
                    }
                    break;
                }
                // without new scaffolding the first walk found is the shortest
                if (goal != null && goal.newBlocks() == 0) break;
            }
            for (int[] side : SIDES) {
                BlockPos next = node.pos().offset(side[0], 0, side[1]);
                if (Math.abs(next.getX() - stand.getX()) > SHIFT_RADIUS || Math.abs(next.getZ() - stand.getZ()) > SHIFT_RADIUS) continue;
                if (!level.isLoaded(next) || !job.columnFreeFor(BuildJob.columnKey(next), maid.getUUID())) continue;
                if (job.isStructurePos(next) || job.isStructurePos(next.above())) continue;
                if (level.getBlockState(next).is(Blocks.SCAFFOLDING) || !passable(level, next) || !passable(level, next.above())) continue;
                BlockPos cell = next.below();
                int newBlocks = node.newBlocks(), hang;
                if (job.isScaffold(cell) && level.getBlockState(cell).is(Blocks.SCAFFOLDING)) {
                    hang = hang(level, cell);
                } else if (buildable(level, cell) && !job.isStructurePos(cell) && job.inWorkArea(cell, margin)) {
                    hang = node.hang() + 1;
                    if (hang > MAX_HANG || ++newBlocks > maxNew) continue;
                } else {
                    continue;
                }
                Integer seen = best.get(next);
                int rank = newBlocks * 1000 + node.length() + 1;
                if (seen != null && seen <= rank) continue;
                best.put(next, rank);
                open.add(new Node(next, newBlocks, node.length() + 1, hang, node));
            }
        }
        if (goal == null) return null;
        List<BlockPos> path = new ArrayList<>();
        for (Node n = goal; n.from() != null; n = n.from()) path.add(n.pos());
        java.util.Collections.reverse(path);
        return new Walk(path, goal.newBlocks(), goalTarget);
    }

    /**
     * A way along the top of the job's scaffolding, at the height she stands at, to a column that
     * stands on the ground (so she can slide down it): the feet positions to walk through, ending
     * over or inside that column; empty if she is on one already, null if there is none.
     */
    @Nullable
    static List<BlockPos> planReturn(ServerLevel level, BuildJob job, BlockPos stand) {
        Map<Long, List<BlockPos>> columns = job.scaffoldColumns();
        java.util.Map<BlockPos, BlockPos> from = new java.util.HashMap<>();
        java.util.ArrayDeque<BlockPos> open = new java.util.ArrayDeque<>();
        open.add(stand);
        from.put(stand, stand);
        while (!open.isEmpty()) {
            BlockPos node = open.poll();
            List<BlockPos> column = columns.get(BuildJob.columnKey(node));
            if (column != null && column.get(0).getY() < node.getY() && grounded(level, column.get(0))) {
                List<BlockPos> path = new ArrayList<>();
                for (BlockPos p = node; !p.equals(stand); p = from.get(p)) path.add(p);
                java.util.Collections.reverse(path);
                return path;
            }
            for (int[] side : SIDES) {
                BlockPos next = node.offset(side[0], 0, side[1]);
                if (from.containsKey(next) || Math.abs(next.getX() - stand.getX()) > 2 * SHIFT_RADIUS
                        || Math.abs(next.getZ() - stand.getZ()) > 2 * SHIFT_RADIUS) continue;
                boolean onTop = job.isScaffold(next.below()), inside = job.isScaffold(next);
                if (!onTop && !inside || !passable(level, next) || !passable(level, next.above())) continue;
                from.put(next, node);
                open.add(next);
            }
        }
        return null;
    }

    /** The lowest cell of a column rests on the ground (not on more scaffolding or on nothing). */
    static boolean grounded(Level level, BlockPos lowest) {
        BlockPos below = lowest.below();
        BlockState state = level.getBlockState(below);
        return !state.is(Blocks.SCAFFOLDING) && state.isFaceSturdy(level, below, Direction.UP);
    }

    /** How far a scaffold hangs from its support (0 when it stands on something). */
    private static int hang(Level level, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        return state.is(Blocks.SCAFFOLDING) ? state.getValue(ScaffoldingBlock.DISTANCE) : 0;
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

    /** Higher than a jump from the ground takes her (about 1.25 blocks). */
    private static final double ABOVE_JUMP = 1.3;

    /**
     * The tracked column the maid is standing on or climbing in, if any. Standing in its bottom
     * block on the ground does not count: that is where she enters and leaves it. Nor does a jump
     * from there (e.g. onto a chair next to it on her way out): only standing on a scaffold, or
     * being higher than a jump.
     */
    @Nullable
    static List<BlockPos> columnAt(EntityMaid maid, BuildJob job) {
        if (job.scaffolds().isEmpty()) return null;
        BlockPos feet = maid.blockPosition();
        if (!job.isScaffold(feet) && !job.isScaffold(feet.below())) return null;
        List<BlockPos> column = job.scaffoldColumns().get(BuildJob.columnKey(feet));
        if (column == null) return null;
        double up = maid.getY() - column.get(0).getY();
        return up > ABOVE_JUMP || maid.onGround() && up > 0.5 ? column : null;
    }

    /** Whether the maid's box sits inside the column's x/z cell (she is climbing or standing on it). */
    static boolean overColumn(EntityMaid maid, BlockPos base) {
        AABB box = maid.getBoundingBox();
        double cx = base.getX() + 0.5, cz = base.getZ() + 0.5;
        return Math.abs((box.minX + box.maxX) / 2 - cx) < 0.5 && Math.abs((box.minZ + box.maxZ) / 2 - cz) < 0.5;
    }

    // ---- 4. a long way round on foot ----

    /** Cells the ground search may look at: enough for a detour of some 60 blocks round a building. */
    private static final int WALK_MAX_NODES = 16384;
    private static final int MAX_DROP = 3;

    /**
     * A way on foot from where the maid stands to a spot whose bottom centre is within
     * {@code within} blocks of the centre of {@code goal}: feet positions one step apart (up one
     * block, down at most {@link #MAX_DROP}), through closed wooden doors too. Unlike the game's
     * path finder it is not limited to the follow range, so it finds the long way out of a building
     * whose wall stands between her and the goal. Null if there is none within the search budget.
     */
    @Nullable
    static List<BlockPos> planWalk(ServerLevel level, EntityMaid maid, BlockPos goal, double within) {
        return planWalk(level, maid, goal, within, null);
    }

    /**
     * The same; when there is no way, {@code noWayFrom} (if given) receives the spots the search
     * showed to have none either: everything she can walk to if the search covered all of it,
     * otherwise just where she stands.
     */
    @Nullable
    static List<BlockPos> planWalk(ServerLevel level, EntityMaid maid, BlockPos goal, double within,
                                   @Nullable java.util.Set<BlockPos> noWayFrom) {
        record Node(BlockPos pos, int length, @Nullable Node from) {
        }
        Vec3 goalCentre = Vec3.atCenterOf(goal);
        java.util.PriorityQueue<Node> open = new java.util.PriorityQueue<>(Comparator.comparingInt(
                n -> n.length() + Math.abs(n.pos().getX() - goal.getX()) + Math.abs(n.pos().getZ() - goal.getZ())));
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        BlockPos start = maid.blockPosition();
        open.add(new Node(start, 0, null));
        seen.add(start);
        while (!open.isEmpty() && seen.size() < WALK_MAX_NODES) {
            Node node = open.poll();
            BlockPos at = node.pos();
            if (Vec3.atBottomCenterOf(at).distanceToSqr(goalCentre) <= within * within) {
                List<BlockPos> path = new ArrayList<>();
                for (Node n = node; n.from() != null; n = n.from()) path.add(n.pos());
                java.util.Collections.reverse(path);
                return path;
            }
            boolean headroom = walkable(level, at.above(2));
            for (int[] side : SIDES) {
                BlockPos beside = at.offset(side[0], 0, side[1]);
                if (!level.isLoaded(beside)) continue;
                BlockPos next = null;
                if (headroom && canWalkAt(level, beside.above())) {
                    next = beside.above();
                } else if (walkable(level, beside) && walkable(level, beside.above())) {
                    for (int drop = 0; drop <= MAX_DROP; drop++) {
                        BlockPos feet = beside.below(drop);
                        if (canWalkAt(level, feet)) {
                            next = feet;
                            break;
                        }
                        // a scaffold stops the fall (she would land on top of it, which the search does not use)
                        if (!passable(level, feet.below())) break;
                    }
                }
                if (next != null && seen.add(next)) open.add(new Node(next, node.length() + 1, node));
            }
        }
        if (noWayFrom != null) {
            if (open.isEmpty()) noWayFrom.addAll(seen);
            else noWayFrom.add(start);
        }
        return null;
    }

    /** Like {@link #canStandAt}, but closed wooden doors count as open (maids open them) and she walks under or through scaffolding. */
    private static boolean canWalkAt(Level level, BlockPos feet) {
        return walksOn(level, feet.below()) && walkable(level, feet) && walkable(level, feet.above());
    }

    /**
     * A floor for the ground search: a block whose top is between half and full height, so stairs
     * and slabs (a door step) count. Not scaffolding (the search keeps off the job's columns), nor
     * fences and walls, which a maid cannot step onto.
     */
    private static boolean walksOn(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.SCAFFOLDING)) return false;
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) return false;
        double top = shape.max(Direction.Axis.Y);
        return top >= 0.5 && top <= 1.0;
    }

    /**
     * A cell she can walk through: empty, a wooden door, or scaffolding, which has no collision
     * from below or inside (asked without an entity, the game answers as if standing on top).
     */
    private static boolean walkable(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return passable(level, pos) || net.minecraft.world.level.block.DoorBlock.isWoodenDoor(state) || state.is(Blocks.SCAFFOLDING);
    }
}
