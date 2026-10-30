package ru.mashinka.multiflow.core.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

// максимальный поток между двумя вершинами неориентированной сети
// метод Форда-Фалкерсона: пока от S до T есть путь с запасом, пускаем по нему поток
// пути ищутся в ширину (вариант Эдмондса-Карпа): сначала самые короткие,
// при равной длине - через вершины с меньшими номерами; поэтому ответ всегда один и тот же
//#сложность - O(V*E^2) по времени; для сетей до 10 вершин это доли миллисекунды
public final class MaxFlow {

    private MaxFlow() {
    }

    //#исчерпание - ищем пути, пока они есть; каждый путь насыщаем по самой слабой ветви
    public static FlowResult compute(int[][] capacity, int source, int sink) {
        int n = capacity.length;
        if (source == sink) {
            throw new IllegalArgumentException("S и T должны быть разными вершинами: обе равны " + source);
        }

        // остаточная сеть: ветвь {u,v} можно пройти в обе стороны. Пустили d из u в v -
        // запас u->v уменьшился на d, а v->u вырос на d (поток можно развернуть обратно)
        int[][] residual = new int[n][];
        for (int i = 0; i < n; i++) {
            residual[i] = capacity[i].clone();
        }

        List<AugmentingPath> paths = new ArrayList<>();
        int total = 0;
        while (true) {
            int[] parent = searchPath(residual, source, sink);
            if (parent[sink] == -1) {
                break; // путей больше нет - поток максимальный
            }

            int amount = Integer.MAX_VALUE;
            for (int v = sink; v != source; v = parent[v]) {
                amount = Math.min(amount, residual[parent[v]][v]);
            }
            for (int v = sink; v != source; v = parent[v]) {
                residual[parent[v]][v] -= amount;
                residual[v][parent[v]] += amount;
            }

            paths.add(new AugmentingPath(restorePath(parent, source, sink), amount));
            total += amount;
        }

        // теорема Форда-Фалкерсона: всё, что достижимо от S по остаточной сети, -
        // это сторона S минимального разреза, и его величина равна найденному потоку
        boolean[] sourceSide = reachable(residual, source);
        return new FlowResult(total, paths, sourceSide, residual);
    }

    // поиск в ширину по ветвям с положительным запасом; parent[v] = откуда пришли в v
    private static int[] searchPath(int[][] residual, int source, int sink) {
        int n = residual.length;
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        parent[source] = source;

        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty() && parent[sink] == -1) {
            int v = queue.poll();
            for (int u = 0; u < n; u++) {
                if (parent[u] == -1 && residual[v][u] > 0) {
                    parent[u] = v;
                    queue.add(u);
                }
            }
        }
        return parent;
    }

    private static int[] restorePath(int[] parent, int source, int sink) {
        List<Integer> reversed = new ArrayList<>();
        for (int v = sink; v != source; v = parent[v]) {
            reversed.add(v);
        }
        reversed.add(source);

        int[] path = new int[reversed.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = reversed.get(path.length - 1 - i);
        }
        return path;
    }

    private static boolean[] reachable(int[][] residual, int source) {
        int n = residual.length;
        boolean[] seen = new boolean[n];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        seen[source] = true;
        while (!queue.isEmpty()) {
            int v = queue.poll();
            for (int u = 0; u < n; u++) {
                if (!seen[u] && residual[v][u] > 0) {
                    seen[u] = true;
                    queue.add(u);
                }
            }
        }
        return seen;
    }
}
