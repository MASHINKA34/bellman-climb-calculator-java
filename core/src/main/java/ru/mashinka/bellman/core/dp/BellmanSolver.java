package ru.mashinka.bellman.core.dp;

import java.util.ArrayList;
import java.util.List;

import ru.mashinka.bellman.core.atmosphere.Atmosphere;
import ru.mashinka.bellman.core.model.Aircraft;
import ru.mashinka.bellman.core.model.ClimbTask;
import ru.mashinka.bellman.core.model.Criterion;
import ru.mashinka.bellman.core.perf.FlightPerformance;

// алгоритм Беллмана для набора крейсерской высоты
// f_N(i) = 0
// f_k(i) = min по j [ c(i,j) + f_k+1(j) ],  k = N-1 ... 0
public class BellmanSolver {

    // ограничения размера сетки, чтобы расчёт не подвис
    private static final int MAX_LEVELS = 400;
    private static final int MAX_SPEEDS = 800;
    private static final long MAX_TRANSITIONS = 50_000_000L;

    private final Aircraft aircraft;
    private final ClimbTask task;
    private final FlightPerformance performance;

    // счётчик переходов для рекурсивного варианта
    private long recursiveTransitions;

    public BellmanSolver(Aircraft aircraft, ClimbTask task) {
        this.aircraft = aircraft;
        this.task = task;
        this.performance = new FlightPerformance(aircraft);
    }

    public FlightPerformance getPerformance() {
        return performance;
    }

    //#главный - точка входа, отсюда начинать показ
    public ClimbSolution solve() {
        return compute(false);
    }

    //#рекурсия-вариант - то же уравнение, но рекурсией с мемоизацией.
    // ответ совпадает с solve(), это проверяет тест recursiveMatchesIterative
    public ClimbSolution solveRecursive() {
        return compute(true);
    }

