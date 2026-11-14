package ru.mashinka.multiflow.core.placement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ru.mashinka.multiflow.core.TestLogging;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Assignment;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Cover;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Line;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Pick;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Reduction;

class HungarianSolverTest {

    private static final Logger LOG = Logger.getLogger(HungarianSolverTest.class.getName());
    private static final int RANDOM_PROBLEMS = 2000;

    @BeforeAll
    static void logging() {
        TestLogging.init();
    }

    // ------------------------------------------------------------------ пример из пособия

    // ответ и все промежуточные числа совпадают с примером 3.1 пособия МЭСИ
    @Test
    void textbookExampleMatchesTheBook() {
        PlacementSolution solution = new HungarianSolver().solve(PlacementExamples.textbook(), "test-book");

        assertEquals(7_892_000L, solution.getTotalProfit(), "1050 + 5600 + 1242 тыс. руб.");
        assertEquals(3, solution.plantOf(0), "вид 1 - предприятие 4");
        assertEquals(0, solution.plantOf(1), "вид 2 - предприятие 1");
        assertEquals(2, solution.plantOf(2), "вид 3 - предприятие 3");
        assertArrayEquals(new int[]{3, 0, 2, 1, 4}, solution.getColumns(), "фиктивные 4 и 5 - предприятия 2 и 5");
        assertEquals(2, solution.getFictitious());

        // матрица после сведения к минимуму - как в книге (в тыс. руб.)
        assertEquals(5_760_000L, solution.getMaxProfit());
        long[][] cost = solution.getCost();
        assertArrayEquals(thousands(5235, 6390, 5865, 4710, 5515), cost[0]);
        assertArrayEquals(thousands(160, 16800, 0, 8960, 12960), cost[1]);
        assertArrayEquals(thousands(4680, 4842, 4518, 5166, 4842), cost[2]);
        assertArrayEquals(new long[5], cost[3]);
        assertArrayEquals(new long[5], cost[4]);

        // две итерации, как в книге: на второй назначение полное
        List<HungarianIteration> iterations = solution.getIterations();
        assertEquals(2, iterations.size());

        Reduction reduction = iterations.get(0).getReduction();
        assertArrayEquals(thousands(4710, 0, 4518, 0, 0), reduction.getRowMin());
        assertArrayEquals(new long[5], reduction.getColumnMin(), "в каждом столбце уже есть ноль фиктивных строк");
        assertArrayEquals(thousands(525, 1680, 1155, 0, 805), reduction.getAfterColumns()[0]);
        assertArrayEquals(thousands(162, 324, 0, 648, 324), reduction.getAfterColumns()[2]);

        Assignment first = iterations.get(0).getAssignment();
        assertFalse(first.isComplete());
        assertEquals(4, first.getMaxMatching(), "строкам 2 и 3 нужен один и тот же ноль (2,3)");
        assertPicks(first, new int[][]{{0, 3}, {1, 2}, {3, 0}, {4, 1}},
                Pick.Rule.ROW, Pick.Rule.ROW, Pick.Rule.CHOICE, Pick.Rule.COLUMN);

        Cover cover = iterations.get(0).getCover();
        assertFalse(cover.isReplaced(), "правило книги сразу дало наименьшее число линий");
        assertEquals(4, cover.getLines().size());
        assertLine(cover.getLines().get(0), true, 3, 5);
        assertLine(cover.getLines().get(1), true, 4, 5);
        assertLine(cover.getLines().get(2), false, 2, 2);
        assertLine(cover.getLines().get(3), true, 0, 1);
        assertEquals(160_000L, cover.getMinimum());
        assertArrayEquals(thousands(0, 16640, 0, 8800, 12800), cover.getAfter()[1]);
        assertArrayEquals(thousands(2, 164, 0, 488, 164), cover.getAfter()[2]);
        assertArrayEquals(thousands(525, 1680, 1315, 0, 805), cover.getAfter()[0], "на пересечении +160");

        HungarianIteration second = iterations.get(1);
        assertNull(second.getReduction());
        assertNull(second.getCover());
        assertTrue(second.isLast());
        assertPicks(second.getAssignment(), new int[][]{{0, 3}, {2, 2}, {1, 0}, {3, 1}, {4, 4}},
                Pick.Rule.ROW, Pick.Rule.ROW, Pick.Rule.ROW, Pick.Rule.CHOICE, Pick.Rule.ROW);

        // следующий по прибыли вариант - 7890, поэтому других оптимальных размещений нет
        assertEquals(0, solution.getAlternativeCount());
        assertTrue(solution.getAlternatives().isEmpty());
        checkSolution(PlacementExamples.textbook(), solution);
    }

