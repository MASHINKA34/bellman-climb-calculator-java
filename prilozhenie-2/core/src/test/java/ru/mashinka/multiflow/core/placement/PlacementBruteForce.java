package ru.mashinka.multiflow.core.placement;

import java.util.ArrayList;
import java.util.List;

// независимая проверка без единой строчки венгерского метода:
// перебор всех размещений видов по разным предприятиям (не больше 10!/0! = 3 628 800)
final class PlacementBruteForce {

    final long best;
    // все размещения с наибольшей прибылью: предприятие для каждого вида
    final List<int[]> optimal = new ArrayList<>();

    PlacementBruteForce(long[][] profit) {
        int m = profit.length;
        int n = profit[0].length;
        long[] bestHolder = {Long.MIN_VALUE};
        visit(profit, 0, new int[m], new boolean[n], 0L, bestHolder);
        best = bestHolder[0];
    }

    private void visit(long[][] profit, int row, int[] chosen, boolean[] used, long sum, long[] bestHolder) {
        if (row == chosen.length) {
            if (sum > bestHolder[0]) {
                bestHolder[0] = sum;
                optimal.clear();
            }
            if (sum == bestHolder[0]) {
                optimal.add(chosen.clone());
            }
            return;
        }
        for (int c = 0; c < used.length; c++) {
            if (!used[c]) {
                used[c] = true;
                chosen[row] = c;
                visit(profit, row + 1, chosen, used, sum + profit[row][c], bestHolder);
                used[c] = false;
            }
        }
    }
}
