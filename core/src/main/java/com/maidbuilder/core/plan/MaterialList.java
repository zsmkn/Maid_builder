package com.maidbuilder.core.plan;

import com.maidbuilder.core.schematic.BlockStateData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/** Item id -> count needed to build a plan. */
public final class MaterialList {
    private final Map<String, Integer> counts;

    private MaterialList(Map<String, Integer> counts) {
        this.counts = Collections.unmodifiableMap(counts);
    }

    /**
     * @param baseItem resolves the base item id of a state, or {@code null} if it has no item
     */
    public static MaterialList of(Iterable<BuildStep> steps, Function<BlockStateData, String> baseItem) {
        Map<String, Integer> counts = new TreeMap<>();
        for (BuildStep step : steps) {
            BlockStateData state = step.schematicState();
            String item = baseItem.apply(state);
            if (item != null) counts.merge(item, MaterialRules.countFor(state), Integer::sum);
            for (String extra : MaterialRules.extraItems(state)) counts.merge(extra, 1, Integer::sum);
        }
        return new MaterialList(counts);
    }

    public static MaterialList of(BuildPlan plan) {
        return of(plan.steps(), MaterialRules::itemFor);
    }

    public Map<String, Integer> counts() {
        return counts;
    }

    public int get(String itemId) {
        return counts.getOrDefault(itemId, 0);
    }

    public int totalItems() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Entries sorted by descending count, then by id. */
    public List<Map.Entry<String, Integer>> sortedByCount() {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        return list;
    }
}
