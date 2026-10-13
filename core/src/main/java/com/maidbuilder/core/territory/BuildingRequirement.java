package com.maidbuilder.core.territory;

/**
 * A building condition of a level upgrade. Exactly one of {@code group} and {@code category} is set:
 * <ul>
 *   <li>{@code group}: at least {@code count} working buildings of that group
 *       (e.g. "greenhouse" covers the small and the large greenhouse);</li>
 *   <li>{@code category}: working buildings of at least {@code count} different groups of that
 *       category (e.g. three kinds of production building).</li>
 * </ul>
 */
public record BuildingRequirement(String group, String category, int count) {
    public BuildingRequirement {
        if ((group == null) == (category == null)) throw new IllegalArgumentException("set exactly one of group and category");
        if (count < 1) throw new IllegalArgumentException("count must be positive");
    }

    public static BuildingRequirement group(String group, int count) {
        return new BuildingRequirement(group, null, count);
    }

    public static BuildingRequirement category(String category, int distinctGroups) {
        return new BuildingRequirement(null, category, distinctGroups);
    }

    public boolean isGroup() {
        return group != null;
    }
}
