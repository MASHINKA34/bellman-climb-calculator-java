package ru.mashinka.multiflow.core.placement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import ru.mashinka.multiflow.core.log.CoreLog;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Assignment;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Cover;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Line;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Pick;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Reduction;

// размещение производства венгерским методом - по учебному пособию МЭСИ
// «Логистические модели», п. 3.2 (стандартная задача о назначениях)
//
// Задача на максимум прибыли сводится к минимуму: каждая прибыль умножается на -1
// и к ней прибавляется наибольшая прибыль M, то есть клетка = M - прибыль.
// Видов меньше, чем предприятий, - матрица дополняется до квадратной строками
// фиктивных видов с нулевой прибылью (в матрице - нули).
//
// Дальше итерации:
//   шаг 1 (только первый раз) - из каждой строки вычесть её минимум, затем из каждого столбца - его;
//   шаг 2 - назначения: а) строки с единственным невычеркнутым нулём, б) такие же столбцы,
//           повторять а) и б); если в каждой строке осталось по два нуля и больше - ноль выбирается;
//   шаг 3 - если назначение неполное: вычеркнуть нули линиями (строка или столбец с наибольшим
//           числом нулей), из невычеркнутых вычесть их минимум, на пересечениях линий прибавить,
//           и снова шаг 2.
//
// Два места, где правила пособия могут подвести, решены так:
//   - правила шага 2 иногда не находят полное назначение, хотя оно есть (неудачный выбор нуля):
//     тогда оно находится перебором нулей - «можно попытаться найти полное назначение»;
//   - правило «линия с наибольшим числом нулей» иногда проводит лишние линии, и метод может
//     ходить по кругу: тогда берётся наименьшее число линий (по теореме Кёнига оно равно
//     наибольшему числу назначений).
public class HungarianSolver {

    private static final Logger LOG = Logger.getLogger(HungarianSolver.class.getName());

    // защита от зацикливания; на матрицах до 10 x 10 итераций единицы
    static final int MAX_ITERATIONS = 500;
    // другие размещения с той же прибылью: сколько показать и до скольких считать
    static final int MAX_LISTED_ALTERNATIVES = 20;
    static final int MAX_COUNTED_ALTERNATIVES = 10_000;
    static final int MAX_SEARCH_NODES = 2_000_000;

    // нуль, который ещё не назначен и не вычеркнут; только внутри шага 2
    private static final int FREE = -1;

    //#главный - точка входа, отсюда начинать показ
    public PlacementSolution solve(PlacementProblem problem, String runId) {
        long startedAt = System.nanoTime();
        int m = problem.products();
        int n = problem.plants();
        long[][] profit = problem.profitTable();

        long maxProfit = Long.MIN_VALUE;
        for (long[] row : profit) {
            for (long value : row) {
                maxProfit = Math.max(maxProfit, value);
            }
        }
        long best = maxProfit;
        LOG.info(() -> String.format(Locale.ROOT,
                "run=%s placement start: %d products, %d plants, %d fictitious rows, max profit %d rub",
                runId, m, n, n - m, best));
        if (LOG.isLoggable(Level.FINE)) {
            for (int i = 0; i < m; i++) {
                int row = i;
                LOG.fine(() -> "run=" + runId + " product " + (row + 1) + ": volume " + problem.volume(row)
                        + ", price " + problem.price(row) + ", unit profit "
                        + Arrays.toString(problem.unitProfitTable()[row]) + ", profit " + Arrays.toString(profit[row]));
            }
        }

        //#сведение-к-минимуму - клетка = M - прибыль, фиктивные строки остаются нулевыми
        long[][] cost = new long[n][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                cost[i][j] = maxProfit - profit[i][j];
            }
        }
        logMatrix(runId, "cost matrix", cost);

        List<HungarianIteration> iterations = new ArrayList<>();
        Reduction reduction = reduce(cost, runId);
        long[][] matrix = reduction.getAfterColumns();
        int[] columns;
        for (int index = 1; ; index++) {
            if (index > MAX_ITERATIONS) {
                // сюда метод попасть не должен: каждая модификация уменьшает сумму матрицы
                throw new IllegalStateException("Венгерский метод не сошёлся за " + MAX_ITERATIONS
                        + " итераций (run=" + runId + ")");
            }
            Assignment assignment = assign(matrix, runId, index);
            if (assignment.isComplete()) {
                iterations.add(new HungarianIteration(index, index == 1 ? reduction : null, assignment, null));
                columns = assignment.getColumns();
                break;
            }
            Cover cover = cover(matrix, assignment, runId, index);
            iterations.add(new HungarianIteration(index, index == 1 ? reduction : null, assignment, cover));
            matrix = cover.getAfter();
        }