    // с числами, как они напечатаны в книге (5 вместо 35), лучший ответ другой - 8137
    @Test
    void printedTextbookNumbersGiveAnotherAnswer() {
        int[][] production = PlacementExamples.production();
        production[0][4] = 5;
        production[1][4] = 5;
        PlacementProblem problem = PlacementProblem.of(production, PlacementExamples.sales(),
                PlacementExamples.volume(), PlacementExamples.price());

        PlacementSolution solution = new HungarianSolver().solve(problem, "test-printed");
        assertEquals(8_137_000L, solution.getTotalProfit(), "1295 + 5600 + 1242 тыс. руб.");
        assertEquals(4, solution.plantOf(0), "вид 1 - предприятие 5");
        assertEquals(0, solution.plantOf(1));
        assertEquals(2, solution.plantOf(2));
        checkSolution(problem, solution);
    }

    // ------------------------------------------------------------------ сверка с перебором

    @Test
    void randomProblemsMatchBruteForce() {
        Random random = new Random(20261114L);
        Level previous = TestLogging.mute();
        int replaced = 0;
        int bySearch = 0;
        int byChoice = 0;
        int maxIterations = 0;
        try {
            for (int k = 0; k < RANDOM_PROBLEMS; k++) {
                int n = 2 + random.nextInt(7);          // 2..8
                int m = 2 + random.nextInt(n - 1);      // 2..n
                // узкий диапазон чисел - много равных клеток, нулей и ничьих
                int spread = k % 3 == 0 ? 3 : k % 3 == 1 ? 12 : 60;
                PlacementProblem problem = randomProblem(random, m, n, spread);

                PlacementSolution solution = new HungarianSolver().solve(problem, "rnd-" + k);
                checkAgainstBruteForce(problem, solution, "problem " + k);
                checkSolution(problem, solution);

                maxIterations = Math.max(maxIterations, solution.getIterations().size());
                for (HungarianIteration iteration : solution.getIterations()) {
                    if (iteration.getCover() != null && iteration.getCover().isReplaced()) {
                        replaced++;
                    }
                    if (iteration.getAssignment().isCompletedBySearch()) {
                        bySearch++;
                    }
                    for (Pick pick : iteration.getAssignment().getPicks()) {
                        if (pick.getRule() == Pick.Rule.CHOICE) {
                            byChoice++;
                        }
                    }
                }
            }
        } finally {
            TestLogging.restore(previous);
        }
        int replacedTotal = replaced;
        int searchTotal = bySearch;
        int choiceTotal = byChoice;
        int iterationsMax = maxIterations;
        LOG.info(() -> RANDOM_PROBLEMS + " random problems match brute force; minimal cover used " + replacedTotal
                + " times, assignment completed by search " + searchTotal + " times, zeros chosen " + choiceTotal
                + " times, max iterations " + iterationsMax);
        // запасные ветки метода должны реально проверяться этим тестом, а не простаивать
        assertTrue(replaced > 0, "в прогоне не встретилось ни одного лишнего вычёркивания");
        assertTrue(bySearch > 0, "в прогоне ни разу не понадобилось достраивать назначение");
        assertTrue(byChoice > 0, "в прогоне ни разу не пришлось выбирать ноль");
    }

