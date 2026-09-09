package ru.mashinka.bellman.core.dp;

import java.util.ArrayList;
import java.util.List;

import ru.mashinka.bellman.core.atmosphere.Atmosphere;
import ru.mashinka.bellman.core.model.Aircraft;
import ru.mashinka.bellman.core.model.ClimbTask;
import ru.mashinka.bellman.core.model.Criterion;
import ru.mashinka.bellman.core.perf.FlightPerformance;

/**
 * Решение задачи о наборе крейсерской высоты методом динамического программирования Р. Беллмана.
 *
 * <h2>Постановка</h2>
 * Пространство состояний дискретизируется сеткой (H, V): высотные уровни
 * H₀, H₁, ..., H_N от начальной высоты до крейсерской и набор истинных скоростей
 * V₀, V₁, ..., V_{M−1}. Этап k — переход с уровня H_k на уровень H_{k+1},
 * управление — выбор скорости на следующем уровне.
 *
 * <h2>Стоимость перехода</h2>
 * Используется энергетический метод. Энергетическая высота Hэ = H + V²/(2g)
 * растёт со скоростью dHэ/dt = V·(P − Q)/(m·g). Время перехода находится
 * интегрированием dt = dHэ / (dHэ/dt) по правилу трапеций,
 * расход топлива — как средний секундный расход, умноженный на время.
 *
 * <h2>Уравнение Беллмана</h2>
 * <pre>
 *     f_N(i) = 0                                            (крейсерская высота достигнута)
 *     f_k(i) = min over j [ c_k(i, j) + f_{k+1}(j) ]        k = N−1, ..., 0
 * </pre>
 * Обратная (от конца к началу) прогонка даёт функцию Беллмана f и оптимальное
 * управление; прямой проход по запомненным управлениям восстанавливает
 * оптимальную траекторию набора.
 */
public class BellmanSolver {

    /** Предельное число высотных уровней — защита от неподъёмной по времени сетки. */
    private static final int MAX_LEVELS = 400;
    /** Предельное число узлов по скорости. */
    private static final int MAX_SPEEDS = 800;
    /** Предельное число просматриваемых переходов. */
    private static final long MAX_TRANSITIONS = 50_000_000L;

    private final Aircraft aircraft;
    private final ClimbTask task;
    private final FlightPerformance performance;

    public BellmanSolver(Aircraft aircraft, ClimbTask task) {
        this.aircraft = aircraft;
        this.task = task;
        this.performance = new FlightPerformance(aircraft);
    }

    public FlightPerformance getPerformance() {
        return performance;
    }

    /**
     * Выполняет расчёт.
     *
     * @throws IllegalArgumentException при некорректных исходных данных
     * @throws ClimbCalculationException если набор крейсерской высоты невозможен
     */
    public ClimbSolution solve() {
        aircraft.validate();
        task.validate();

        long startedAt = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();

        double[] altitudes = buildAltitudeLevels();
        double[] speeds = buildSpeedGrid();
        int levels = altitudes.length;
        int speedCount = speeds.length;

        checkGridSize(levels, speedCount);

        boolean[][] feasible = buildFeasibilityMask(altitudes, speeds);
        int feasibleNodes = countFeasibleNodes(feasible);
        if (feasibleNodes == 0) {
            throw new ClimbCalculationException(
                    "Ни один узел сетки не удовлетворяет ограничениям по скорости. "
                            + "Расширьте диапазон скоростей или уменьшите массу самолёта.");
        }

        double[][] valueFunction = new double[levels][speedCount];
        int[][] policy = new int[levels][speedCount];
        for (int k = 0; k < levels; k++) {
            for (int i = 0; i < speedCount; i++) {
                valueFunction[k][i] = Double.POSITIVE_INFINITY;
                policy[k][i] = -1;
            }
        }

        initTerminalLevel(valueFunction, feasible, altitudes, speeds, notes);

        long evaluatedTransitions = runBackwardRecursion(
                altitudes, speeds, feasible, valueFunction, policy);

        int startIndex = resolveStartIndex(speeds, valueFunction[0], notes);

        List<TrajectoryPoint> trajectory =
                restoreTrajectory(altitudes, speeds, policy, startIndex);

        TrajectoryPoint last = trajectory.get(trajectory.size() - 1);

        return new ClimbSolution(
                task.getCriterion(),
                valueFunction[0][startIndex],
                trajectory,
                last.getTime(),
                last.getFuel(),
                last.getDistance(),
                altitudes,
                speeds,
                valueFunction,
                feasible,
                feasibleNodes,
                evaluatedTransitions,
                System.currentTimeMillis() - startedAt,
                notes);
    }

