package com.maidbuilder.core.plan;

/** Build passes, executed in declaration order. */
public enum BuildPhase {
    /** Blocks that stand on their own. */
    SOLID,
    /** Blocks that need a supporting neighbour: torches, doors, carpets, plants, redstone... */
    ATTACHED,
    /** Falling blocks and fluids, placed last so their support already exists. */
    GRAVITY_AND_FLUIDS
}