        long total = 0;
        for (int i = 0; i < m; i++) {
            total += profit[i][columns[i]];
        }

        Search search = new Search(profit, matrix, total, columns, m);
        search.run();

        long elapsed = System.nanoTime() - startedAt;
        long totalProfit = total;
        int[] plan = columns;
        LOG.info(() -> String.format(Locale.ROOT,
                "run=%s placement done: plan %s, total profit %d rub, %d iterations, %d other optimal plans%s, %s",
                runId, describePlan(plan, m), totalProfit, iterations.size(), search.count,
                search.truncated ? " (search stopped at the limit)" : "", CoreLog.millis(elapsed)));
        return new PlacementSolution(problem, maxProfit, cost, iterations, columns, total, search.listed,
                search.count, search.truncated, elapsed);
    }

    // ------------------------------------------------------------------ шаг 1

    private Reduction reduce(long[][] cost, String runId) {
        int n = cost.length;
        long[] rowMin = new long[n];
        long[][] afterRows = new long[n][n];
        for (int i = 0; i < n; i++) {
            long min = Long.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                min = Math.min(min, cost[i][j]);
            }
            rowMin[i] = min;
            for (int j = 0; j < n; j++) {
                afterRows[i][j] = cost[i][j] - min;
            }
        }

        long[] columnMin = new long[n];
        long[][] afterColumns = new long[n][n];
        for (int j = 0; j < n; j++) {
            long min = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                min = Math.min(min, afterRows[i][j]);
            }
            columnMin[j] = min;
            for (int i = 0; i < n; i++) {
                afterColumns[i][j] = afterRows[i][j] - min;
            }
        }

        LOG.info(() -> "run=" + runId + " iteration 1 step 1: row minima " + Arrays.toString(rowMin)
                + ", column minima " + Arrays.toString(columnMin));
        logMatrix(runId, "reduced matrix", afterColumns);
        return new Reduction(rowMin, afterRows, columnMin, afterColumns);
    }

    // ------------------------------------------------------------------ шаг 2

    private Assignment assign(long[][] matrix, String runId, int index) {
        int n = matrix.length;
        int[][] marks = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                marks[i][j] = matrix[i][j] == 0 ? FREE : HungarianIteration.NOT_ZERO;
            }
        }
        boolean[] rowDone = new boolean[n];
        boolean[] columnDone = new boolean[n];
        List<Pick> picks = new ArrayList<>();

        while (true) {
            boolean changed = true;
            while (changed) {
                changed = false;
                // а) строки с единственным невычеркнутым нулём, по возрастанию номеров
                for (int r = 0; r < n; r++) {
                    int only = rowDone[r] ? -1 : singleFree(marks, r, true);
                    if (only >= 0) {
                        pick(marks, rowDone, columnDone, r, only, HungarianIteration.ASSIGNED);
                        picks.add(new Pick(r, only, Pick.Rule.ROW));
                        changed = true;
                    }
                }
                // б) столбцы с единственным невычеркнутым нулём
                for (int c = 0; c < n; c++) {
                    int only = columnDone[c] ? -1 : singleFree(marks, c, false);
                    if (only >= 0) {
                        pick(marks, rowDone, columnDone, only, c, HungarianIteration.ASSIGNED);
                        picks.add(new Pick(only, c, Pick.Rule.COLUMN));
                        changed = true;
                    }
                }
            }

            //#выбор-нуля - в каждой строке, где остались нули, их по два и больше:
            // берём строку с наименьшим числом нулей (из равных - верхнюю) и её левый ноль
            int chosenRow = -1;
            int fewest = Integer.MAX_VALUE;
            for (int r = 0; r < n; r++) {
                int free = rowDone[r] ? 0 : countFree(marks, r);
                if (free > 0 && free < fewest) {
                    fewest = free;
                    chosenRow = r;
                }
            }
            if (chosenRow < 0) {
                break;
            }
            int chosenColumn = firstFree(marks, chosenRow);
            int row = chosenRow;
            int zeros = fewest;
            LOG.fine(() -> "run=" + runId + " iteration " + index + " step 2: every open row has 2+ zeros, choosing ("
                    + (row + 1) + "," + (chosenColumn + 1) + ") from " + zeros + " zeros");
            pick(marks, rowDone, columnDone, chosenRow, chosenColumn, HungarianIteration.CHOSEN);
            picks.add(new Pick(chosenRow, chosenColumn, Pick.Rule.CHOICE));
        }
        for (int[] row : marks) {
            for (int mark : row) {
                if (mark == FREE) {
                    // назначенная строка вычёркивает свои нули, поэтому свободный ноль остаться не может
                    throw new IllegalStateException("Шаг 2 закончился с невычеркнутым нулём (run=" + runId + ")");
                }
            }
        }

        int[] matching = maxMatching(matrix);
        int maxMatching = 0;
        for (int column : matching) {
            if (column >= 0) {
                maxMatching++;
            }
        }

        int[] columns = null;
        boolean bySearch = false;
        if (picks.size() == n) {
            columns = new int[n];
            for (Pick p : picks) {
                columns[p.getRow()] = p.getColumn();
            }
        } else if (maxMatching == n) {
            columns = matching;
            bySearch = true;
        }

        int byChoice = 0;
        for (Pick p : picks) {
            if (p.getRule() == Pick.Rule.CHOICE) {
                byChoice++;
            }
        }
        int chosen = byChoice;
        int matched = maxMatching;
        boolean search = bySearch;
        LOG.info(() -> String.format(Locale.ROOT,
                "run=%s iteration %d step 2: %d of %d assigned by the textbook rules (%d by choice), "
                        + "max possible %d -> %s",
                runId, index, picks.size(), n, chosen, matched,
                picks.size() == n ? "complete"
                        : search ? "rules fell short, complete assignment found by search over zeros"
                        : "incomplete, going to step 3"));
        return new Assignment(HungarianIteration.copy(matrix), marks, picks, maxMatching, bySearch, columns);
    }

    // номер единственной свободной клетки-нуля в строке (row = true) или столбце; -1, если их не одна
    private static int singleFree(int[][] marks, int line, boolean row) {
        int found = -1;
        for (int k = 0; k < marks.length; k++) {
            int mark = row ? marks[line][k] : marks[k][line];
            if (mark == FREE) {
                if (found >= 0) {
                    return -1;
                }
                found = k;
            }
        }
        return found;
    }

    private static int countFree(int[][] marks, int row) {
        int count = 0;
        for (int mark : marks[row]) {
            if (mark == FREE) {
                count++;
            }
        }
        return count;
    }

    private static int firstFree(int[][] marks, int row) {
        for (int c = 0; c < marks.length; c++) {
            if (marks[row][c] == FREE) {
                return c;
            }
        }
        throw new IllegalStateException("В строке " + (row + 1) + " нет свободного нуля");
    }

    // назначение (r, c): остальные свободные нули его строки и столбца вычеркиваются
    private static void pick(int[][] marks, boolean[] rowDone, boolean[] columnDone, int r, int c, int mark) {
        marks[r][c] = mark;
        rowDone[r] = true;
        columnDone[c] = true;
        for (int k = 0; k < marks.length; k++) {
            if (marks[r][k] == FREE) {
                marks[r][k] = HungarianIteration.CROSSED;
            }
            if (marks[k][c] == FREE) {
                marks[k][c] = HungarianIteration.CROSSED;
            }
        }
    }

    //#паросочетание - наибольшее число назначений по нулям (алгоритм Куна, увеличивающие цепочки)
    // результат: столбец для каждой строки, -1 - строке назначения не нашлось
    static int[] maxMatching(long[][] matrix) {
        int n = matrix.length;
        int[] columnOfRow = new int[n];
        int[] rowOfColumn = new int[n];
        Arrays.fill(columnOfRow, -1);
        Arrays.fill(rowOfColumn, -1);
        for (int r = 0; r < n; r++) {
            augment(matrix, r, new boolean[n], columnOfRow, rowOfColumn);
        }
        return columnOfRow;
    }

    private static boolean augment(long[][] matrix, int r, boolean[] seen, int[] columnOfRow, int[] rowOfColumn) {
        for (int c = 0; c < matrix.length; c++) {
            if (matrix[r][c] != 0 || seen[c]) {
                continue;
            }
            seen[c] = true;
            if (rowOfColumn[c] < 0 || augment(matrix, rowOfColumn[c], seen, columnOfRow, rowOfColumn)) {
                rowOfColumn[c] = r;
                columnOfRow[r] = c;
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ шаг 3

    private Cover cover(long[][] matrix, Assignment assignment, String runId, int index) {
        int n = matrix.length;
        boolean[] rows = new boolean[n];
        boolean[] columns = new boolean[n];
        List<Line> lines = new ArrayList<>();

        //#линии - правило пособия: строка или столбец с наибольшим числом ещё не вычеркнутых нулей;
        // из равных - строка раньше столбца, меньший номер раньше большего
        while (true) {
            int best = 0;
            boolean bestIsRow = true;
            int bestIndex = -1;
            for (int r = 0; r < n; r++) {
                int zeros = rows[r] ? 0 : openZeros(matrix, rows, columns, r, true);
                if (zeros > best) {
                    best = zeros;
                    bestIsRow = true;
                    bestIndex = r;
                }
            }
            for (int c = 0; c < n; c++) {
                int zeros = columns[c] ? 0 : openZeros(matrix, rows, columns, c, false);
                if (zeros > best) {
                    best = zeros;
                    bestIsRow = false;
                    bestIndex = c;
                }
            }
            if (bestIndex < 0) {
                break;
            }
            if (bestIsRow) {
                rows[bestIndex] = true;
            } else {
                columns[bestIndex] = true;
            }
            lines.add(new Line(bestIsRow, bestIndex, best));
        }

        int ruleLineCount = lines.size();
        int minimal = assignment.getMaxMatching();
        boolean replaced = ruleLineCount > minimal;
        if (replaced) {
            String ruleLines = describeLines(lines);
            LOG.info(() -> String.format(Locale.ROOT,
                    "run=%s iteration %d step 3: textbook rule drew %d lines %s, but %d are enough -> using the "
                            + "minimal cover", runId, index, ruleLineCount, ruleLines, minimal));
            rows = new boolean[n];
            columns = new boolean[n];
            minimalCover(matrix, rows, columns);
            lines = linesOf(matrix, rows, columns);
        }

        long minimum = Long.MAX_VALUE;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (!rows[r] && !columns[c]) {
                    minimum = Math.min(minimum, matrix[r][c]);
                }
            }
        }
        if (minimum == Long.MAX_VALUE || minimum <= 0) {
            // линий меньше n, поэтому невычеркнутые клетки есть, и нулей среди них нет
            throw new IllegalStateException("Шаг 3: неверный минимум невычеркнутых " + minimum
                    + " при " + lines.size() + " линиях (run=" + runId + ")");
        }

        long[][] after = HungarianIteration.copy(matrix);
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (!rows[r] && !columns[c]) {
                    after[r][c] -= minimum;
                } else if (rows[r] && columns[c]) {
                    after[r][c] += minimum;
                }
            }
        }

        long subtracted = minimum;
        List<Line> drawn = lines;
        LOG.info(() -> String.format(Locale.ROOT,
                "run=%s iteration %d step 3: %d lines %s, minimum uncovered %d subtracted from uncovered, "
                        + "added at intersections", runId, index, drawn.size(), describeLines(drawn), subtracted));
        logMatrix(runId, "after modification", after);
        return new Cover(lines, ruleLineCount, replaced, rows, columns, minimum, after);
    }

    private static int openZeros(long[][] matrix, boolean[] rows, boolean[] columns, int line, boolean row) {
        int count = 0;
        for (int k = 0; k < matrix.length; k++) {
            int r = row ? line : k;
            int c = row ? k : line;
            if (matrix[r][c] == 0 && !rows[r] && !columns[c]) {
                count++;
            }
        }
        return count;
    }

    //#кёниг - наименьшее число линий: от строк без назначения идём по нулям в столбцы,
    // из столбца - в строку, которой он назначен. Линии: строки, куда не дошли, и столбцы, куда дошли
    private static void minimalCover(long[][] matrix, boolean[] rows, boolean[] columns) {
        int n = matrix.length;
        int[] columnOfRow = maxMatching(matrix);
        int[] rowOfColumn = new int[n];
        Arrays.fill(rowOfColumn, -1);
        for (int r = 0; r < n; r++) {
            if (columnOfRow[r] >= 0) {
                rowOfColumn[columnOfRow[r]] = r;
            }
        }

        boolean[] rowReached = new boolean[n];
        boolean[] columnReached = new boolean[n];
        Deque<Integer> queue = new ArrayDeque<>();
        for (int r = 0; r < n; r++) {
            if (columnOfRow[r] < 0) {
                rowReached[r] = true;
                queue.add(r);
            }
        }
        while (!queue.isEmpty()) {
            int r = queue.poll();
            for (int c = 0; c < n; c++) {
                if (matrix[r][c] == 0 && !columnReached[c]) {
                    columnReached[c] = true;
                    int next = rowOfColumn[c];
                    if (next >= 0 && !rowReached[next]) {
                        rowReached[next] = true;
                        queue.add(next);
                    }
                }
            }
        }
        for (int k = 0; k < n; k++) {
            rows[k] = !rowReached[k];
            columns[k] = columnReached[k];
        }
    }

    // линии готового покрытия по порядку: сначала строки, потом столбцы
    private static List<Line> linesOf(long[][] matrix, boolean[] rows, boolean[] columns) {
        int n = matrix.length;
        boolean[] drawnRows = new boolean[n];
        boolean[] drawnColumns = new boolean[n];
        List<Line> lines = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            if (rows[r]) {
                lines.add(new Line(true, r, openZeros(matrix, drawnRows, drawnColumns, r, true)));
                drawnRows[r] = true;
            }
        }
        for (int c = 0; c < n; c++) {
            if (columns[c]) {
                lines.add(new Line(false, c, openZeros(matrix, drawnRows, drawnColumns, c, false)));
                drawnColumns[c] = true;
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ другие размещения

    // Другие размещения с той же прибылью. Итоговая матрица - исходная минус числа
    // строк и столбцов, поэтому у любого оптимального размещения все клетки в ней нулевые:
    // перебираются только нули, а прибыль каждого найденного проверяется по исходной таблице.
    private static final class Search {

        private final long[][] profit;
        private final long[][] matrix;
        private final long best;
        private final int[] main;
        private final int products;
        private final int[] chosen;
        private final boolean[] used;
        private final List<int[]> listed = new ArrayList<>();
        private int count;
        private int nodes;
        private boolean truncated;

        Search(long[][] profit, long[][] matrix, long best, int[] main, int products) {
            this.profit = profit;
            this.matrix = matrix;
            this.best = best;
            this.main = main;
            this.products = products;
            this.chosen = new int[products];
            this.used = new boolean[matrix.length];
        }

        void run() {
            visit(0, 0L);
        }

        private void visit(int row, long sum) {
            if (truncated) {
                return;
            }
            if (++nodes > MAX_SEARCH_NODES) {
                truncated = true;
                return;
            }
            if (row == products) {
                if (sum == best && !isMain()) {
                    count++;
                    if (listed.size() < MAX_LISTED_ALTERNATIVES) {
                        listed.add(chosen.clone());
                    }
                    if (count >= MAX_COUNTED_ALTERNATIVES) {
                        truncated = true;
                    }
                }
                return;
            }
            for (int c = 0; c < matrix.length; c++) {
                if (!used[c] && matrix[row][c] == 0) {
                    used[c] = true;
                    chosen[row] = c;
                    visit(row + 1, sum + profit[row][c]);
                    used[c] = false;
                }
            }
        }

        private boolean isMain() {
            for (int i = 0; i < products; i++) {
                if (chosen[i] != main[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ логи

    private static void logMatrix(String runId, String title, long[][] matrix) {
        if (!LOG.isLoggable(Level.FINE)) {
            return;
        }
        for (int i = 0; i < matrix.length; i++) {
            int row = i;
            LOG.fine(() -> "run=" + runId + " " + title + " row " + (row + 1) + ": " + Arrays.toString(matrix[row]));
        }
    }

    // «1->4 2->1 3->3»: вид -> предприятие, с единицы
    static String describePlan(int[] columns, int products) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < products; i++) {
            if (i > 0) {
                text.append(' ');
            }
            text.append(i + 1).append("->").append(columns[i] + 1);
        }
        return text.toString();
    }

    private static String describeLines(List<Line> lines) {
        StringBuilder text = new StringBuilder("[");
        for (int k = 0; k < lines.size(); k++) {
            Line line = lines.get(k);
            if (k > 0) {
                text.append(", ");
            }
            text.append(line.isRow() ? "row " : "column ").append(line.getIndex() + 1)
                    .append(" (").append(line.getZeros()).append(" zeros)");
        }
        return text.append(']').toString();
    }
}
