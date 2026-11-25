package ru.mashinka.multiflow.core.transport;

import java.util.Arrays;

// независимая проверка: ни одной строки из TransportSolver.
//   - северо-западный угол - через накопленные суммы запасов и потребностей: в клетке
//     лежит пересечение отрезков [A(i-1), A(i)] и [B(j-1), B(j)];
//   - оптимум - полный перебор целых планов (маленькие таблицы) и потоки минимальной
//     стоимости по кратчайшим путям (любые таблицы).
final class TransportReference {

    private TransportReference() {
    }

    static long[] supply(TransportProblem problem) {
        long excess = problem.getTotalDemand() - problem.getTotalSupply();
        long[] a = Arrays.stream(problem.getSupply()).asLongStream().toArray();
        if (excess > 0) {
            a = Arrays.copyOf(a, a.length + 1);
            a[a.length - 1] = excess;
        }
        return a;
    }

    static long[] demand(TransportProblem problem) {
        long excess = problem.getTotalSupply() - problem.getTotalDemand();
        long[] b = Arrays.stream(problem.getDemand()).asLongStream().toArray();
        if (excess > 0) {
            b = Arrays.copyOf(b, b.length + 1);
            b[b.length - 1] = excess;
        }
        return b;
    }

    static int cost(TransportProblem problem, int i, int j) {
        return i < problem.suppliers() && j < problem.consumers() ? problem.cost(i, j) : 0;
    }

    static long[][] northWest(TransportProblem problem) {
        long[] a = supply(problem);
        long[] b = demand(problem);
        long[][] plan = new long[a.length][b.length];
        long aStart = 0;
        for (int i = 0; i < a.length; i++) {
            long bStart = 0;
            for (int j = 0; j < b.length; j++) {
                long from = Math.max(aStart, bStart);
                long to = Math.min(aStart + a[i], bStart + b[j]);
                plan[i][j] = Math.max(0, to - from);
                bStart += b[j];
            }
            aStart += a[i];
        }
        return plan;
    }

    static long total(TransportProblem problem, long[][] plan) {
        long sum = 0;
        for (int i = 0; i < plan.length; i++) {
            for (int j = 0; j < plan[i].length; j++) {
                sum += plan[i][j] * cost(problem, i, j);
            }
        }
        return sum;
    }

    // полный перебор: клетка за клеткой, все возможные целые количества
    static long bruteForce(TransportProblem problem) {
        return search(problem, supply(problem), demand(problem), 0, 0);
    }

    private static long search(TransportProblem problem, long[] a, long[] b, int i, int j) {
        if (i == a.length) {
            return Arrays.stream(b).allMatch(left -> left == 0) ? 0 : Long.MAX_VALUE;
        }
        if (j == b.length) {
            return a[i] == 0 ? search(problem, a, b, i + 1, 0) : Long.MAX_VALUE;
        }
        long best = Long.MAX_VALUE;
        long limit = Math.min(a[i], b[j]);
        for (long x = 0; x <= limit; x++) {
            a[i] -= x;
            b[j] -= x;
            long rest = search(problem, a, b, i, j + 1);
            a[i] += x;
            b[j] += x;
            if (rest != Long.MAX_VALUE) {
                best = Math.min(best, rest + x * cost(problem, i, j));
            }
        }
        return best;
    }

    // поток минимальной стоимости: пока есть запас, груз идёт по самому дешёвому пути
    // (Беллман-Форд по остаточной сети), в том числе в обход уже назначенных перевозок
    static long shortestPaths(TransportProblem problem) {
        long[] a = supply(problem);
        long[] b = demand(problem);
        int m = a.length;
        int n = b.length;
        long[][] x = new long[m][n];
        while (true) {
            long[] distance = new long[m + n];
            int[] previous = new int[m + n];
            Arrays.fill(distance, Long.MAX_VALUE / 4);
            Arrays.fill(previous, -1);
            for (int i = 0; i < m; i++) {
                if (a[i] > 0) {
                    distance[i] = 0;
                }
            }
            for (int pass = 0; pass < m + n; pass++) {
                for (int i = 0; i < m; i++) {
                    for (int j = 0; j < n; j++) {
                        long forward = distance[i] + cost(problem, i, j);
                        if (forward < distance[m + j]) {
                            distance[m + j] = forward;
                            previous[m + j] = i;
                        }
                        long back = distance[m + j] - cost(problem, i, j);
                        if (x[i][j] > 0 && back < distance[i]) {
                            distance[i] = back;
                            previous[i] = m + j;
                        }
                    }
                }
            }
            int target = -1;
            for (int j = 0; j < n; j++) {
                if (b[j] > 0 && (target == -1 || distance[m + j] < distance[target])) {
                    target = m + j;
                }
            }
            if (target == -1) {
                return total(problem, x);
            }
            long flow = b[target - m];
            int node = target;
            while (previous[node] != -1) {
                int from = previous[node];
                if (from >= m) {
                    flow = Math.min(flow, x[node][from - m]);
                }
                node = from;
            }
            flow = Math.min(flow, a[node]);
            a[node] -= flow;
            b[target - m] -= flow;
            node = target;
            while (previous[node] != -1) {
                int from = previous[node];
                if (from < m) {
                    x[from][node - m] += flow;
                } else {
                    x[node][from - m] -= flow;
                }
                node = from;
            }
        }
    }
}
