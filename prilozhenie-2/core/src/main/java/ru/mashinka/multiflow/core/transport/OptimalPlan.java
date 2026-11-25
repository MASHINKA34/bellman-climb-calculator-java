package ru.mashinka.multiflow.core.transport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import ru.mashinka.multiflow.core.transport.TransportProblem.Balanced;

// оптимальный план методом потенциалов. Начинает с опорного плана и, пока есть клетка с
// отрицательной оценкой, переносит груз по её циклу.
// Входящая и выходящая клетки выбираются по наименьшему номеру (правило Блэнда): так метод
// не ходит по кругу и на вырожденных планах.
//#потенциалы - u + v = тариф для базисных клеток, оценка остальных = тариф - u - v
final class OptimalPlan {

    private static final Logger LOG = Logger.getLogger(OptimalPlan.class.getName());

    // защита от зацикливания; на таблицах до 11 x 11 итераций десятки
    static final int MAX_ITERATIONS = 100_000;

    private final TransportPlan plan;
    private final boolean unique;
    private final int iterations;

    private OptimalPlan(TransportPlan plan, boolean unique, int iterations) {
        this.plan = plan;
        this.unique = unique;
        this.iterations = iterations;
    }

    TransportPlan getPlan() {
        return plan;
    }

    // единственный ли это оптимальный план; стоимость у всех оптимальных одна
    boolean isUnique() {
        return unique;
    }

    int getIterations() {
        return iterations;
    }

    static OptimalPlan improve(Balanced table, TransportPlan start, String runId) {
        long[][] amount = start.getAmount();
        boolean[][] basis = start.getFilled();
        long[] u = new long[table.rows()];
        long[] v = new long[table.columns()];
        int iterations = 0;
        while (true) {
            potentials(table, basis, u, v);
            int[] entering = entering(table, basis, u, v);
            if (entering == null) {
                break;
            }
            iterations++;
            if (iterations > MAX_ITERATIONS) {
                throw new IllegalStateException("Метод потенциалов не сошёлся за " + MAX_ITERATIONS
                        + " итераций (run=" + runId + ")");
            }
            shift(amount, basis, entering, runId, iterations);
        }
        boolean unique = isUnique(table, amount, u, v);
        TransportPlan plan = new TransportPlan(amount, basis, table.getCost(), Collections.emptyList());
        int count = iterations;
        LOG.info(() -> String.format(Locale.ROOT,
                "run=%s optimal done: cost %d (started from %d), %d iterations, unique plan: %s",
                runId, plan.getCost(), start.getCost(), count, unique));
        return new OptimalPlan(plan, unique, iterations);
    }

    // потенциалы по базисным клеткам: у первой строки 0, остальные по цепочке
    private static void potentials(Balanced table, boolean[][] basis, long[] u, long[] v) {
        int rows = table.rows();
        int columns = table.columns();
        boolean[] knownU = new boolean[rows];
        boolean[] knownV = new boolean[columns];
        // узлы базисного дерева: 0..rows-1 - строки, дальше столбцы
        Deque<Integer> queue = new ArrayDeque<>();
        u[0] = 0;
        knownU[0] = true;
        queue.add(0);
        int known = 1;
        while (!queue.isEmpty()) {
            int node = queue.poll();
            if (node < rows) {
                for (int j = 0; j < columns; j++) {
                    if (basis[node][j] && !knownV[j]) {
                        v[j] = table.cost(node, j) - u[node];
                        knownV[j] = true;
                        known++;
                        queue.add(rows + j);
                    }
                }
            } else {
                int j = node - rows;
                for (int i = 0; i < rows; i++) {
                    if (basis[i][j] && !knownU[i]) {
                        u[i] = table.cost(i, j) - v[j];
                        knownU[i] = true;
                        known++;
                        queue.add(i);
                    }
                }
            }
        }
        if (known != rows + columns) {
            // базис обязан быть деревом из m + n - 1 клеток: иначе ошибка в построении плана
            throw new IllegalStateException("Базис не связный: потенциалы найдены для " + known + " из "
                    + (rows + columns));
        }
    }

    private static int[] entering(Balanced table, boolean[][] basis, long[] u, long[] v) {
        for (int i = 0; i < table.rows(); i++) {
            for (int j = 0; j < table.columns(); j++) {
                if (!basis[i][j] && table.cost(i, j) - u[i] - v[j] < 0) {
                    return new int[]{i, j};
                }
            }
        }
        return null;
    }

