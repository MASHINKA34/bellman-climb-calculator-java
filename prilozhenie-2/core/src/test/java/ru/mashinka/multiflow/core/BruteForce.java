package ru.mashinka.multiflow.core;

// независимая проверка без единой строчки кода решателя:
// минимальный разрез между s и t ищется полным перебором всех разбиений вершин.
// По теореме Форда–Фалкерсона он равен максимальному потоку.
// Для сетей до 10 вершин это не больше 2^8 = 256 разбиений на пару.
public final class BruteForce {

    private BruteForce() {
    }

    public static int minCut(int[][] capacity, int s, int t) {
        int n = capacity.length;
        int best = Integer.MAX_VALUE;
        for (int mask = 0; mask < (1 << n); mask++) {
            boolean sInside = (mask & (1 << s)) != 0;
            boolean tInside = (mask & (1 << t)) != 0;
            if (!sInside || tInside) {
                continue;
            }
            best = Math.min(best, cutValue(capacity, mask));
        }
        return best;
    }

    // сумма ветвей между вершинами из mask и всеми остальными
    public static int cutValue(int[][] capacity, int mask) {
        int n = capacity.length;
        int sum = 0;
        for (int u = 0; u < n; u++) {
            if ((mask & (1 << u)) == 0) {
                continue;
            }
            for (int v = 0; v < n; v++) {
                if ((mask & (1 << v)) == 0) {
                    sum += capacity[u][v];
                }
            }
        }
        return sum;
    }

    public static int[][] allPairs(int[][] capacity) {
        int n = capacity.length;
        int[][] table = new int[n][n];
        for (int s = 0; s < n; s++) {
            for (int t = s + 1; t < n; t++) {
                int value = minCut(capacity, s, t);
                table[s][t] = value;
                table[t][s] = value;
            }
        }
        return table;
    }
}
