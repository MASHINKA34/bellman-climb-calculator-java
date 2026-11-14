package ru.mashinka.multiflow.core.placement;

import java.util.Collections;
import java.util.List;

// результат: матрица для венгерского метода, итерации и размещение с наибольшей прибылью
// суммы - в рублях; на экран они выводятся в тыс. руб., как в учебнике
//#неизменяемость - поля final, списки через unmodifiableList, массивы отдаются копиями
public final class PlacementSolution {

    private final PlacementProblem problem;
    private final long maxProfit;
    private final long[][] cost;
    private final List<HungarianIteration> iterations;
    private final int[] columns;
    private final long totalProfit;
    private final List<int[]> alternatives;
    private final int alternativeCount;
    private final boolean alternativesTruncated;
    private final long elapsedNanos;

    PlacementSolution(PlacementProblem problem, long maxProfit, long[][] cost, List<HungarianIteration> iterations,
                      int[] columns, long totalProfit, List<int[]> alternatives, int alternativeCount,
                      boolean alternativesTruncated, long elapsedNanos) {
        this.problem = problem;
        this.maxProfit = maxProfit;
        this.cost = cost;
        this.iterations = Collections.unmodifiableList(iterations);
        this.columns = columns;
        this.totalProfit = totalProfit;
        this.alternatives = Collections.unmodifiableList(alternatives);
        this.alternativeCount = alternativeCount;
        this.alternativesTruncated = alternativesTruncated;
        this.elapsedNanos = elapsedNanos;
    }

    public PlacementProblem getProblem() {
        return problem;
    }

    // наибольшая общая прибыль в таблице - число, которое прибавляется при сведении к минимуму
    public long getMaxProfit() {
        return maxProfit;
    }

    // квадратная матрица для венгерского метода: строки видов - максимум минус прибыль,
    // строки фиктивных видов - нули
    public long[][] getCost() {
        return HungarianIteration.copy(cost);
    }

    public int getFictitious() {
        return problem.plants() - problem.products();
    }

    public List<HungarianIteration> getIterations() {
        return iterations;
    }

    // предприятие для каждой строки матрицы, включая фиктивные виды
    public int[] getColumns() {
        return columns.clone();
    }

    // предприятие, на котором производится вид product
    public int plantOf(int product) {
        return columns[product];
    }

    public long getTotalProfit() {
        return totalProfit;
    }

    // другие размещения с той же прибылью: предприятие для каждого настоящего вида
    // (в списке не больше нескольких десятков, полное число - getAlternativeCount)
    public List<int[]> getAlternatives() {
        return alternatives;
    }

    public int getAlternativeCount() {
        return alternativeCount;
    }

    // перебор других размещений остановлен по лимиту: их больше, чем getAlternativeCount
    public boolean isAlternativesTruncated() {
        return alternativesTruncated;
    }

    public long getElapsedNanos() {
        return elapsedNanos;
    }
}
