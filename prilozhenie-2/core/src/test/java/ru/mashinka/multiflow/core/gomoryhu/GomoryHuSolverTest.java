package ru.mashinka.multiflow.core.gomoryhu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ru.mashinka.multiflow.core.BruteForce;
import ru.mashinka.multiflow.core.TestLogging;
import ru.mashinka.multiflow.core.flow.AugmentingPath;
import ru.mashinka.multiflow.core.network.Examples;
import ru.mashinka.multiflow.core.network.Network;

class GomoryHuSolverTest {

    private static final int RANDOM_NETWORKS = 500;

    @BeforeAll
    static void logging() {
        TestLogging.init();
    }

    @Test
    void exampleTakesSixStepsAndMatchesBruteForce() {
        int[][] matrix = Examples.defaultNetwork();
        MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(matrix), "test-example");

        assertEquals(6, solution.getSteps().size());
        assertEquals(6, solution.getTree().size());
        assertTablesEqual(BruteForce.allPairs(matrix), solution.getTable());
        checkSolution(matrix, solution);
    }

    // итог для встроенного примера; числа получены полным перебором разрезов (BruteForce)
    // и записаны сюда, чтобы любое изменение решателя, меняющее ответ, сразу было видно
    @Test
    void exampleFinalTableIsStable() {
        MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(Examples.defaultNetwork()), "test-stable");
        int[][] expected = {
                {0, 9, 18, 9, 18, 9, 9},
                {9, 0, 9, 18, 9, 18, 18},
                {18, 9, 0, 9, 19, 9, 9},
                {9, 18, 9, 0, 9, 24, 21},
                {18, 9, 19, 9, 0, 9, 9},
                {9, 18, 9, 24, 9, 0, 21},
                {9, 18, 9, 21, 9, 21, 0},
        };
        assertTablesEqual(expected, solution.getTable());
    }

    // первый же разрез делит пример на две группы по слабым ветвям 1-2, 3-4, 5-6
    @Test
    void exampleFirstCutSplitsTheTwoClusters() {
        MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(Examples.defaultNetwork()), "test-clusters");
        Step first = solution.getSteps().get(0);
        assertEquals(9, first.getMaxFlow());
        assertArrayEquals(new int[]{0, 2, 4}, first.getSourceSide());
        assertArrayEquals(new int[]{1, 3, 5, 6}, first.getSinkSide());
        assertEquals(3, first.getCutEdges().size());
    }

    @Test
    void firstStepTakesVerticesOneAndTwoWithoutContraction() {
        MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(Examples.defaultNetwork()), "test-first");
        Step first = solution.getSteps().get(0);
        assertEquals(0, first.getSource());
        assertEquals(1, first.getSink());
        assertEquals(7, first.getNodes().size());
        for (StepNode node : first.getNodes()) {
            assertFalse(node.isContracted());
        }
    }

    @Test
    void laterStepsContractVerticesOutsideTheGroup() {
        MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(Examples.defaultNetwork()), "test-contract");
        boolean contracted = false;
        for (Step step : solution.getSteps()) {
            for (StepNode node : step.getNodes()) {
                contracted |= node.isContracted();
            }
        }
        assertTrue(contracted, "в примере должно быть видно стягивание вершин");
    }

    @Test
    void randomNetworksMatchBruteForce() {
        Random random = new Random(20261028L);
        Level previous = TestLogging.mute();
        try {
            for (int k = 0; k < RANDOM_NETWORKS; k++) {
                int[][] matrix = randomNetwork(random);
                MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(matrix), "random-" + k);
                assertEquals(matrix.length - 1, solution.getSteps().size(), "сеть №" + k);
                assertTablesEqual(BruteForce.allPairs(matrix), solution.getTable());
                checkSolution(matrix, solution);
            }
        } finally {
            TestLogging.restore(previous);
        }
    }

    @Test
    void disconnectedNetworkGivesZeroBetweenParts() {
        int[][] matrix = new int[5][5];
        link(matrix, 0, 1, 4);
        link(matrix, 1, 2, 6);
        link(matrix, 0, 2, 3);
        link(matrix, 3, 4, 5);

        MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(matrix), "test-disconnected");
        assertFalse(solution.isConnected());
        assertEquals(2, solution.getComponents().size());
        for (int u = 0; u < 3; u++) {
            for (int v = 3; v < 5; v++) {
                assertEquals(0, solution.getFlow(u, v));
            }
        }
        assertEquals(7, solution.getFlow(0, 1));
        assertEquals(5, solution.getFlow(3, 4));

        int zeroBranches = 0;
        for (TreeEdge edge : solution.getTree()) {
            if (edge.getValue() == 0) {
                zeroBranches++;
            }
        }
        assertEquals(1, zeroBranches, "две части сети соединяет ровно одна ветвь с потоком 0");
        checkSolution(matrix, solution);
    }

    @Test
    void isolatedVertexHasZeroFlowToEveryone() {
        int[][] matrix = Examples.defaultNetwork();
        for (int v = 0; v < 7; v++) {
            matrix[4][v] = 0;
            matrix[v][4] = 0;
        }
        MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(matrix), "test-isolated");
        for (int v = 0; v < 7; v++) {
            assertEquals(0, solution.getFlow(4, v));
        }
        assertTablesEqual(BruteForce.allPairs(matrix), solution.getTable());
    }

    @Test
    void twoVerticesNeedOneStep() {
        int[][] matrix = {{0, 9}, {9, 0}};
        MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(matrix), "test-two");
        assertEquals(1, solution.getSteps().size());
        assertEquals(9, solution.getFlow(0, 1));
        assertEquals(1, solution.getSteps().get(0).getPaths().size());
    }

    // ------------------------------------------------------------------ проверки решения

    // всё, что по записке пишется на каждом шаге, должно сходиться между собой
    private static void checkSolution(int[][] matrix, MultiFlowSolution solution) {
        int n = matrix.length;
        for (Step step : solution.getSteps()) {
            String where = "шаг " + step.getIndex();

            // строки таблицы шага покрывают все вершины ровно по разу, S - строка 0, T - строка 1
            int[] seen = new int[n];
            for (StepNode node : step.getNodes()) {
                for (int vertex : node.getMembers()) {
                    seen[vertex]++;
                }
            }
            for (int vertex = 0; vertex < n; vertex++) {
                assertEquals(1, seen[vertex], where + ": вершина " + (vertex + 1));
            }
            assertArrayEquals(new int[]{step.getSource()}, step.getNodes().get(0).getMembers(), where);
            assertArrayEquals(new int[]{step.getSink()}, step.getNodes().get(1).getMembers(), where);

            // поток шага - это минимальный разрез между S и T в исходной сети
            assertEquals(BruteForce.minCut(matrix, step.getSource(), step.getSink()), step.getMaxFlow(), where);

            // стороны разреза делят все вершины, S и T по разные стороны
            assertEquals(n, step.getSourceSide().length + step.getSinkSide().length, where);
            assertTrue(contains(step.getSourceSide(), step.getSource()), where);
            assertTrue(contains(step.getSinkSide(), step.getSink()), where);

            int cutSum = 0;
            for (CutEdge edge : step.getCutEdges()) {
                assertTrue(contains(step.getSourceSide(), edge.getU()), where);
                assertTrue(contains(step.getSinkSide(), edge.getV()), where);
                assertEquals(matrix[edge.getU()][edge.getV()], edge.getCapacity(), where);
                cutSum += edge.getCapacity();
            }
            assertEquals(step.getMaxFlow(), cutSum, where + ": сумма ветвей разреза");

            checkExhaustion(step, where);
        }
        checkFinalTree(matrix, solution);
    }

    // пути складываются в поток, каждый проходим по остаточной сети, после них путей нет
    private static void checkExhaustion(Step step, String where) {
        int[][] residual = step.getCapacity();
        int total = 0;
        for (AugmentingPath path : step.getPaths()) {
            int[] nodes = path.getNodes();
            assertEquals(0, nodes[0], where + ": путь начинается в S");
            assertEquals(1, nodes[nodes.length - 1], where + ": путь кончается в T");
            assertTrue(path.getAmount() > 0, where);
            for (int i = 0; i + 1 < nodes.length; i++) {
                assertTrue(residual[nodes[i]][nodes[i + 1]] >= path.getAmount(), where + ": запас ветви");
                residual[nodes[i]][nodes[i + 1]] -= path.getAmount();
                residual[nodes[i + 1]][nodes[i]] += path.getAmount();
            }
            total += path.getAmount();
        }
        assertEquals(step.getMaxFlow(), total, where + ": сумма путей");
        assertFalse(reachable(residual, 0, 1), where + ": после исчерпания путей от S к T не остаётся");
    }

    // каждая ветвь дерева - минимальный разрез между своими концами (свойство дерева Гомори-Ху)
    private static void checkFinalTree(int[][] matrix, MultiFlowSolution solution) {
        int n = matrix.length;
        List<TreeEdge> tree = solution.getTree();
        assertEquals(n - 1, tree.size());
        for (TreeEdge edge : tree) {
            int mask = sideOf(tree, n, edge);
            assertEquals(edge.getValue(), BruteForce.cutValue(matrix, mask), "разрез ветви " + (edge.getU() + 1) + "-" + (edge.getV() + 1));
            assertEquals(edge.getValue(), BruteForce.minCut(matrix, edge.getU(), edge.getV()));
        }
    }

    // вершины, которые остаются на стороне u, если убрать из дерева ветвь edge
    private static int sideOf(List<TreeEdge> tree, int n, TreeEdge removed) {
        int mask = 1 << removed.getU();
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(removed.getU());
        while (!stack.isEmpty()) {
            int v = stack.pop();
            for (TreeEdge edge : tree) {
                if (edge == removed) {
                    continue;
                }
                int other = edge.getU() == v ? edge.getV() : edge.getV() == v ? edge.getU() : -1;
                if (other >= 0 && (mask & (1 << other)) == 0) {
                    mask |= 1 << other;
                    stack.push(other);
                }
            }
        }
        return mask;
    }

    private static boolean reachable(int[][] residual, int from, int to) {
        boolean[] seen = new boolean[residual.length];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        seen[from] = true;
        while (!queue.isEmpty()) {
            int v = queue.poll();
            for (int u = 0; u < residual.length; u++) {
                if (!seen[u] && residual[v][u] > 0) {
                    seen[u] = true;
                    queue.add(u);
                }
            }
        }
        return seen[to];
    }

    // ------------------------------------------------------------------ данные

    private static int[][] randomNetwork(Random random) {
        int n = 2 + random.nextInt(9);
        double density = 0.15 + random.nextDouble() * 0.8;
        int[][] matrix = new int[n][n];
        List<int[]> pairs = new ArrayList<>();
        for (int u = 0; u < n; u++) {
            for (int v = u + 1; v < n; v++) {
                pairs.add(new int[]{u, v});
            }
        }
        boolean any = false;
        for (int[] pair : pairs) {
            if (random.nextDouble() < density) {
                link(matrix, pair[0], pair[1], 1 + random.nextInt(30));
                any = true;
            }
        }
        if (!any) {
            int[] pair = pairs.get(random.nextInt(pairs.size()));
            link(matrix, pair[0], pair[1], 1 + random.nextInt(30));
        }
        return matrix;
    }

    private static void link(int[][] matrix, int u, int v, int capacity) {
        matrix[u][v] = capacity;
        matrix[v][u] = capacity;
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) {
            if (value == wanted) {
                return true;
            }
        }
        return false;
    }

    private static void assertTablesEqual(int[][] expected, int[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], actual[i], "строка " + (i + 1));
        }
    }
}
