package ru.mashinka.multiflow.core.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

// неориентированная сеть, заданная симметричной матрицей пропускных способностей
// c(i,j) = c(j,i) - ветвь между вершинами i и j, 0 - ветви нет
// вершины внутри нумеруются с нуля, пользователю показываются с единицы
//#инкапсуляция - матрица private, наружу только копия и чтение по клеткам
public final class Network {

    public static final int MIN_VERTICES = 2;
    public static final int MAX_VERTICES = 10;
    public static final int MAX_CAPACITY = 1_000_000;

    private final int[][] capacity;

    private Network(int[][] capacity) {
        this.capacity = capacity;
    }

    //#валидация - отсекаем плохой ввод до расчёта; текст ошибки сразу для пользователя
    public static Network of(int[][] matrix) {
        if (matrix == null) {
            throw new IllegalArgumentException("Матрица пропускных способностей не задана");
        }
        int n = matrix.length;
        if (n < MIN_VERTICES || n > MAX_VERTICES) {
            throw new IllegalArgumentException("Число вершин должно быть от " + MIN_VERTICES
                    + " до " + MAX_VERTICES + ", а задано " + n);
        }
        for (int i = 0; i < n; i++) {
            int length = matrix[i] == null ? 0 : matrix[i].length;
            if (length != n) {
                throw new IllegalArgumentException("Матрица должна быть квадратной: в строке "
                        + (i + 1) + " " + length + " чисел вместо " + n);
            }
        }

        int[][] copy = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int value = matrix[i][j];
                if (i == j) {
                    if (value != 0) {
                        throw new IllegalArgumentException("Клетка " + cell(i, j)
                                + ": на диагонали должен быть 0 - ветвь из вершины в саму себя не допускается");
                    }
                    continue;
                }
                if (value < 0) {
                    throw new IllegalArgumentException("Клетка " + cell(i, j)
                            + ": пропускная способность не может быть отрицательной (" + value + ")");
                }
                if (value > MAX_CAPACITY) {
                    throw new IllegalArgumentException("Клетка " + cell(i, j)
                            + ": пропускная способность не больше 1 000 000 (" + value + ")");
                }
                copy[i][j] = value;
            }
        }

        boolean anyBranch = false;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (copy[i][j] != copy[j][i]) {
                    throw new IllegalArgumentException("Матрица несимметрична: c" + cell(i, j) + " = "
                            + copy[i][j] + ", а c" + cell(j, i) + " = " + copy[j][i]);
                }
                anyBranch |= copy[i][j] > 0;
            }
        }
        if (!anyBranch) {
            throw new IllegalArgumentException("В сети нет ни одной ветви: введите хотя бы одну "
                    + "ненулевую пропускную способность");
        }
        return new Network(copy);
    }

    private static String cell(int i, int j) {
        return "(" + (i + 1) + "," + (j + 1) + ")";
    }

    public int size() {
        return capacity.length;
    }

    public int capacity(int i, int j) {
        return capacity[i][j];
    }

    public int[][] toMatrix() {
        int[][] copy = new int[capacity.length][];
        for (int i = 0; i < capacity.length; i++) {
            copy[i] = capacity[i].clone();
        }
        return copy;
    }

    // число ветвей: каждая пара вершин считается один раз
    public int branchCount() {
        int count = 0;
        for (int i = 0; i < capacity.length; i++) {
            for (int j = i + 1; j < capacity.length; j++) {
                if (capacity[i][j] > 0) {
                    count++;
                }
            }
        }
        return count;
    }

    public long totalCapacity() {
        long total = 0;
        for (int i = 0; i < capacity.length; i++) {
            for (int j = i + 1; j < capacity.length; j++) {
                total += capacity[i][j];
            }
        }
        return total;
    }

    // части сети, между которыми нет ни одной ветви; поток между ними всегда 0
    //#коллекции - обход в ширину через ArrayDeque, части копятся в ArrayList
    public List<int[]> components() {
        int n = capacity.length;
        boolean[] seen = new boolean[n];
        List<int[]> parts = new ArrayList<>();
        for (int start = 0; start < n; start++) {
            if (seen[start]) {
                continue;
            }
            List<Integer> members = new ArrayList<>();
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int v = queue.poll();
                members.add(v);
                for (int u = 0; u < n; u++) {
                    if (!seen[u] && capacity[v][u] > 0) {
                        seen[u] = true;
                        queue.add(u);
                    }
                }
            }
            Collections.sort(members);
            int[] part = new int[members.size()];
            for (int k = 0; k < part.length; k++) {
                part[k] = members.get(k);
            }
            parts.add(part);
        }
        return parts;
    }
}
