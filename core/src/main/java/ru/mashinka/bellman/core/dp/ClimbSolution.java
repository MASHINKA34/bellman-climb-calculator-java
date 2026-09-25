package ru.mashinka.bellman.core.dp;

import java.util.Collections;
import java.util.List;

import ru.mashinka.bellman.core.model.Criterion;

// результат расчёта: траектория, итоги и таблица функции Беллмана
//#неизменяемость - поля final, списки через unmodifiableList
//#дженерики - List<TrajectoryPoint>
public class ClimbSolution {

    private final Criterion criterion;
    private final double optimalValue;

    private final List<TrajectoryPoint> trajectory;

    private final double totalTime;
    private final double totalFuel;
    private final double totalDistance;

    private final double[] altitudes;
    private final double[] speeds;
    private final double[][] valueFunction;
    private final boolean[][] feasible;

    private final int feasibleNodes;
    private final long evaluatedTransitions;
    private final long solveTimeMillis;

    private final List<String> notes;

    ClimbSolution(Criterion criterion, double optimalValue, List<TrajectoryPoint> trajectory,
                  double totalTime, double totalFuel, double totalDistance,
                  double[] altitudes, double[] speeds, double[][] valueFunction, boolean[][] feasible,
                  int feasibleNodes, long evaluatedTransitions, long solveTimeMillis,
                  List<String> notes) {
        this.criterion = criterion;
        this.optimalValue = optimalValue;
        this.trajectory = Collections.unmodifiableList(trajectory);
        this.totalTime = totalTime;
        this.totalFuel = totalFuel;
        this.totalDistance = totalDistance;
        this.altitudes = altitudes;
        this.speeds = speeds;
        this.valueFunction = valueFunction;
        this.feasible = feasible;
        this.feasibleNodes = feasibleNodes;
        this.evaluatedTransitions = evaluatedTransitions;
        this.solveTimeMillis = solveTimeMillis;
        this.notes = Collections.unmodifiableList(notes);
    }

    // критерий, по которому проводилась оптимизация
    public Criterion getCriterion() {
        return criterion;
    }

    // значение функции Беллмана в начальном состоянии — минимум выбранного критерия
    // Единицы измерения зависят от критерия: секунды, килограммы или приведённые килограммы.
    public double getOptimalValue() {
        return optimalValue;
    }

    // оптимальная траектория набора: от начальной высоты до крейсерской
    public List<TrajectoryPoint> getTrajectory() {
        return trajectory;
    }

    // полное время набора крейсерской высоты, с
    public double getTotalTime() {
        return totalTime;
    }

    // полный расход топлива на наборе, кг
    public double getTotalFuel() {
        return totalFuel;
    }

    // горизонтальная дальность, пройденная за набор, м
    public double getTotalDistance() {
        return totalDistance;
    }

    // высотные уровни сетки, м
    public double[] getAltitudes() {
        return altitudes;
    }

    // скорости сетки, м/с
    public double[] getSpeeds() {
        return speeds;
    }

    // f[k][i] - затраты от узла до крейсерской высоты; бесконечность = недостижимо
    public double[][] getValueFunction() {
        return valueFunction;
    }

    // маска допустимости узлов сетки по ограничениям скорости
    public boolean[][] getFeasible() {
        return feasible;
    }

    // число допустимых узлов сетки
    public int getFeasibleNodes() {
        return feasibleNodes;
    }

    // число просмотренных алгоритмом переходов между узлами
    public long getEvaluatedTransitions() {
        return evaluatedTransitions;
    }

    // время работы алгоритма, мс
    public long getSolveTimeMillis() {
        return solveTimeMillis;
    }

    // замечания к расчёту: скорректированные исходные данные, предупреждения
    public List<String> getNotes() {
        return notes;
    }
}
