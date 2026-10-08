package com.maidbuilder.common.job;

/** What builder maids may break to make the world match a job's schematic. */
public enum ClearMode {
    /** Nothing: wrong blocks are left for the player. */
    OFF,
    /** Wrong blocks where the schematic has a block are broken and replaced. */
    REPLACE,
    /** Like REPLACE, and blocks in the schematic's air near the building (same layer, within the clearing radius) are broken too. */
    ALL
}
