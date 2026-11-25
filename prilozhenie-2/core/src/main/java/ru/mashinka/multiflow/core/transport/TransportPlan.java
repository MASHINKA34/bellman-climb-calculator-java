package ru.mashinka.multiflow.core.transport;

import java.util.Collections;
import java.util.List;

// план перевозок по закрытой модели: сколько везти в каждой клетке, какие клетки заполнены
// (базисные, среди них бывают нули) и общая стоимость
//#неизменяемость - поля final, массивы наружу отдаются копиями
public final class TransportPlan {

    private final long[][] amount;
    private final boolean[][] filled;
    private final long cost;
    // как план построен; у оптимального плана шаги не пишутся
    private final List<TransportStep> steps;

    TransportPlan(long[][] amount, boolean[][] filled, int[][] tariffs, List<TransportStep> steps) {
        int rows = amount.length;
        this.amount = new long[rows][];
        this.filled = new boolean[rows][];
        long total = 0;
        for (int i = 0; i < rows; i++) {
            this.amount[i] = amount[i].clone();
            this.filled[i] = filled[i].clone();
            for (int j = 0; j < amount[i].length; j++) {
                total += amount[i][j] * tariffs[i][j];
            }
        }
        this.cost = total;
        this.steps = Collections.unmodifiableList(steps);
    }

    public long[][] getAmount() {
        long[][] copy = new long[amount.length][];
        for (int i = 0; i < amount.length; i++) {
            copy[i] = amount[i].clone();
        }
        return copy;
    }

    public boolean[][] getFilled() {
        boolean[][] copy = new boolean[filled.length][];
        for (int i = 0; i < filled.length; i++) {
            copy[i] = filled[i].clone();
        }
        return copy;
    }

    public long getCost() {
        return cost;
    }

    public List<TransportStep> getSteps() {
        return steps;
    }

    // сколько клеток заполнено; у опорного плана их m + n - 1
    public int filledCount() {
        int count = 0;
        for (boolean[] row : filled) {
            for (boolean value : row) {
                if (value) {
                    count++;
                }
            }
        }
        return count;
    }

    // базисные нули: заполненные клетки с перевозкой 0 (план вырожденный)
    public int zeroCount() {
        int count = 0;
        for (int i = 0; i < filled.length; i++) {
            for (int j = 0; j < filled[i].length; j++) {
                if (filled[i][j] && amount[i][j] == 0) {
                    count++;
                }
            }
        }
        return count;
    }
}
