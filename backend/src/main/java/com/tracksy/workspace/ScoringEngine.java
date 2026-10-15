package com.tracksy.workspace;

import java.util.LinkedHashMap;
import java.util.Map;

/** Pure scoring functions. Every component has a declared denominator and bounded range. */
public final class ScoringEngine {
    private ScoringEngine() {}

    public static double ratio(double achieved, double available) {
        if (available <= 0 || !Double.isFinite(available) || !Double.isFinite(achieved)) return 0;
        return round(Math.max(0, Math.min(100, achieved / available * 100)));
    }

    public static double weighted(Map<String, Double> components, Map<String, Integer> weights) {
        if (weights.values().stream().mapToInt(Integer::intValue).sum() != 100)
            throw new IllegalArgumentException("Rubric weights must total 100");
        double result = 0;
        for (var entry : weights.entrySet()) {
            if (entry.getValue() < 0 || entry.getValue() > 100) throw new IllegalArgumentException("Invalid weight");
            double value = components.getOrDefault(entry.getKey(), 0.0);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid component");
            result += Math.max(0, Math.min(100, value)) * entry.getValue() / 100;
        }
        return round(result);
    }

    public static Map<String, Integer> defaults() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("tasks", 40); weights.put("evidence", 20); weights.put("consistency", 15);
        weights.put("peer", 10); weights.put("repository", 0); weights.put("documentation", 10);
        weights.put("milestones", 5);
        return weights;
    }

    public static double round(double value) { return Math.round(value * 100.0) / 100.0; }
}