    // ------------------------------------------------------------------ сетка

    /** Высотные уровни: равномерный шаг, последний уровень точно равен крейсерской высоте. */
    private double[] buildAltitudeLevels() {
        double span = task.getCruiseAltitude() - task.getStartAltitude();
        int steps = (int) Math.ceil(span / task.getAltitudeStep() - 1e-9);
        double[] altitudes = new double[steps + 1];
        for (int k = 0; k <= steps; k++) {
            altitudes[k] = task.getStartAltitude() + k * task.getAltitudeStep();
        }
        altitudes[steps] = task.getCruiseAltitude();
        return altitudes;
    }

    /** Равномерная сетка истинных скоростей. */
    private double[] buildSpeedGrid() {
        int count = (int) Math.floor((task.getSpeedMax() - task.getSpeedMin())
                / task.getSpeedStep() + 1e-9) + 1;
        double[] speeds = new double[count];
        for (int i = 0; i < count; i++) {
            speeds[i] = task.getSpeedMin() + i * task.getSpeedStep();
        }
        return speeds;
    }

    private void checkGridSize(int levels, int speedCount) {
        if (levels - 1 > MAX_LEVELS) {
            throw new IllegalArgumentException("Слишком мелкий шаг по высоте: получилось "
                    + (levels - 1) + " этапов при допустимых " + MAX_LEVELS
                    + ". Увеличьте шаг по высоте.");
        }
        if (speedCount > MAX_SPEEDS) {
            throw new IllegalArgumentException("Слишком мелкий шаг по скорости: получилось "
                    + speedCount + " узлов при допустимых " + MAX_SPEEDS
                    + ". Увеличьте шаг по скорости.");
        }
        long transitions = (long) (levels - 1) * speedCount * speedCount;
        if (transitions > MAX_TRANSITIONS) {
            throw new IllegalArgumentException("Сетка слишком подробная: " + transitions
                    + " переходов. Увеличьте шаг по высоте или по скорости.");
        }
    }

    /** Узел допустим, если скорость лежит между минимально и максимально допустимой на этой высоте. */
    private boolean[][] buildFeasibilityMask(double[] altitudes, double[] speeds) {
        boolean[][] feasible = new boolean[altitudes.length][speeds.length];
        for (int k = 0; k < altitudes.length; k++) {
            double vMin = performance.minSpeed(altitudes[k]);
            double vMax = performance.maxSpeed(altitudes[k]);
            for (int i = 0; i < speeds.length; i++) {
                feasible[k][i] = speeds[i] >= vMin && speeds[i] <= vMax;
            }
        }
        return feasible;
    }

