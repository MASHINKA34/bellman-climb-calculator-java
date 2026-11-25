package ru.mashinka.multiflow.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ru.mashinka.multiflow.core.TestLogging;
import ru.mashinka.multiflow.core.transport.TransportProblem.Balanced;

class TransportSolverTest {

    // суммы по всем 27 вариантам контрольной, посчитаны отдельной программой на Python:
    // северо-западный угол; минимальная стоимость (фиктивные клетки в последнюю очередь);
    // минимальная стоимость (фиктивные наравне с остальными); оптимум симплекс-методом (scipy, HiGHS)
    private static final long[][] REFERENCE = {
            {5190, 3280, 3340, 2670}, {4240, 2870, 2990, 2410}, {5140, 2590, 2630, 2240},
            {5120, 2760, 2920, 2420}, {5040, 3210, 3230, 2890}, {4940, 2540, 2570, 2190},
            {4880, 3320, 3330, 2690}, {5280, 3300, 3360, 2780}, {5020, 2860, 2980, 2730},
            {4540, 3220, 3230, 2740}, {5080, 2590, 2890, 2510}, {5850, 2915, 2930, 2770},
            {5210, 3190, 3190, 2940}, {4660, 2500, 2500, 2420}, {4740, 2920, 3020, 2790},
            {3590, 2410, 2710, 2260}, {5090, 2630, 2880, 2480}, {4030, 3060, 3240, 2660},
            {4620, 3000, 3120, 2680}, {5490, 2690, 2910, 2620}, {4530, 2930, 2950, 2770},
            {3670, 3400, 3680, 2900}, {4030, 3060, 3240, 2660}, {4560, 3030, 3150, 2670},
            {5320, 2420, 2530, 2420}, {5180, 2890, 2950, 2890}, {4000, 3120, 3400, 2920},
    };

    private final TransportSolver solver = new TransportSolver();

    @BeforeAll
    static void logging() {
        TestLogging.init();
    }

    @Test
    void allVariantsMatchTheIndependentProgram() {
        assertEquals(REFERENCE.length, TransportExamples.count());
        for (int number = 1; number <= TransportExamples.count(); number++) {
            TransportProblem problem = TransportExamples.variant(number);
            TransportSolution last = solver.solve(problem, true, "v" + number);
            TransportSolution first = solver.solve(problem, false, "v" + number + "-first");
            long[] expected = REFERENCE[number - 1];
            String name = "вариант " + number;
            assertEquals(expected[0], last.getNorthWest().getCost(), name + ", северо-западный угол");
            assertEquals(expected[1], last.getMinimumCost().getCost(), name + ", фиктивные в конце");
            assertEquals(expected[2], first.getMinimumCost().getCost(), name + ", фиктивные по тарифу");
            assertEquals(expected[3], last.getOptimal().getCost(), name + ", оптимум");
            assertEquals(expected[3], first.getOptimal().getCost(), name + ", оптимум не зависит от начала");
            assertEquals(expected[3], TransportReference.shortestPaths(problem), name + ", потоки минимальной стоимости");
            assertPlan(last, last.getNorthWest(), name);
            assertPlan(last, last.getMinimumCost(), name);
            assertPlan(first, first.getMinimumCost(), name);
            assertPlan(last, last.getOptimal(), name);
        }
    }

