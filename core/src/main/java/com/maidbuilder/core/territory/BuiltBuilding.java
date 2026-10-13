package com.maidbuilder.core.territory;

/**
 * What the rules need to know about a building registered to a territory.
 *
 * @param active intact enough to count (see {@link TerritoryRules#intact})
 */
public record BuiltBuilding(String group, String category, int prosperity, boolean active) {
}
