package ru.mashinka.multiflow.core.transport;

import java.util.Collections;
import java.util.List;

import ru.mashinka.multiflow.core.transport.TransportProblem.Balanced;

// ответ вкладки «Транспортная»: закрытая модель, начальные планы двумя методами и оптимум
//#неизменяемость - поля final, списки только для чтения
public final class TransportSolution {

    private final TransportProblem problem;
    private final Balanced balanced;
    private final boolean fictitiousLast;
    private final TransportPlan northWest;
    private final TransportPlan minimumCost;
    private final List<Long> otherCosts;
    private final boolean otherCostsTruncated;
    private final TransportPlan optimal;
    private final boolean optimalUnique;
    private final long elapsedNanos;

    TransportSolution(TransportProblem problem, Balanced balanced, boolean fictitiousLast, TransportPlan northWest,
                      TransportPlan minimumCost, List<Long> otherCosts, boolean otherCostsTruncated,
                      TransportPlan optimal, boolean optimalUnique, long elapsedNanos) {
        this.problem = problem;
        this.balanced = balanced;
        this.fictitiousLast = fictitiousLast;
        this.northWest = northWest;
        this.minimumCost = minimumCost;
        this.otherCosts = Collections.unmodifiableList(otherCosts);
        this.otherCostsTruncated = otherCostsTruncated;
        this.optimal = optimal;
        this.optimalUnique = optimalUnique;
        this.elapsedNanos = elapsedNanos;
    }

    public TransportProblem getProblem() {
        return problem;
    }

    public Balanced getBalanced() {
        return balanced;
    }

    // фиктивные клетки в методе минимальной стоимости брались в последнюю очередь
    public boolean isFictitiousLast() {
        return fictitiousLast;
    }

    public TransportPlan getNorthWest() {
        return northWest;
    }

    public TransportPlan getMinimumCost() {
        return minimumCost;
    }

    // стоимости, которые вышли бы при другом выборе среди равных тарифов (без показанной)
    public List<Long> getOtherCosts() {
        return otherCosts;
    }

    // перебор оборван: стоимостей может быть больше
    public boolean isOtherCostsTruncated() {
        return otherCostsTruncated;
    }

    public TransportPlan getOptimal() {
        return optimal;
    }

    public boolean isOptimalUnique() {
        return optimalUnique;
    }

    public long getElapsedNanos() {
        return elapsedNanos;
    }
}
