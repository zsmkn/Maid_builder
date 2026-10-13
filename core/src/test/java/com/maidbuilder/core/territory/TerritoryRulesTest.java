package com.maidbuilder.core.territory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerritoryRulesTest {
    private static LevelDef level(int n, int radius, int prosperity, List<BuildingRequirement> buildings, int residentPoints, int cap,
                                  String... bonuses) {
        return new LevelDef(n, radius, prosperity, buildings, Map.of(), residentPoints, cap, Set.of(bonuses));
    }

    @Test
    void spacingKeepsMaxRadiusSquaresApart() {
        int min = 160;
        int r = TerritoryRules.maxRadius(min);
        assertEquals(79, r);
        // two flags exactly min apart: the squares [f-r, f+r] do not share a column
        assertTrue(0 + r < min - r);
        assertEquals(160, TerritoryRules.flagDistance(0, 0, 160, -30));
        assertEquals(159, TerritoryRules.flagDistance(0, 0, -159, 159));
    }

    @Test
    void insideAndIntersect() {
        assertTrue(TerritoryRules.inside(0, 0, 48, 48, -48));
        assertFalse(TerritoryRules.inside(0, 0, 48, 49, 0));
        assertTrue(TerritoryRules.insideBox(10, 10, 5, 5, 5, 15, 15));
        assertFalse(TerritoryRules.insideBox(10, 10, 5, 5, 5, 16, 15));
        assertTrue(TerritoryRules.intersects(new int[]{0, 0, 0, 2, 2, 2}, new int[]{2, 2, 2, 5, 5, 5}));
        assertFalse(TerritoryRules.intersects(new int[]{0, 0, 0, 2, 2, 2}, new int[]{3, 0, 0, 5, 5, 5}));
        assertFalse(TerritoryRules.intersects(new int[]{0, 0, 0, 2, 2, 2}, new int[]{0, 3, 0, 2, 5, 2}));
    }

    @Test
    void prosperityCountsActiveBuildingsAndCappedResidents() {
        LevelDef l2 = level(2, 56, 30, List.of(), 3, 4);
        List<BuiltBuilding> buildings = List.of(
                new BuiltBuilding("warehouse", "storage", 10, true),
                new BuiltBuilding("greenhouse", "production", 15, false),
                new BuiltBuilding("sugar_cane", "production", 5, true));
        assertEquals(15, TerritoryRules.buildingPoints(buildings));
        assertEquals(15 + 6, TerritoryRules.prosperity(buildings, 2, l2));
        assertEquals(15 + 12, TerritoryRules.prosperity(buildings, 9, l2));
    }

    @Test
    void upgradeConditions() {
        LevelDef next = level(3, 64, 80, List.of(BuildingRequirement.group("greenhouse", 1),
                BuildingRequirement.category("production", 2)), 3, 6);
        List<BuiltBuilding> buildings = new ArrayList<>(List.of(
                new BuiltBuilding("greenhouse", "production", 15, false),
                new BuiltBuilding("sugar_cane", "production", 5, true),
                new BuiltBuilding("sugar_cane", "production", 5, true)));
        List<TerritoryRules.Condition> c = TerritoryRules.upgradeConditions(next, 90, buildings);
        assertEquals(3, c.size());
        assertTrue(c.get(0).met());
        assertFalse(c.get(1).met(), "damaged greenhouse does not count");
        assertEquals(1, c.get(2).have(), "two sugar cane farms are one kind");
        assertFalse(TerritoryRules.allMet(c));

        buildings.set(0, new BuiltBuilding("greenhouse", "production", 15, true));
        c = TerritoryRules.upgradeConditions(next, 90, buildings);
        assertTrue(TerritoryRules.allMet(c));
        assertFalse(TerritoryRules.allMet(TerritoryRules.upgradeConditions(next, 79, buildings)));
    }

    @Test
    void integrityThreshold() {
        assertTrue(TerritoryRules.intact(80, 100));
        assertFalse(TerritoryRules.intact(79, 100));
        assertTrue(TerritoryRules.intact(4, 5));
        assertFalse(TerritoryRules.intact(3, 5));
        assertTrue(TerritoryRules.intact(0, 0));
        assertEquals(75, TerritoryRules.percent(3, 4));
    }

    @Test
    void levelTableFixesBadDefinitions() {
        List<String> warnings = new ArrayList<>();
        LevelTable table = LevelTable.of(List.of(
                level(2, 40, 30, List.of(), 3, 4, "maid_regen"),
                level(1, 48, 0, List.of(), 2, 2),
                level(3, 200, 80, List.of(), 3, 6, "crop_growth"),
                level(5, 79, 300, List.of(), 3, 6)), 79, warnings);
        assertEquals(3, table.maxLevel(), "level 5 dropped because 4 is missing");
        assertEquals(48, table.get(2).radius(), "radius never shrinks");
        assertEquals(79, table.get(3).radius(), "radius clamped");
        assertEquals(3, warnings.size());
        assertNull(table.next(3));
        assertEquals(2, table.next(1).level());
        assertEquals(3, table.get(99).level());
        assertTrue(table.hasBonus(3, "maid_regen"));
        assertFalse(table.hasBonus(1, "maid_regen"));
        assertEquals(3, table.bonusLevel("crop_growth"));
        assertEquals(-1, table.bonusLevel("nope"));

        assertEquals(1, LevelTable.of(List.of(), 79, warnings).maxLevel());
    }
}
