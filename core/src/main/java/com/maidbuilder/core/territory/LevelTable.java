package com.maidbuilder.core.territory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * All territory levels, 1..{@link #maxLevel()} without gaps. Radii never shrink with the level and
 * never exceed the limit that keeps territories from overlapping.
 */
public final class LevelTable {
    /** Used when no level definitions are loaded at all. */
    public static final LevelTable FALLBACK = new LevelTable(List.of(
            new LevelDef(1, 48, 0, List.of(), Map.of(), 0, 0, Set.of())));

    private final List<LevelDef> levels;

    private LevelTable(List<LevelDef> levels) {
        this.levels = List.copyOf(levels);
    }

    /**
     * Builds a table from definitions in any order. Levels after the first gap are dropped; radii
     * above {@code maxRadius} are clamped and a radius smaller than the previous level's is raised
     * to it. Every such fix is added to {@code warnings}.
     */
    public static LevelTable of(Collection<LevelDef> defs, int maxRadius, List<String> warnings) {
        TreeMap<Integer, LevelDef> byLevel = new TreeMap<>();
        for (LevelDef def : defs) {
            if (def.level() < 1) {
                warnings.add("ignoring level " + def.level() + " (levels start at 1)");
                continue;
            }
            if (byLevel.put(def.level(), def) != null) warnings.add("level " + def.level() + " is defined twice; the last one wins");
        }
        List<LevelDef> levels = new ArrayList<>();
        int previousRadius = 0;
        for (Map.Entry<Integer, LevelDef> e : byLevel.entrySet()) {
            if (e.getKey() != levels.size() + 1) {
                warnings.add("level " + (levels.size() + 1) + " is missing; ignoring level " + e.getKey() + " and above");
                break;
            }
            LevelDef def = e.getValue();
            int radius = def.radius();
            if (radius > maxRadius) {
                warnings.add("level " + def.level() + " radius " + radius + " exceeds " + maxRadius + " (territories would overlap); clamped");
                radius = maxRadius;
            }
            if (radius < previousRadius) {
                warnings.add("level " + def.level() + " radius " + radius + " is smaller than the previous level's; raised to " + previousRadius);
                radius = previousRadius;
            }
            if (radius < 1) radius = 1;
            levels.add(radius == def.radius() ? def : def.withRadius(radius));
            previousRadius = radius;
        }
        if (levels.isEmpty()) {
            warnings.add("no usable territory levels; using a single default level");
            return FALLBACK;
        }
        return new LevelTable(levels);
    }

    public int maxLevel() {
        return levels.size();
    }

    /** The definition of {@code level}, clamped to the table (a territory above the max keeps the top level). */
    public LevelDef get(int level) {
        return levels.get(Math.max(1, Math.min(level, levels.size())) - 1);
    }

    /** The level after {@code level}, or null at the top. */
    public LevelDef next(int level) {
        return level >= levels.size() ? null : levels.get(level);
    }

    public List<LevelDef> all() {
        return levels;
    }

    /** Whether the bonus is unlocked at {@code level} (granted by that level or any lower one). */
    public boolean hasBonus(int level, String bonus) {
        for (int i = 0; i < Math.min(level, levels.size()); i++) {
            if (levels.get(i).bonuses().contains(bonus)) return true;
        }
        return false;
    }

    /** The lowest level granting the bonus, or -1. */
    public int bonusLevel(String bonus) {
        for (LevelDef def : levels) if (def.bonuses().contains(bonus)) return def.level();
        return -1;
    }
}