    // перенос груза по циклу входящей клетки: в ней плюс, дальше по очереди минус и плюс
    private static void shift(long[][] amount, boolean[][] basis, int[] entering, String runId, int iteration) {
        List<int[]> cycle = cycle(basis, entering);
        long theta = Long.MAX_VALUE;
        int[] leaving = null;
        for (int k = 1; k < cycle.size(); k += 2) {
            int[] cell = cycle.get(k);
            long value = amount[cell[0]][cell[1]];
            if (value < theta || (value == theta && before(cell, leaving))) {
                theta = value;
                leaving = cell;
            }
        }
        for (int k = 0; k < cycle.size(); k++) {
            int[] cell = cycle.get(k);
            amount[cell[0]][cell[1]] += k % 2 == 0 ? theta : -theta;
        }
        basis[entering[0]][entering[1]] = true;
        basis[leaving[0]][leaving[1]] = false;
        int[] out = leaving;
        long moved = theta;
        LOG.fine(() -> String.format(Locale.ROOT,
                "run=%s optimal iteration %d: enters (%d,%d), leaves (%d,%d), moved %d, cycle of %d cells",
                runId, iteration, entering[0] + 1, entering[1] + 1, out[0] + 1, out[1] + 1, moved, cycle.size()));
    }

    private static boolean before(int[] a, int[] b) {
        return a[0] != b[0] ? a[0] < b[0] : a[1] < b[1];
    }

    // цикл: входящая клетка, затем путь по базисному дереву от её столбца к её строке
    private static List<int[]> cycle(boolean[][] basis, int[] entering) {
        int rows = basis.length;
        int columns = basis[0].length;
        int[] parent = new int[rows + columns];
        Arrays.fill(parent, -1);
        int from = rows + entering[1];
        int to = entering[0];
        parent[from] = from;
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(from);
        while (parent[to] == -1 && !queue.isEmpty()) {
            int node = queue.poll();
            boolean isRow = node < rows;
            int count = isRow ? columns : rows;
            for (int k = 0; k < count; k++) {
                boolean linked = isRow ? basis[node][k] : basis[k][node - rows];
                int next = isRow ? rows + k : k;
                if (linked && parent[next] == -1) {
                    parent[next] = node;
                    queue.add(next);
                }
            }
        }
        List<int[]> path = new ArrayList<>();
        for (int node = to; node != from; node = parent[node]) {
            int previous = parent[node];
            path.add(node < rows ? new int[]{node, previous - rows} : new int[]{previous, node - rows});
        }
        Collections.reverse(path);
        List<int[]> cycle = new ArrayList<>();
        cycle.add(entering);
        cycle.addAll(path);
        return cycle;
    }

    // другой оптимальный план есть, если груз можно сдвинуть по циклу из клеток с нулевой оценкой:
    // в пустую клетку добавить, а из клеток с грузом забрать
    private static boolean isUnique(Balanced table, long[][] amount, long[] u, long[] v) {
        int rows = table.rows();
        int columns = table.columns();
        boolean[][] zero = new boolean[rows][columns];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) {
                zero[i][j] = table.cost(i, j) - u[i] - v[j] == 0;
            }
        }
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) {
                if (zero[i][j] && amount[i][j] == 0 && reachable(zero, amount, j, i)) {
                    return false;
                }
            }
        }
        return true;
    }

    // можно ли дойти от столбца до строки: из столбца - по клеткам с грузом,
    // из строки - по клеткам с нулевой оценкой
    private static boolean reachable(boolean[][] zero, long[][] amount, int column, int row) {
        int rows = zero.length;
        int columns = zero[0].length;
        boolean[] seen = new boolean[rows + columns];
        Deque<Integer> queue = new ArrayDeque<>();
        seen[rows + column] = true;
        queue.add(rows + column);
        while (!queue.isEmpty()) {
            int node = queue.poll();
            boolean isRow = node < rows;
            int count = isRow ? columns : rows;
            for (int k = 0; k < count; k++) {
                boolean linked = isRow ? zero[node][k] : amount[k][node - rows] > 0;
                int next = isRow ? rows + k : k;
                if (linked && !seen[next]) {
                    if (next == row) {
                        return true;
                    }
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        return false;
    }
}