    // вариант 1, северо-западный угол: каждый шаг так, как его пишут на бумаге
    @Test
    void northWestOfTheFirstVariantStepByStep() {
        TransportPlan plan = solver.solve(TransportExamples.variant(1), true, "nw").getNorthWest();
        long[][] expected = {
                {50, 50, 0, 0, 0},
                {0, 110, 20, 0, 0},
                {0, 0, 110, 10, 20},
                {0, 0, 0, 0, 180},
                {0, 0, 0, 0, 10},
        };
        assertArrayEquals(expected, plan.getAmount());
        assertEquals(50 * 13 + 50 * 5 + 110 * 15 + 20 * 5 + 110 * 4 + 10 * 2 + 20 * 5 + 180 * 11, plan.getCost());
        assertEquals(9, plan.getSteps().size(), "5 + 5 - 1 шагов");

        TransportStep first = plan.getSteps().get(0);
        assertEquals(0, first.getRow());
        assertEquals(0, first.getColumn());
        assertEquals(50, first.getAmount());
        assertEquals(TransportStep.Crossed.COLUMN, first.getCrossed(), "потребность первого потребителя закрыта");
        assertArrayEquals(new long[]{50, 130, 140, 180, 10}, first.getSupplyAfter());
        assertArrayEquals(new long[]{0, 160, 130, 10, 210}, first.getDemandAfter());
        assertArrayEquals(new boolean[]{true, false, false, false, false}, first.getColumnsCrossed());

        TransportStep second = plan.getSteps().get(1);
        assertEquals(1, second.getColumn());
        assertEquals(TransportStep.Crossed.ROW, second.getCrossed(), "запас первого поставщика кончился");
        assertEquals(TransportStep.Crossed.BOTH, plan.getSteps().get(8).getCrossed());
    }

    // вариант 22 (запасов больше на 70): фиктивный столбец в последнюю очередь и наравне с остальными
    @Test
    void fictitiousColumnIsTakenLastOrByTariff() {
        TransportProblem problem = TransportExamples.variant(22);
        assertEquals(TransportProblem.Type.EXCESS, problem.type());
        Balanced table = problem.balance();
        assertEquals(6, table.columns());
        assertEquals(5, table.getFictitiousColumn());
        assertEquals(-1, table.getFictitiousRow());
        assertEquals(70, table.getDemand()[5]);

        List<TransportStep> last = solver.solve(problem, true, "last").getMinimumCost().getSteps();
        boolean fictitiousSeen = false;
        for (TransportStep step : last) {
            boolean fictitious = step.getColumn() == 5;
            assertTrue(fictitious || !fictitiousSeen, "после фиктивной клетки настоящих быть не должно");
            fictitiousSeen |= fictitious;
        }
        TransportStep first = solver.solve(problem, false, "first").getMinimumCost().getSteps().get(0);
        assertEquals(5, first.getColumn(), "тариф 0 - самый дешёвый, клетка берётся первой");
        assertEquals(70, first.getAmount());
        assertEquals(4, first.getCandidates().length, "четыре нуля на выбор");
    }

    @Test
    void shortageAddsFictitiousSupplier() {
        TransportProblem problem = TransportExamples.variant(11);
        assertEquals(TransportProblem.Type.SHORTAGE, problem.type());
        Balanced table = problem.balance();
        assertEquals(5, table.rows());
        assertEquals(4, table.getFictitiousRow());
        assertEquals(50, table.getSupply()[4]);
        assertArrayEquals(new int[]{0, 0, 0, 0, 0}, table.getCost()[4]);
    }

    @Test
    void closedModelStaysAsItIs() {
        TransportProblem problem = TransportProblem.of(new int[][]{{3, 1}, {2, 5}}, new int[]{30, 20},
                new int[]{25, 25});
        assertEquals(TransportProblem.Type.CLOSED, problem.type());
        assertEquals(0, problem.fictitiousAmount());
        Balanced table = problem.balance();
        assertEquals(2, table.rows());
        assertEquals(2, table.columns());
        assertFalse(table.isFictitious(1, 1));
        TransportSolution solution = solver.solve(problem, true, "closed");
        assertEquals(25 * 3 + 5 + 20 * 5, solution.getNorthWest().getCost());
        assertEquals(25 + 5 * 3 + 20 * 2, solution.getMinimumCost().getCost());
        assertEquals(80, solution.getOptimal().getCost());
    }