    @Test
    void tenByTenMatchesBruteForce() {
        Random random = new Random(7L);
        for (int k = 0; k < 3; k++) {
            PlacementProblem problem = randomProblem(random, 10, 10, k == 0 ? 4 : 100);
            PlacementSolution solution = new HungarianSolver().solve(problem, "ten-" + k);
            checkAgainstBruteForce(problem, solution, "10x10 problem " + k);
            checkSolution(problem, solution);
        }
    }

    // ------------------------------------------------------------------ крайние случаи

    @Test
    void squareProblemHasNoFictitiousRows() {
        PlacementProblem problem = PlacementProblem.of(
                new int[][]{{4, 9, 2, 7}, {3, 5, 8, 1}, {6, 2, 4, 9}, {7, 3, 1, 5}},
                new int[][]{{1, 1, 1, 1}, {2, 2, 2, 2}, {0, 0, 0, 0}, {3, 1, 2, 0}},
                new int[]{1000, 2000, 1500, 500},
                new int[]{20, 15, 18, 25});
        PlacementSolution solution = new HungarianSolver().solve(problem, "test-square");
        assertEquals(0, solution.getFictitious());
        assertEquals(4, solution.getCost().length);
        checkAgainstBruteForce(problem, solution, "square");
        checkSolution(problem, solution);
    }

    // цена 0 - продукция везде убыточна, но разместить её всё равно нужно
    @Test
    void allLossesStillPlaceEveryProduct() {
        PlacementProblem problem = PlacementProblem.of(
                new int[][]{{5, 3, 8}, {2, 9, 4}},
                new int[][]{{1, 2, 1}, {3, 1, 2}},
                new int[]{100, 300},
                new int[]{0, 0});
        PlacementSolution solution = new HungarianSolver().solve(problem, "test-losses");
        assertTrue(solution.getTotalProfit() < 0);
        assertEquals(-100 * 5 - 300 * 5, solution.getTotalProfit(), "вид 1 - предприятие 2, вид 2 - 1: по -5 на единицу");
        assertEquals(1, solution.plantOf(0));
        assertEquals(0, solution.plantOf(1));
        checkAgainstBruteForce(problem, solution, "losses");
        checkSolution(problem, solution);
    }

    // все клетки равны: подходит любое размещение, их 5 x 4 x 3 = 60
    @Test
    void equalProfitsListOtherPlans() {
        int[][] same = {{1, 1, 1, 1, 1}, {1, 1, 1, 1, 1}, {1, 1, 1, 1, 1}};
        PlacementProblem problem = PlacementProblem.of(same, same, new int[]{10, 10, 10}, new int[]{5, 5, 5});
        PlacementSolution solution = new HungarianSolver().solve(problem, "test-equal");
        assertEquals(59, solution.getAlternativeCount());
        assertFalse(solution.isAlternativesTruncated());
        assertEquals(HungarianSolver.MAX_LISTED_ALTERNATIVES, solution.getAlternatives().size());
        checkSolution(problem, solution);
    }

    // 10 видов на 10 предприятиях с равной прибылью: 10! размещений - перебор останавливается по лимиту
    @Test
    void hugeNumberOfTiesIsCappedQuickly() {
        int[][] same = new int[10][10];
        int[] volume = new int[10];
        int[] price = new int[10];
        Arrays.fill(volume, 1);
        PlacementProblem problem = PlacementProblem.of(same, same, volume, price);
        long started = System.nanoTime();
        PlacementSolution solution = new HungarianSolver().solve(problem, "test-ties");
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(solution.isAlternativesTruncated());
        assertEquals(HungarianSolver.MAX_COUNTED_ALTERNATIVES, solution.getAlternativeCount());
        assertTrue(millis < 2000, "перебор ничьих занял " + millis + " мс");
        checkSolution(problem, solution);
    }

    // ------------------------------------------------------------------ проверки