    // общая часть обоих способов
    private ClimbSolution compute(boolean recursive) {
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

        //#массивы - две таблицы "уровни x скорости": f хранит стоимость, policy - выбор
        //#бесконечность - нейтральна для минимума; ноль дал бы ложное "долетим даром"
        //#минус-один - в policy лежит индекс скорости, а 0 это настоящий индекс speeds[0]
        double[][] valueFunction = new double[levels][speedCount];
        int[][] policy = new int[levels][speedCount];
        for (int k = 0; k < levels; k++) {
            for (int i = 0; i < speedCount; i++) {
                valueFunction[k][i] = Double.POSITIVE_INFINITY;
                policy[k][i] = -1;
            }
        }

        initTerminalLevel(valueFunction, feasible, altitudes, speeds, notes);

        // развилка: итеративно или рекурсивно, таблицы заполняются одни и те же
        long evaluatedTransitions = recursive
                ? runRecursiveDescent(altitudes, speeds, feasible, valueFunction, policy)
                : runBackwardRecursion(altitudes, speeds, feasible, valueFunction, policy);

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

    private double[] buildAltitudeLevels() {
        double span = task.getCruiseAltitude() - task.getStartAltitude();

        //#вещественные - 1e-9 гасит погрешность double: 11000/500 может дать 22.0000000001
        int steps = (int) Math.ceil(span / task.getAltitudeStep() - 1e-9);

        double[] altitudes = new double[steps + 1];
        for (int k = 0; k <= steps; k++) {
            altitudes[k] = task.getStartAltitude() + k * task.getAltitudeStep();
        }

        // последний уровень ровно на крейсерской, даже если шаг не делится нацело
        altitudes[steps] = task.getCruiseAltitude();
        return altitudes;
    }

    private double[] buildSpeedGrid() {
        // +1e-9, чтобы floor не потерял последний узел из-за погрешности
        int count = (int) Math.floor((task.getSpeedMax() - task.getSpeedMin())
                / task.getSpeedStep() + 1e-9) + 1;

        double[] speeds = new double[count];
        for (int i = 0; i < count; i++) {
            speeds[i] = task.getSpeedMin() + i * task.getSpeedStep();
        }
        return speeds;
    }

    //#сложность - O(N*M^2) по времени, O(N*M) по памяти; перебор был бы O(M^N)
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

    // допустимые узлы считаем один раз заранее, а не в каждом витке прогонки
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

    // граничное условие: на крейсерской высоте f = 0
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
                            + "подходящих узлов на крейсерской высоте - %d.",
                    targetMach, task.getCruiseMachTolerance(), allowed));
        }
    }

    //#рекурсия - здесь её нет, это циклы; рекурсивный вариант - valueAt ниже
    //#циклы - три вложенных: этапы -> состояния -> управления
    //#принцип-оптимальности - идём с конца: хвост оптимального пути сам оптимален
    private long runBackwardRecursion(double[] altitudes, double[] speeds, boolean[][] feasible,
                                      double[][] valueFunction, int[][] policy) {
        long evaluated = 0;
        double maxSpeedChange = task.getMaxSpeedChangePerStep();

        for (int k = altitudes.length - 2; k >= 0; k--) {     // этапы с конца
            for (int i = 0; i < speeds.length; i++) {         // откуда
                if (!feasible[k][i]) {
                    continue;
                }
                double best = Double.POSITIVE_INFINITY;
                int bestJ = -1;

                for (int j = 0; j < speeds.length; j++) {     // куда = управление
                    if (!feasible[k + 1][j]) {
                        continue;
                    }

                    double tail = valueFunction[k + 1][j];    // готовое f_k+1(j)

                    // отсечение: ради недостижимого узла дорогой buildSegment не считаем
                    if (Double.isInfinite(tail)) {
                        continue;
                    }

                    // ограничение на изменение скорости за один эшелон
                    if (Math.abs(speeds[j] - speeds[i]) > maxSpeedChange + 1e-9) {
                        continue;
                    }
                    evaluated++;

                    Segment segment = buildSegment(altitudes[k], speeds[i],
                            altitudes[k + 1], speeds[j]);
                    if (segment == null) {
                        continue;                             // переход невозможен
                    }

                    //#рекуррентность - f_k через готовое f_k+1, это и есть уравнение Беллмана
                    double candidate = cost(segment) + tail;
                    if (candidate < best) {
                        best = candidate;
                        bestJ = j;
                    }
                }

                // ничего не подошло - останутся бесконечность и -1, узел недостижим
                valueFunction[k][i] = best;
                policy[k][i] = bestJ;
            }
        }
        return evaluated;
    }

    // ------------------------------------------------- рекурсивный вариант

    //#рекурсия-настоящая - valueAt вызывает сам себя
    //#мемоизация - запоминаем посчитанные узлы, иначе работа растёт экспоненциально
    private long runRecursiveDescent(double[] altitudes, double[] speeds, boolean[][] feasible,
                                     double[][] valueFunction, int[][] policy) {
        // "уже посчитан" - отдельный флаг: бесконечность занята под "недостижим"
        boolean[][] solved = new boolean[altitudes.length][speeds.length];

        recursiveTransitions = 0;
        for (int i = 0; i < speeds.length; i++) {
            if (feasible[0][i]) {
                valueAt(0, i, altitudes, speeds, feasible, valueFunction, policy, solved);
            }
        }
        return recursiveTransitions;
    }

    // глубина рекурсии = число этапов, не больше MAX_LEVELS
    private double valueAt(int k, int i, double[] altitudes, double[] speeds,
                           boolean[][] feasible, double[][] valueFunction,
                           int[][] policy, boolean[][] solved) {
        // база рекурсии: крейсерская высота, значение уже известно
        if (k == altitudes.length - 1) {
            return valueFunction[k][i];
        }

        // мемоизация: в один узел приходят разные ветки спуска
        if (solved[k][i]) {
            return valueFunction[k][i];
        }
        solved[k][i] = true;

        if (!feasible[k][i]) {
            return valueFunction[k][i];
        }

        double best = Double.POSITIVE_INFINITY;
        int bestJ = -1;
        double maxSpeedChange = task.getMaxSpeedChangePerStep();

        for (int j = 0; j < speeds.length; j++) {
            if (!feasible[k + 1][j]) {
                continue;
            }
            if (Math.abs(speeds[j] - speeds[i]) > maxSpeedChange + 1e-9) {
                continue;
            }

            // рекурсивный вызов вместо готового числа из таблицы
            double tail = valueAt(k + 1, j, altitudes, speeds, feasible,
                    valueFunction, policy, solved);
            if (Double.isInfinite(tail)) {
                continue;
            }
            recursiveTransitions++;

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
        return best;
    }

    // стоимость одного перехода, нужна тесту с полным перебором
    public Double transitionCost(double h1, double v1, double h2, double v2) {
        Segment segment = buildSegment(h1, v1, h2, v2);
        return segment == null ? null : cost(segment);
    }

    //#физика - вся физика задачи в этом методе
    //#формулы - Hэ = H + V²/2g,  dHэ/dt = V(P−Q)/mg,  время по трапециям
    // null значит переход невозможен - это обычный случай, а не ошибка
    private Segment buildSegment(double h1, double v1, double h2, double v2) {
        // энергетическая высота учитывает сразу и высоту, и скорость
        double energy1 = h1 + v1 * v1 / (2.0 * Atmosphere.G);
        double energy2 = h2 + v2 * v2 / (2.0 * Atmosphere.G);
        double energyGain = energy2 - energy1;

        // 1e-6 вместо нуля: разность double почти никогда не бывает ровно нулём
        if (energyGain <= 1e-6) {
            return null;                    // энергия не растёт
        }

        double rate1 = performance.energyRate(h1, v1);
        double rate2 = performance.energyRate(h2, v2);
        if (rate1 <= 1e-6 || rate2 <= 1e-6) {
            return null;                    // нет избытка тяги
        }

        // трапеция по 1/rate, а не по rate: интегрируем время, а не энергию
        double time = energyGain * 0.5 * (1.0 / rate1 + 1.0 / rate2);

        // !(time > 0) ловит и NaN: с NaN любое сравнение ложно
        if (!(time > 0) || Double.isInfinite(time) || Double.isNaN(time)) {
            return null;
        }

        double rateOfClimb = (h2 - h1) / time;
        if (rateOfClimb < task.getMinRateOfClimb()) {
            return null;                    // набор слишком медленный
        }

        double fuel = 0.5 * (performance.fuelFlow(h1, v1) + performance.fuelFlow(h2, v2)) * time;

        // горизонтальная скорость по Пифагору: Vx = √(V² − Vy²)
        double meanSpeed = 0.5 * (v1 + v2);
        double horizontalSpeed = Math.sqrt(
                Math.max(0.0, meanSpeed * meanSpeed - rateOfClimb * rateOfClimb));
        double distance = horizontalSpeed * time;

        return new Segment(time, fuel, distance, rateOfClimb);
    }

    // вся разница между тремя критериями - в этих строках
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

    // стартовый узел по начальной скорости; если из него не долететь - ближайший рабочий
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

    //#восстановление - идём по policy снизу вверх и собираем траекторию
    //#коллекции - точки копятся в ArrayList<TrajectoryPoint>
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
                        "Оптимальное управление не определено на высоте %.0f м - "
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

    // один переход между узлами сетки
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
