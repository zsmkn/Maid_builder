package com.maidbuilder.core.plan;

import com.maidbuilder.core.math.IntPos;
import com.maidbuilder.core.schematic.BlockStateData;

/**
 * One block to place.
 *
 * @param worldPos        target position in the world
 * @param schematicPos    position relative to the schematic origin (before transform)
 * @param schematicState  state as stored in the schematic (before transform)
 * @param worldState      state after the placement's mirror/rotation, computed by core rules
 * @param phase           build pass
 */
public record BuildStep(IntPos worldPos, IntPos schematicPos, BlockStateData schematicState,
                        BlockStateData worldState, BuildPhase phase) {
}