    private static void checkAgainstBruteForce(PlacementProblem problem, PlacementSolution solution, String what) {
        PlacementBruteForce brute = new PlacementBruteForce(problem.profitTable());
        assertEquals(brute.best, solution.getTotalProfit(), what + ": прибыль не совпала с перебором");
        if (!solution.isAlternativesTruncated()) {
            assertEquals(brute.optimal.size() - 1, solution.getAlternativeCount(),
                    what + ": число других оптимальных размещений");
        }
        Set<String> optimal = new HashSet<>();
        for (int[] plan : brute.optimal) {
            optimal.add(Arrays.toString(plan));
        }
        int m = problem.products();
        assertTrue(optimal.contains(Arrays.toString(Arrays.copyOf(solution.getColumns(), m))),
                what + ": найденное размещение не из оптимальных");
        for (int[] plan : solution.getAlternatives()) {
            assertTrue(optimal.contains(Arrays.toString(plan)), what + ": в списке неоптимальное " + Arrays.toString(plan));
        }
    }

    // правила метода на каждой итерации, без сверки с ответом
    private static void checkSolution(PlacementProblem problem, PlacementSolution solution) {
        int m = problem.products();
        int n = problem.plants();

        int[] columns = solution.getColumns();
        assertEquals(n, columns.length);
        boolean[] usedPlant = new boolean[n];
        long total = 0;
        for (int i = 0; i < n; i++) {
            assertFalse(usedPlant[columns[i]], "предприятие " + (columns[i] + 1) + " назначено дважды");
            usedPlant[columns[i]] = true;
            if (i < m) {
                total += problem.profit(i, columns[i]);
            }
        }
        assertEquals(total, solution.getTotalProfit());

        long[][] cost = solution.getCost();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                assertEquals(i < m ? solution.getMaxProfit() - problem.profit(i, j) : 0L, cost[i][j]);
                assertTrue(cost[i][j] >= 0);
            }
        }

        List<HungarianIteration> iterations = solution.getIterations();
        assertFalse(iterations.isEmpty());
        long[][] expected = null;
        for (int k = 0; k < iterations.size(); k++) {
            HungarianIteration iteration = iterations.get(k);
            assertEquals(k + 1, iteration.getIndex());
            if (k == 0) {
                Reduction reduction = iteration.getReduction();
                assertNotNull(reduction);
                expected = reduction.getAfterColumns();
                for (int i = 0; i < n; i++) {
                    assertTrue(hasZero(expected, i, true), "после редукции в строке " + (i + 1) + " нет нуля");
                    assertTrue(hasZero(expected, i, false), "после редукции в столбце " + (i + 1) + " нет нуля");
                }
            } else {
                assertNull(iteration.getReduction());
            }

            Assignment assignment = iteration.getAssignment();
            long[][] matrix = assignment.getMatrix();
            assertTrue(Arrays.deepEquals(expected, matrix), "шаг 2 работает не с той матрицей");
            checkMarks(assignment, matrix);

            boolean last = k == iterations.size() - 1;
            assertEquals(last, assignment.isComplete());
            assertEquals(last, iteration.isLast());
            if (last) {
                int[] assigned = assignment.getColumns();
                assertArrayEquals(columns, assigned);
                for (int i = 0; i < n; i++) {
                    assertEquals(0L, matrix[i][assigned[i]], "назначение не по нулю в строке " + (i + 1));
                }
                assertEquals(assignment.isCompletedBySearch(), assignment.getPicks().size() < n);
                break;
            }

            assertTrue(assignment.getMaxMatching() < n);
            Cover cover = iteration.getCover();
            assertNotNull(cover);
            assertEquals(cover.getRuleLineCount() > assignment.getMaxMatching(), cover.isReplaced());
            assertEquals(assignment.getMaxMatching(), cover.getLines().size(), "линий должно быть наименьшее число");
            boolean[] rows = cover.getRows();
            boolean[] cols = cover.getColumns();
            int counted = 0;
            for (Line line : cover.getLines()) {
                assertTrue(line.isRow() ? rows[line.getIndex()] : cols[line.getIndex()]);
                counted++;
            }
            assertEquals(count(rows) + count(cols), counted);
            long minimum = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (matrix[i][j] == 0) {
                        assertTrue(rows[i] || cols[j], "ноль (" + (i + 1) + "," + (j + 1) + ") не вычеркнут");
                    }
                    if (!rows[i] && !cols[j]) {
                        minimum = Math.min(minimum, matrix[i][j]);
                    }
                }
            }
            assertEquals(minimum, cover.getMinimum());
            assertTrue(minimum > 0);
            long[][] after = cover.getAfter();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    long shift = !rows[i] && !cols[j] ? -minimum : rows[i] && cols[j] ? minimum : 0;
                    assertEquals(matrix[i][j] + shift, after[i][j]);
                    assertTrue(after[i][j] >= 0);
                }
            }
            expected = after;
        }
    }

    private static void checkMarks(Assignment assignment, long[][] matrix) {
        int n = matrix.length;
        int[][] marks = assignment.getMarks();
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (matrix[i][j] != 0) {
                    assertEquals(HungarianIteration.NOT_ZERO, marks[i][j]);
                } else {
                    assertTrue(marks[i][j] == HungarianIteration.ASSIGNED || marks[i][j] == HungarianIteration.CHOSEN
                            || marks[i][j] == HungarianIteration.CROSSED, "ноль без отметки");
                }
                if (marks[i][j] == HungarianIteration.ASSIGNED || marks[i][j] == HungarianIteration.CHOSEN) {
                    assigned++;
                }
            }
        }
        assertEquals(assignment.getPicks().size(), assigned);
        boolean[] rowUsed = new boolean[n];
        boolean[] columnUsed = new boolean[n];
        for (Pick pick : assignment.getPicks()) {
            assertFalse(rowUsed[pick.getRow()]);
            assertFalse(columnUsed[pick.getColumn()]);
            rowUsed[pick.getRow()] = true;
            columnUsed[pick.getColumn()] = true;
            int mark = marks[pick.getRow()][pick.getColumn()];
            assertEquals(pick.getRule() == Pick.Rule.CHOICE ? HungarianIteration.CHOSEN : HungarianIteration.ASSIGNED, mark);
        }
        assertTrue(assignment.getPicks().size() <= assignment.getMaxMatching());
    }

    private static boolean hasZero(long[][] matrix, int line, boolean row) {
        for (int k = 0; k < matrix.length; k++) {
            if ((row ? matrix[line][k] : matrix[k][line]) == 0) {
                return true;
            }
        }
        return false;
    }

    private static int count(boolean[] flags) {
        int count = 0;
        for (boolean flag : flags) {
            if (flag) {
                count++;
            }
        }
        return count;
    }

    private static PlacementProblem randomProblem(Random random, int m, int n, int spread) {
        int[][] production = new int[m][n];
        int[][] sales = new int[m][n];
        int[] volume = new int[m];
        int[] price = new int[m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                production[i][j] = random.nextInt(spread + 1);
                sales[i][j] = random.nextInt(spread + 1);
            }
            volume[i] = spread <= 3 ? 1 + random.nextInt(2) : 1 + random.nextInt(50) * 1000;
            price[i] = random.nextInt(2 * spread + 1);
        }
        return PlacementProblem.of(production, sales, volume, price);
    }

    private static void assertPicks(Assignment assignment, int[][] cells, Pick.Rule... rules) {
        List<Pick> picks = assignment.getPicks();
        assertEquals(cells.length, picks.size());
        for (int k = 0; k < cells.length; k++) {
            assertEquals(cells[k][0], picks.get(k).getRow(), "строка назначения " + (k + 1));
            assertEquals(cells[k][1], picks.get(k).getColumn(), "столбец назначения " + (k + 1));
            assertEquals(rules[k], picks.get(k).getRule(), "правило назначения " + (k + 1));
        }
    }

    private static void assertLine(Line line, boolean row, int index, int zeros) {
        assertEquals(row, line.isRow());
        assertEquals(index, line.getIndex());
        assertEquals(zeros, line.getZeros());
    }

    private static long[] thousands(long... values) {
        long[] result = new long[values.length];
        for (int k = 0; k < values.length; k++) {
            result[k] = values[k] * 1000;
        }
        return result;
    }
}