    // из равных тарифов - клетка, куда помещается больше; при равенстве - верхняя, затем левая
    @Test
    void tieGoesToTheLargestShipmentThenTopLeft() {
        int[][] equal = {{4, 4}, {4, 4}};
        TransportPlan plan = solver.solve(TransportProblem.of(equal, new int[]{10, 50}, new int[]{20, 40}), true,
                "tie").getMinimumCost();
        TransportStep first = plan.getSteps().get(0);
        assertEquals(1, first.getRow());
        assertEquals(1, first.getColumn());
        assertEquals(40, first.getAmount());
        assertEquals(4, first.getCandidates().length);
        assertArrayEquals(new int[]{1, 1}, first.getCandidates()[0], "выбранная клетка - первая в списке");

        TransportStep same = solver.solve(TransportProblem.of(equal, new int[]{30, 30}, new int[]{30, 30}), true,
                "tie-top").getMinimumCost().getSteps().get(0);
        assertEquals(0, same.getRow());
        assertEquals(0, same.getColumn());
    }

    // запас и потребность кончаются вместе: вычёркивается строка, в столбец потом пишется базисный ноль
    @Test
    void degeneratePlanGetsBasicZero() {
        TransportProblem problem = TransportProblem.of(new int[][]{{1, 9, 9}, {9, 1, 9}, {9, 9, 1}},
                new int[]{10, 20, 30}, new int[]{10, 20, 30});
        TransportSolution solution = solver.solve(problem, true, "degenerate");
        for (TransportPlan plan : List.of(solution.getNorthWest(), solution.getMinimumCost())) {
            assertEquals(60, plan.getCost());
            assertEquals(5, plan.filledCount(), "3 + 3 - 1 заполненных клеток");
            assertEquals(2, plan.zeroCount());
        }
        TransportStep first = solution.getNorthWest().getSteps().get(0);
        assertTrue(first.isDegenerate());
        assertEquals(TransportStep.Crossed.ROW, first.getCrossed());
        TransportStep second = solution.getNorthWest().getSteps().get(1);
        assertEquals(1, second.getRow());
        assertEquals(0, second.getColumn());
        assertEquals(0, second.getAmount());
        assertTrue(solution.getNorthWest().getFilled()[1][0]);
        assertTrue(solution.isOptimalUnique());
    }

    // другие стоимости при ничьих: для варианта 1 перебор на Python нашёл 2830, 3280 и 3330
    @Test
    void otherCostsCoverEveryTieChoice() {
        TransportSolution solution = solver.solve(TransportExamples.variant(1), true, "ties");
        assertEquals(3280, solution.getMinimumCost().getCost());
        assertEquals(List.of(2830L, 3330L), solution.getOtherCosts());
        assertFalse(solution.isOtherCostsTruncated());
        assertEquals(List.of(), solver.solve(TransportExamples.variant(4), true, "no-ties").getOtherCosts());
        assertEquals(List.of(2260L, 2280L, 2460L),
                solver.solve(TransportExamples.variant(16), true, "ties-16").getOtherCosts());
    }