    private int countFeasibleNodes(boolean[][] feasible) {
        int count = 0;
        for (boolean[] row : feasible) {
            for (boolean value : row) {
                if (value) {
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------- уравнение Беллмана

    /**
     * Граничное условие обратной прогонки: на крейсерской высоте затраты равны нулю.
     * Если задано крейсерское число Маха, допускаются только соответствующие ему узлы.
     */
    private void initTerminalLevel(double[][] valueFunction, boolean[][] feasible,
                                   double[] altitudes, double[] speeds, List<String> notes) {
        int last = altitudes.length - 1;
        double cruiseAltitude = altitudes[last];
        Double targetMach = task.getTargetCruiseMach();

        int allowed = 0;
        for (int i = 0; i < speeds.length; i++) {
            if (!feasible[last][i]) {
                continue;
            }
            if (targetMach != null) {
                double mach = performance.mach(cruiseAltitude, speeds[i]);
                if (Math.abs(mach - targetMach) > task.getCruiseMachTolerance()) {
                    continue;
                }
            }
            valueFunction[last][i] = 0.0;
            allowed++;
        }

        if (allowed == 0) {
            if (targetMach != null) {
                throw new ClimbCalculationException(String.format(
                        "На крейсерской высоте %.0f м нет ни одного узла сетки с числом Маха "
                                + "M = %.3f ± %.3f. Расширьте диапазон скоростей, уменьшите шаг "
                                + "по скорости или увеличьте допуск по числу Маха.",
                        cruiseAltitude, targetMach, task.getCruiseMachTolerance()));
            }
            throw new ClimbCalculationException(String.format(
                    "На крейсерской высоте %.0f м нет ни одной допустимой скорости: "
                            + "высота лежит выше практического потолка при данной массе.",
                    cruiseAltitude));
        }
        if (targetMach != null) {
            notes.add(String.format(
                    "Конец набора закреплён за числом Маха M = %.3f ± %.3f: "
                            + "подходящих узлов на крейсерской высоте — %d.",
                    targetMach, task.getCruiseMachTolerance(), allowed));
        }
    }

    /**
     * Обратная прогонка: f_k(i) = min_j [ c_k(i, j) + f_{k+1}(j) ].
     * Попутно запоминается оптимальное управление policy[k][i] — номер скорости
     * на следующем уровне.
     *
     * @return число просмотренных переходов
     */
    private long runBackwardRecursion(double[] altitudes, double[] speeds, boolean[][] feasible,
                                      double[][] valueFunction, int[][] policy) {
        long evaluated = 0;
        double maxSpeedChange = task.getMaxSpeedChangePerStep();

        for (int k = altitudes.length - 2; k >= 0; k--) {
            for (int i = 0; i < speeds.length; i++) {
                if (!feasible[k][i]) {
                    continue;
                }
                double best = Double.POSITIVE_INFINITY;
                int bestJ = -1;

                for (int j = 0; j < speeds.length; j++) {
                    if (!feasible[k + 1][j]) {
                        continue;
                    }
                    double tail = valueFunction[k + 1][j];
                    if (Double.isInfinite(tail)) {
                        continue;
                    }
                    if (Math.abs(speeds[j] - speeds[i]) > maxSpeedChange + 1e-9) {
                        continue;
                    }
                    evaluated++;

                    Segment segment = buildSegment(altitudes[k], speeds[i],
                            altitudes[k + 1], speeds[j]);
                    if (segment == null) {
                        continue;
                    }
                    double candidate = cost(segment) + tail;
                    if (candidate < best) {
                        best = candidate;
                        bestJ = j;
                    }
                }

                valueFunction[k][i] = best;
                policy[k][i] = bestJ;
            }
        }
        return evaluated;
    }

    /**
     * Стоимость перехода из состояния (h1, v1) в состояние (h2, v2) в выбранном
     * критерии оптимальности — величина c_k(i, j) из уравнения Беллмана.
     *
     * @return стоимость перехода либо {@code null}, если переход невозможен
     */
    public Double transitionCost(double h1, double v1, double h2, double v2) {
        Segment segment = buildSegment(h1, v1, h2, v2);
        return segment == null ? null : cost(segment);
    }

    /**
     * Параметры перехода из состояния (h1, v1) в состояние (h2, v2)
     * или {@code null}, если такой переход физически невозможен.
     */
    private Segment buildSegment(double h1, double v1, double h2, double v2) {
        double energy1 = h1 + v1 * v1 / (2.0 * Atmosphere.G);
        double energy2 = h2 + v2 * v2 / (2.0 * Atmosphere.G);
        double energyGain = energy2 - energy1;
        if (energyGain <= 1e-6) {
            // энергетическая высота обязана расти: иначе это не набор
            return null;
        }

        double rate1 = performance.energyRate(h1, v1);
        double rate2 = performance.energyRate(h2, v2);
        if (rate1 <= 1e-6 || rate2 <= 1e-6) {
            // избытка тяги нет — самолёт не может увеличивать энергию
            return null;
        }

        // dt = ∫ dHэ / (dHэ/dt), интеграл берётся по правилу трапеций по величине 1/(dHэ/dt)
        double time = energyGain * 0.5 * (1.0 / rate1 + 1.0 / rate2);
        if (!(time > 0) || Double.isInfinite(time) || Double.isNaN(time)) {
            return null;
        }

        double rateOfClimb = (h2 - h1) / time;
        if (rateOfClimb < task.getMinRateOfClimb()) {
            return null;
        }

        double fuel = 0.5 * (performance.fuelFlow(h1, v1) + performance.fuelFlow(h2, v2)) * time;

        double meanSpeed = 0.5 * (v1 + v2);
        double horizontalSpeed = Math.sqrt(
                Math.max(0.0, meanSpeed * meanSpeed - rateOfClimb * rateOfClimb));
        double distance = horizontalSpeed * time;

        return new Segment(time, fuel, distance, rateOfClimb);
    }

    /** Стоимость перехода в выбранном критерии оптимальности. */
    private double cost(Segment segment) {
        Criterion criterion = task.getCriterion();
        if (criterion == Criterion.TIME) {
            return segment.time;
        }
        if (criterion == Criterion.FUEL) {
            return segment.fuel;
        }
        return segment.fuel + task.getCostIndexKgPerSecond() * segment.time;
    }

    // ------------------------------------------------ восстановление траектории

    /**
     * Номер узла сетки, соответствующего начальной скорости. Если из него набор
     * невозможен, берётся ближайший узел, из которого решение существует.
     */
    private int resolveStartIndex(double[] speeds, double[] valueAtStart, List<String> notes) {
        int nearest = (int) Math.round((task.getStartSpeed() - task.getSpeedMin())
                / task.getSpeedStep());
        nearest = Math.max(0, Math.min(speeds.length - 1, nearest));

        if (Math.abs(speeds[nearest] - task.getStartSpeed()) > 1e-6) {
            notes.add(String.format(
                    "Начальная скорость %.1f м/с не попадает в узел сетки и округлена до %.1f м/с.",
                    task.getStartSpeed(), speeds[nearest]));
        }

        if (!Double.isInfinite(valueAtStart[nearest])) {
            return nearest;
        }

        int fallback = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < speeds.length; i++) {
            if (Double.isInfinite(valueAtStart[i])) {
                continue;
            }
            int distance = Math.abs(i - nearest);
            if (distance < bestDistance) {
                bestDistance = distance;
                fallback = i;
            }
        }
        if (fallback < 0) {
            throw new ClimbCalculationException(
                    "Набор крейсерской высоты невозможен ни при одной начальной скорости: "
                            + "избытка тяги не хватает либо ограничения по скорости разрывают "
                            + "траекторию. Проверьте массу, тягу и границы диапазона скоростей.");
        }
        notes.add(String.format(
                "При начальной скорости %.1f м/с набор невозможен, расчёт выполнен "
                        + "от ближайшей допустимой скорости %.1f м/с.",
                speeds[nearest], speeds[fallback]));
        return fallback;
    }

    /** Прямой проход по оптимальному управлению — та самая оптимальная траектория. */
    private List<TrajectoryPoint> restoreTrajectory(double[] altitudes, double[] speeds,
                                                    int[][] policy, int startIndex) {
        List<TrajectoryPoint> trajectory = new ArrayList<>();

        double time = 0.0;
        double fuel = 0.0;
        double distance = 0.0;

        int index = startIndex;
        trajectory.add(createPoint(0, altitudes[0], speeds[index],
                0.0, 0.0, 0.0, 0.0, time, fuel, distance));

        for (int k = 0; k < altitudes.length - 1; k++) {
            int nextIndex = policy[k][index];
            if (nextIndex < 0) {
                throw new ClimbCalculationException(String.format(
                        "Оптимальное управление не определено на высоте %.0f м — "
                                + "траектория обрывается. Проверьте исходные данные.",
                        altitudes[k]));
            }
            Segment segment = buildSegment(altitudes[k], speeds[index],
                    altitudes[k + 1], speeds[nextIndex]);
            if (segment == null) {
                throw new ClimbCalculationException(String.format(
                        "Не удалось восстановить переход с высоты %.0f м на %.0f м.",
                        altitudes[k], altitudes[k + 1]));
            }

            time += segment.time;
            fuel += segment.fuel;
            distance += segment.distance;
            index = nextIndex;

            trajectory.add(createPoint(k + 1, altitudes[k + 1], speeds[index],
                    segment.rateOfClimb, segment.time, segment.fuel, segment.distance,
                    time, fuel, distance));
        }
        return trajectory;
    }

    private TrajectoryPoint createPoint(int step, double altitude, double speed,
                                        double rateOfClimb, double segmentTime, double segmentFuel,
                                        double segmentDistance, double time, double fuel,
                                        double distance) {
        double thrust = performance.thrust(altitude, speed);
        double drag = performance.drag(altitude, speed);
        return new TrajectoryPoint(
                step,
                altitude,
                speed,
                performance.mach(altitude, speed),
                speed * Math.sqrt(Atmosphere.relativeDensity(altitude)),
                altitude + speed * speed / (2.0 * Atmosphere.G),
                performance.liftCoefficient(altitude, speed),
                thrust,
                drag,
                thrust - drag,
                performance.fuelFlow(altitude, speed),
                performance.energyRate(altitude, speed),
                rateOfClimb,
                segmentTime,
                segmentFuel,
                segmentDistance,
                time,
                fuel,
                distance);
    }

    /** Параметры одного перехода между узлами сетки. */
    private static final class Segment {
        private final double time;
        private final double fuel;
        private final double distance;
        private final double rateOfClimb;

        private Segment(double time, double fuel, double distance, double rateOfClimb) {
            this.time = time;
            this.fuel = fuel;
            this.distance = distance;
            this.rateOfClimb = rateOfClimb;
        }
    }
}
