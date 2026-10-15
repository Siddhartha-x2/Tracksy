package com.tracksy.workspace;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ScoringEngineTest {
    @Test void noAssignedWorkCannotEarnTaskCredit() { assertEquals(0, ScoringEngine.ratio(3, 0)); }
    @Test void scoresStayBoundedAndFractionalPointsArePreserved() {
        assertEquals(100, ScoringEngine.ratio(12, 5));
        assertEquals(33.33, ScoringEngine.ratio(1, 3));
        assertEquals(0, ScoringEngine.ratio(-1, 10));
    }
    @Test void rubricUsesDeclaredWeightsInsteadOfDividingByAvailableComponents() {
        assertEquals(40, ScoringEngine.weighted(Map.of("tasks", 100.0), ScoringEngine.defaults()));
        assertEquals(90, ScoringEngine.weighted(Map.of("tasks", 100.0, "evidence", 100.0,
                "consistency", 100.0, "peer", 0.0, "documentation", 100.0,"milestones",100.0), ScoringEngine.defaults()));
    }
    @Test void invalidRubricOrNonFiniteScoreFails() {
        assertThrows(IllegalArgumentException.class, () -> ScoringEngine.weighted(Map.of(), Map.of("tasks", 90)));
        assertThrows(IllegalArgumentException.class, () -> ScoringEngine.weighted(Map.of("tasks", Double.NaN), ScoringEngine.defaults()));
    }
}
