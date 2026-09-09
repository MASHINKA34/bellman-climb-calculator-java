package ru.mashinka.bellman.web.api;

import java.util.ArrayList;
import java.util.List;

import ru.mashinka.bellman.core.dp.ClimbSolution;
import ru.mashinka.bellman.core.dp.TrajectoryPoint;

/**
 * Ответ на запрос расчёта.
 *
 * <p>Функция Беллмана переносится в {@code Double[][]}, где {@code null} означает
 * недостижимое состояние: бесконечность нельзя записать в корректный JSON.
 * На очень подробных сетках таблица не передаётся целиком — иначе ответ разрастается
 * до сотен килобайт, а браузер телефона не справляется с её отрисовкой.
 */
public class ClimbResponse {

    /** Предел числа узлов, при котором таблица функции Беллмана ещё передаётся клиенту. */
    private static final int MAX_MATRIX_NODES = 6000;

    private String criterion;
    private String criterionTitle;
    private String criterionUnit;
    private double optimalValue;

    private double totalTime;
    private double totalFuel;
    private double totalDistance;

    private List<TrajectoryPoint> trajectory;

    private double[] altitudes;
    private double[] speeds;
    private Double[][] valueFunction;
    private boolean[][] feasible;

    private int feasibleNodes;
    private long evaluatedTransitions;
    private long solveTimeMillis;

    private List<String> notes;

    /** Преобразует результат расчёта ядра в пригодный для JSON ответ. */
    public static ClimbResponse from(ClimbSolution solution) {
        ClimbResponse response = new ClimbResponse();
        response.criterion = solution.getCriterion().name();
        response.criterionTitle = solution.getCriterion().getTitle();
        response.criterionUnit = solution.getCriterion().getUnit();
        response.optimalValue = solution.getOptimalValue();
        response.totalTime = solution.getTotalTime();
        response.totalFuel = solution.getTotalFuel();
        response.totalDistance = solution.getTotalDistance();
        response.trajectory = solution.getTrajectory();
        response.altitudes = solution.getAltitudes();
        response.speeds = solution.getSpeeds();
        response.feasibleNodes = solution.getFeasibleNodes();
        response.evaluatedTransitions = solution.getEvaluatedTransitions();
        response.solveTimeMillis = solution.getSolveTimeMillis();

        List<String> notes = new ArrayList<>(solution.getNotes());
        int nodes = solution.getAltitudes().length * solution.getSpeeds().length;
        if (nodes <= MAX_MATRIX_NODES) {
            response.valueFunction = toNullableMatrix(solution.getValueFunction());
            response.feasible = solution.getFeasible();
        } else {
            notes.add(String.format(
                    "Число узлов сетки - %d, поэтому поузловые таблицы не передаются: "
                            + "ответ весил бы сотни килобайт, а браузер телефона не справился бы "
                            + "с их отрисовкой. Сама оптимальная траектория рассчитана полностью. "
                            + "Увеличьте шаг по высоте или по скорости, чтобы увидеть таблицу "
                            + "функции Беллмана.", nodes));
        }
        response.notes = notes;
        return response;
    }

    private static Double[][] toNullableMatrix(double[][] source) {
        Double[][] result = new Double[source.length][];
        for (int k = 0; k < source.length; k++) {
            result[k] = new Double[source[k].length];
            for (int i = 0; i < source[k].length; i++) {
                double value = source[k][i];
                result[k][i] = Double.isFinite(value) ? Double.valueOf(value) : null;
            }
        }
        return result;
    }

    public String getCriterion() {
        return criterion;
    }

    public String getCriterionTitle() {
        return criterionTitle;
    }

    public String getCriterionUnit() {
        return criterionUnit;
    }

    public double getOptimalValue() {
        return optimalValue;
    }

    public double getTotalTime() {
        return totalTime;
    }

    public double getTotalFuel() {
        return totalFuel;
    }

    public double getTotalDistance() {
        return totalDistance;
    }

    public List<TrajectoryPoint> getTrajectory() {
        return trajectory;
    }

    public double[] getAltitudes() {
        return altitudes;
    }

    public double[] getSpeeds() {
        return speeds;
    }

    public Double[][] getValueFunction() {
        return valueFunction;
    }

    public boolean[][] getFeasible() {
        return feasible;
    }

    public int getFeasibleNodes() {
        return feasibleNodes;
    }

    public long getEvaluatedTransitions() {
        return evaluatedTransitions;
    }

    public long getSolveTimeMillis() {
        return solveTimeMillis;
    }

    public List<String> getNotes() {
        return notes;
    }
}
