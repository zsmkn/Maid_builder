package com.maidbuilder.core.territory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One territory level.
 *
 * @param level             1-based level number
 * @param radius            half the side of the territory square, in blocks (not counting the flag column)
 * @param prosperity        prosperity needed to upgrade to this level (0 for level 1)
 * @param requiredBuildings buildings needed to upgrade to this level
 * @param upgradeItems      items (registry id to count) handed in to upgrade to this level
 * @param residentPoints    prosperity each resident maid gives while the territory is at this level
 * @param residentCap       most resident maids that count towards prosperity at this level
 * @param bonuses           bonus ids active while the territory is at this level or higher
 */
public record LevelDef(int level, int radius, int prosperity, List<BuildingRequirement> requiredBuildings,
                       Map<String, Integer> upgradeItems, int residentPoints, int residentCap, Set<String> bonuses) {
    public LevelDef {
        requiredBuildings = List.copyOf(requiredBuildings);
        upgradeItems = Map.copyOf(upgradeItems);
        bonuses = Set.copyOf(bonuses);
    }

    public LevelDef withRadius(int newRadius) {
        return new LevelDef(level, newRadius, prosperity, requiredBuildings, upgradeItems, residentPoints, residentCap, bonuses);
    }
}
