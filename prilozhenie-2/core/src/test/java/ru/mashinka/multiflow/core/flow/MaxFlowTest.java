package ru.mashinka.multiflow.core.flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ru.mashinka.multiflow.core.TestLogging;

class MaxFlowTest {

    @BeforeAll
    static void logging() {
        TestLogging.init();
    }

    @Test
    void chainIsLimitedByItsWeakestBranch() {
        int[][] capacity = {
                {0, 5, 0},
                {5, 0, 3},
                {0, 3, 0},
        };
        FlowResult flow = MaxFlow.compute(capacity, 0, 2);
        assertEquals(3, flow.getValue());
        assertEquals(1, flow.getPaths().size());
        assertArrayEquals(new int[]{0, 1, 2}, flow.getPaths().get(0).getNodes());
        assertEquals(3, flow.getPaths().get(0).getAmount());
        // разрез проходит по слабой ветви 2-3: вершины 1 и 2 со стороны S
        assertTrue(flow.isOnSourceSide(0));
        assertTrue(flow.isOnSourceSide(1));
        assertFalse(flow.isOnSourceSide(2));
    }

    @Test
    void shortestPathsAreTakenFirst() {
        // прямая ветвь 1-4 и обходы через 2 и через 2-3
        int[][] capacity = {
                {0, 4, 0, 2},
                {4, 0, 1, 3},
                {0, 1, 0, 1},
                {2, 3, 1, 0},
        };
        FlowResult flow = MaxFlow.compute(capacity, 0, 3);
        assertEquals(6, flow.getValue());
        assertArrayEquals(new int[]{0, 3}, flow.getPaths().get(0).getNodes());
        assertEquals(2, flow.getPaths().get(0).getAmount());
        assertArrayEquals(new int[]{0, 1, 3}, flow.getPaths().get(1).getNodes());
        assertEquals(3, flow.getPaths().get(1).getAmount());
    }

    @Test
    void noPathMeansZeroFlow() {
        int[][] capacity = {
                {0, 2, 0},
                {2, 0, 0},
                {0, 0, 0},
        };
        FlowResult flow = MaxFlow.compute(capacity, 0, 2);
        assertEquals(0, flow.getValue());
        assertTrue(flow.getPaths().isEmpty());
    }

    @Test
    void sourceAndSinkMustDiffer() {
        assertThrows(IllegalArgumentException.class, () -> MaxFlow.compute(new int[2][2], 1, 1));
    }
}
