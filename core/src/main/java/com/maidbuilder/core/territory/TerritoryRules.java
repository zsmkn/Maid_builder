package com.maidbuilder.core.territory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Territory rules that do not need the game: spacing, prosperity, upgrade conditions, integrity. */
public final class TerritoryRules {
    /** Fraction of a building's counted blocks that must be correct for it to work. */
    public static final double INTACT_THRESHOLD = 0.8;

    private TerritoryRules() {
    }

    /**
     * Largest radius that keeps two territories whose flags are {@code minFlagDistance} apart
     * (Chebyshev distance, in blocks) from overlapping.
     */
    public static int maxRadius(int minFlagDistance) {
        return (minFlagDistance - 1) / 2;
    }

    /** Horizontal Chebyshev distance between two flags. */
    public static int flagDistance(int x1, int z1, int x2, int z2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(z1 - z2));
    }

    /** Whether a horizontal position lies in the square of {@code radius} around the flag. */
    public static boolean inside(int flagX, int flagZ, int radius, int x, int z) {
        return Math.abs(x - flagX) <= radius && Math.abs(z - flagZ) <= radius;
    }

    /** Whether the horizontal box [minX..maxX] x [minZ..maxZ] (inclusive) lies fully in the square. */
    public static boolean insideBox(int flagX, int flagZ, int radius, int minX, int minZ, int maxX, int maxZ) {
        return inside(flagX, flagZ, radius, minX, minZ) && inside(flagX, flagZ, radius, maxX, maxZ);
    }

    /** Whether two inclusive integer boxes share a block. */
    public static boolean intersects(int[] a, int[] b) {
        return a[0] <= b[3] && b[0] <= a[3] && a[1] <= b[4] && b[1] <= a[4] && a[2] <= b[5] && b[2] <= a[5];
    }

    /** Prosperity = working buildings + resident maids (up to the level's cap). */
    public static int prosperity(List<BuiltBuilding> buildings, int residents, LevelDef current) {
        return buildingPoints(buildings) + residentPoints(residents, current);
    }

    public static int buildingPoints(List<BuiltBuilding> buildings) {
        int sum = 0;
        for (BuiltBuilding b : buildings) if (b.active()) sum += b.prosperity();
        return sum;
    }

    public static int residentPoints(int residents, LevelDef current) {
        return Math.min(residents, current.residentCap()) * current.residentPoints();
    }

    /** One line of the upgrade checklist. */
    public record Condition(Kind kind, String key, int have, int need) {
        public boolean met() {
            return have >= need;
        }
    }

    public enum Kind {
        PROSPERITY, GROUP, CATEGORY
    }

    /** Prosperity and building conditions for upgrading to {@code next} (items are checked by the caller). */
    public static List<Condition> upgradeConditions(LevelDef next, int prosperity, List<BuiltBuilding> buildings) {
        List<Condition> list = new ArrayList<>();
        list.add(new Condition(Kind.PROSPERITY, "", prosperity, next.prosperity()));
        for (BuildingRequirement r : next.requiredBuildings()) {
            if (r.isGroup()) {
                int n = 0;
                for (BuiltBuilding b : buildings) if (b.active() && r.group().equals(b.group())) n++;
                list.add(new Condition(Kind.GROUP, r.group(), n, r.count()));
            } else {
                Set<String> groups = new HashSet<>();
                for (BuiltBuilding b : buildings) if (b.active() && r.category().equals(b.category())) groups.add(b.group());
                list.add(new Condition(Kind.CATEGORY, r.category(), groups.size(), r.count()));
            }
        }
        return list;
    }

    public static boolean allMet(List<Condition> conditions) {
        for (Condition c : conditions) if (!c.met()) return false;
        return true;
    }

    /** A building with nothing to check counts as intact. */
    public static boolean intact(int correct, int counted) {
        return counted == 0 || correct >= Math.ceil(counted * INTACT_THRESHOLD - 1e-9);
    }

    /** Integrity in percent, rounded down. */
    public static int percent(int correct, int counted) {
        return counted == 0 ? 100 : (int) (100L * correct / counted);
    }
}