    // одинаковые тарифы на большой таблице: перебор обрывается и говорит об этом, а не висит
    @Test
    void otherCostsStopAtTheLimit() {
        Random random = new Random(7);
        int[][] cost = new int[10][10];
        int[] supply = new int[10];
        int[] demand = new int[10];
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                cost[i][j] = 1 + random.nextInt(2);
            }
            supply[i] = 1 + random.nextInt(1_000_000);
            demand[i] = 1 + random.nextInt(1_000_000);
        }
        long startedAt = System.nanoTime();
        TransportSolution solution = solver.solve(TransportProblem.of(cost, supply, demand), true, "big-ties");
        assertTrue(solution.isOtherCostsTruncated());
        assertTrue(System.nanoTime() - startedAt < 5_000_000_000L, "не дольше пяти секунд");
    }

    // случайные таблицы: опорный план, правила методов и оптимум сверяются с независимым расчётом
    @Test
    void randomProblemsMatchTheReference() {
        Level previous = TestLogging.mute();
        try {
            Random random = new Random(20261125);
            for (int round = 0; round < 1000; round++) {
                int m = 2 + random.nextInt(9);
                int n = 2 + random.nextInt(9);
                int maxAmount = round % 3 == 0 ? 4 : 1 + random.nextInt(200);
                TransportProblem problem = random(random, m, n, 1 + random.nextInt(20), maxAmount, round % 4 == 0);
                boolean last = random.nextBoolean();
                TransportSolution solution = solver.solve(problem, last, "r" + round);
                String name = "таблица " + round;
                assertPlan(solution, solution.getNorthWest(), name);
                assertPlan(solution, solution.getMinimumCost(), name);
                assertPlan(solution, solution.getOptimal(), name);
                assertArrayEquals(TransportReference.northWest(problem), solution.getNorthWest().getAmount(), name);
                assertCheapestEachTime(solution, name);
                long best = TransportReference.shortestPaths(problem);
                assertEquals(best, solution.getOptimal().getCost(), name);
                assertTrue(solution.getMinimumCost().getCost() >= best, name);
                assertTrue(solution.getNorthWest().getCost() >= best, name);
            }
        } finally {
            TestLogging.restore(previous);
        }
    }

    @Test
    void smallProblemsMatchTheFullSearch() {
        Level previous = TestLogging.mute();
        try {
            Random random = new Random(42);
            for (int round = 0; round < 300; round++) {
                TransportProblem problem = random(random, 2 + random.nextInt(2), 2 + random.nextInt(2),
                        1 + random.nextInt(9), 5, round % 3 == 0);
                assertEquals(TransportReference.bruteForce(problem),
                        solver.solve(problem, round % 2 == 0, "s" + round).getOptimal().getCost(), "таблица " + round);
            }
        } finally {
            TestLogging.restore(previous);
        }
    }

    // единственность оптимума: перебор всех планов на маленьких таблицах
    @Test
    void uniquenessOfTheOptimumMatchesTheFullSearch() {
        Level previous = TestLogging.mute();
        try {
            Random random = new Random(99);
            int unique = 0;
            for (int round = 0; round < 400; round++) {
                TransportProblem problem = random(random, 2, 2 + random.nextInt(2), 1 + random.nextInt(4), 4, true);
                TransportSolution solution = solver.solve(problem, true, "u" + round);
                boolean expected = countPlans(problem, solution.getOptimal().getCost()) == 1;
                assertEquals(expected, solution.isOptimalUnique(), "таблица " + round);
                unique += expected ? 1 : 0;
            }
            assertTrue(unique > 20 && unique < 380, "в выборке есть оба случая: единственных " + unique);
        } finally {
            TestLogging.restore(previous);
        }
    }

    @Test
    void largestNumbersDoNotOverflow() {
        int[][] cost = new int[10][10];
        int[] amount = new int[10];
        for (int i = 0; i < 10; i++) {
            java.util.Arrays.fill(cost[i], TransportProblem.MAX_COST);
            amount[i] = TransportProblem.MAX_AMOUNT;
        }
        TransportSolution solution = solver.solve(TransportProblem.of(cost, amount, amount), true, "max");
        assertEquals(10_000_000_000_000L, solution.getNorthWest().getCost());
        assertEquals(10_000_000_000_000L, solution.getOptimal().getCost());
    }

    // ------------------------------------------------------------------ проверки

    // план допустимый и опорный: строки и столбцы сходятся, заполнено m + n - 1 клеток без циклов
    private static void assertPlan(TransportSolution solution, TransportPlan plan, String name) {
        Balanced table = solution.getBalanced();
        long[][] amount = plan.getAmount();
        boolean[][] filled = plan.getFilled();
        long[] rows = new long[table.rows()];
        long[] columns = new long[table.columns()];
        int count = 0;
        int[] group = new int[table.rows() + table.columns()];
        for (int k = 0; k < group.length; k++) {
            group[k] = k;
        }
        for (int i = 0; i < table.rows(); i++) {
            for (int j = 0; j < table.columns(); j++) {
                assertTrue(amount[i][j] >= 0, name);
                assertTrue(filled[i][j] || amount[i][j] == 0, name + ": груз в незаполненной клетке");
                rows[i] += amount[i][j];
                columns[j] += amount[i][j];
                if (filled[i][j]) {
                    count++;
                    int a = root(group, i);
                    int b = root(group, table.rows() + j);
                    assertTrue(a != b, name + ": заполненные клетки образуют цикл");
                    group[a] = b;
                }
            }
        }
        assertArrayEquals(table.getSupply(), rows, name);
        assertArrayEquals(table.getDemand(), columns, name);
        assertEquals(table.rows() + table.columns() - 1, count, name);
        assertEquals(TransportReference.total(solution.getProblem(), amount), plan.getCost(), name);
    }

    private static int root(int[] group, int node) {
        while (group[node] != node) {
            node = group[node];
        }
        return node;
    }

    // каждый шаг берёт наименьший тариф из невычеркнутых, а из равных - наибольшую перевозку
    private static void assertCheapestEachTime(TransportSolution solution, String name) {
        Balanced table = solution.getBalanced();
        boolean[] rows = new boolean[table.rows()];
        boolean[] columns = new boolean[table.columns()];
        long[] supply = table.getSupply();
        long[] demand = table.getDemand();
        Set<String> used = new HashSet<>();
        for (TransportStep step : solution.getMinimumCost().getSteps()) {
            assertTrue(used.add(step.getRow() + "," + step.getColumn()), name);
            assertFalse(rows[step.getRow()] || columns[step.getColumn()], name + ": клетка уже вычеркнута");
            boolean stepFictitious = table.isFictitious(step.getRow(), step.getColumn());
            for (int i = 0; i < table.rows(); i++) {
                for (int j = 0; j < table.columns(); j++) {
                    if (rows[i] || columns[j]) {
                        continue;
                    }
                    boolean fictitious = table.isFictitious(i, j);
                    if (solution.isFictitiousLast() && fictitious != stepFictitious) {
                        assertTrue(fictitious, name + ": фиктивная клетка взята раньше настоящей");
                        continue;
                    }
                    assertTrue(table.cost(i, j) >= step.getCost(), name);
                    if (table.cost(i, j) == step.getCost()) {
                        assertTrue(Math.min(supply[i], demand[j]) <= step.getAmount(), name);
                    }
                }
            }
            supply = step.getSupplyAfter();
            demand = step.getDemandAfter();
            rows = step.getRowsCrossed();
            columns = step.getColumnsCrossed();
        }
    }

    private static long countPlans(TransportProblem problem, long cost) {
        return count(problem, TransportReference.supply(problem), TransportReference.demand(problem), 0, 0, cost);
    }

    private static long count(TransportProblem problem, long[] a, long[] b, int i, int j, long left) {
        if (i == a.length) {
            for (long value : b) {
                if (value != 0) {
                    return 0;
                }
            }
            return left == 0 ? 1 : 0;
        }
        if (j == b.length) {
            return a[i] == 0 ? count(problem, a, b, i + 1, 0, left) : 0;
        }
        long plans = 0;
        long limit = Math.min(a[i], b[j]);
        for (long x = 0; x <= limit; x++) {
            a[i] -= x;
            b[j] -= x;
            plans += count(problem, a, b, i, j + 1, left - x * TransportReference.cost(problem, i, j));
            a[i] += x;
            b[j] += x;
        }
        return plans;
    }

    private static TransportProblem random(Random random, int m, int n, int maxCost, int maxAmount, boolean balanced) {
        int[][] cost = new int[m][n];
        int[] supply = new int[m];
        int[] demand = new int[n];
        for (int i = 0; i < m; i++) {
            supply[i] = 1 + random.nextInt(maxAmount);
            for (int j = 0; j < n; j++) {
                cost[i][j] = random.nextInt(maxCost + 1);
            }
        }
        for (int j = 0; j < n; j++) {
            demand[j] = 1 + random.nextInt(maxAmount);
        }
        if (balanced) {
            int difference = java.util.Arrays.stream(supply).sum() - java.util.Arrays.stream(demand).sum();
            if (difference > 0) {
                demand[n - 1] += difference;
            } else {
                supply[m - 1] -= difference;
            }
        }
        return TransportProblem.of(cost, supply, demand);
    }
}
