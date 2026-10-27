package com.maidbuilder.common.maid;

/**
 * Runtime state one builder maid's behaviours share (created per maid in
 * {@link TaskBuilder#createBrainTasks}); not saved, a reload simply starts over.
 */
final class BuilderSession {
    /** Step that no walkable spot reaches, handed from the target finder to the scaffold task; -1 if none. */
    int scaffoldStep = -1;
    /** Scaffolding the maid should fetch from a material container before trying again. */
    int scaffoldingWanted;
    /** Game time of the last "please give me scaffolding" message to the owner. */
    long lastNotify = Long.MIN_VALUE;
    /** Earliest game time of her next placement. */
    long nextPlaceTime;
    /** Set while the scaffold or teardown task controls the maid. */
    boolean busy;
    /** Block she is breaking (null if none) and the ticks of work put into it so far. */
    @javax.annotation.Nullable
    net.minecraft.core.BlockPos breaking;
    int breakProgress;

    /**
     * Steps this maid skips for a while for reasons of her own (no scaffolding, the column she needs
     * is in use). Kept per maid so her teammates, e.g. one already up a scaffold, still build them.
     */
    private final java.util.Map<Integer, Long> deferred = new java.util.HashMap<>();

    void defer(int step, long until) {
        if (deferred.size() > 256) deferred.values().removeIf(t -> t <= until - 20 * 60 * 10);
        deferred.merge(step, until, Math::max);
    }

    boolean isDeferred(int step, long gameTime) {
        Long until = deferred.get(step);
        return until != null && until > gameTime;
    }

    boolean idle() {
        return !busy && scaffoldStep < 0;
    }
}
