package ru.mashinka.bellman.core.dp;

import java.util.ArrayList;
import java.util.List;

import ru.mashinka.bellman.core.atmosphere.Atmosphere;
import ru.mashinka.bellman.core.model.Aircraft;
import ru.mashinka.bellman.core.model.ClimbTask;
import ru.mashinka.bellman.core.model.Criterion;
import ru.mashinka.bellman.core.perf.FlightPerformance;

// алгоритм Беллмана для набора крейсерской высоты
// состояние - узел сетки (H, V), этап - переход на следующий уровень,
// управление - выбор скорости на нём
//
// f_N(i) = 0
// f_k(i) = min по j [ c(i,j) + f_k+1(j) ],  k = N-1 ... 0
public class BellmanSolver {

    // предельное число высотных уровней — защита от неподъёмной по времени сетки
    private static final int MAX_LEVELS = 400;
    // предельное число узлов по скорости
    private static final int MAX_SPEEDS = 800;
    // предельное число просматриваемых переходов
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

    // выполняет расчёт
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

        // заполняем обе таблицы значениями "пусто". java сама обнулила бы массивы,
        // но ноль здесь использовать нельзя - ниже объясняю почему
        //
        // бесконечность в f: мы ищем МИНИМУМ, а бесконечность для минимума
        // нейтральна - любое реальное значение её побьёт. поставь тут ноль, и узел,
        // откуда самолёт вообще не поднимется, выглядел бы как "до крейсерской
        // высоты отсюда даром". и это враньё не осталось бы на месте: на следующем
        // шаге прогонки его подхватит строка cost(segment) + tail и растащит
        // по всей сетке. заодно бесконечность работает меткой недостижимости -
        // её ловит isInfinite ниже и прочерки в таблице на странице
        //
        // -1 в policy: там лежит НОМЕР скорости в массиве speeds, а ноль - вполне
        // законный номер (speeds[0], самая первая скорость сетки). нужен индекс,
        // которого быть не может, иначе не отличишь "выбрана speeds[0]"
        // от "здесь ничего не выбрано". проверяется в restoreTrajectory: nextIndex < 0
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

    // высотные уровни расчёта
    private double[] buildAltitudeLevels() {
        double span = task.getCruiseAltitude() - task.getStartAltitude();

        // вычитаем 1e-9 из-за double: 11000/500 в машинной арифметике может дать
        // 22.000000000000004, и ceil округлит вверх до 23 этапов вместо 22.
        // поправка меньше любого осмысленного шага, на настоящий результат не влияет
        int steps = (int) Math.ceil(span / task.getAltitudeStep() - 1e-9);

        double[] altitudes = new double[steps + 1];
        for (int k = 0; k <= steps; k++) {
            altitudes[k] = task.getStartAltitude() + k * task.getAltitudeStep();
        }

        // последний уровень подгоняем ровно под заказанную высоту. если она не делится
        // на шаг нацело (11 300 при шаге 500), цикл выше загнал бы нас на 11 500 -
        // то есть выше, чем просили. последний участок просто получается короче
        altitudes[steps] = task.getCruiseAltitude();
        return altitudes;
    }

    // сетка истинных скоростей, одна на все высоты
    private double[] buildSpeedGrid() {
        // тут наоборот прибавляем 1e-9: floor любит срезать вниз на той же
        // машинной погрешности и терять последний узел сетки
        int count = (int) Math.floor((task.getSpeedMax() - task.getSpeedMin())
                / task.getSpeedStep() + 1e-9) + 1;

        double[] speeds = new double[count];
        for (int i = 0; i < count; i++) {
            speeds[i] = task.getSpeedMin() + i * task.getSpeedStep();
        }
        return speeds;
    }

    // страховка от неподъёмной сетки. сложность алгоритма O(N·M²), то есть растёт
    // квадратично по числу скоростей: уменьшил шаг по скорости вдвое - получил вчетверо
    // больше работы. без этой проверки пользователь мог бы вписать шаг 0,1 м/с
    // и подвесить сервер на несколько минут
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

    // маска допустимости узлов: скорость должна лежать между сваливанием и ограничением
    //
    // считаем её один раз заранее, а не проверяем внутри прогонки. в прогонке три
    // вложенных цикла, и каждая проверка выполнялась бы десятки тысяч раз, дёргая
    // плотность воздуха и скорость звука. тут же это 851 вычисление, и всё
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

    // граничное условие обратной прогонки: на крейсерской высоте затраты равны нулю
    // Если задано крейсерское число Маха, допускаются только соответствующие ему узлы.
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

    // ЯДРО ВСЕГО РАСЧЁТА. обратная прогонка, те самые три вложенных цикла:
    //
    //     f_k(i) = min по j [ c(i,j) + f_k+1(j) ]
    //
    // идём с конца, потому что граничное условие известно именно там: на крейсерской
    // высоте платить больше не за что, f_N = 0. когда обрабатываем уровень k, весь
    // уровень k+1 уже посчитан, и минимум берём по готовым числам, а не по обещаниям.
    // это и есть принцип оптимальности Беллмана: хвост оптимального пути сам оптимален
    private long runBackwardRecursion(double[] altitudes, double[] speeds, boolean[][] feasible,
                                      double[][] valueFunction, int[][] policy) {
        long evaluated = 0;
        double maxSpeedChange = task.getMaxSpeedChangePerStep();

        for (int k = altitudes.length - 2; k >= 0; k--) {     // этапы, с предпоследнего вниз
            for (int i = 0; i < speeds.length; i++) {         // откуда стартуем
                if (!feasible[k][i]) {
                    continue;                                 // скорость вне допустимого диапазона
                }
                double best = Double.POSITIVE_INFINITY;
                int bestJ = -1;

                for (int j = 0; j < speeds.length; j++) {     // куда переходим = управление
                    if (!feasible[k + 1][j]) {
                        continue;
                    }

                    double tail = valueFunction[k + 1][j];    // f_k+1(j), уже готовое число

                    // формально эта проверка не нужна: бесконечность плюс что угодно
                    // даст бесконечность, и такой вариант минимум всё равно не выиграет.
                    // но без неё мы бы ради заведомо мёртвого узла считали buildSegment,
                    // а там атмосфера, тяга, сопротивление, расход - самое дорогое место
                    // во всём алгоритме. это отсечение, а не логика
                    if (Double.isInfinite(tail)) {
                        continue;
                    }

                    // эксплуатационное ограничение: за один эшелон скорость нельзя
                    // менять как попало. допуск 1e-9 - потому что сравниваем double,
                    // и ровно 30.0 может оказаться 30.000000000000004
                    if (Math.abs(speeds[j] - speeds[i]) > maxSpeedChange + 1e-9) {
                        continue;
                    }
                    evaluated++;                              // счётчик для статистики на странице

                    Segment segment = buildSegment(altitudes[k], speeds[i],
                            altitudes[k + 1], speeds[j]);
                    if (segment == null) {
                        continue;                             // физически такой переход невозможен
                    }

                    double candidate = cost(segment) + tail;  // вот оно, уравнение Беллмана
                    if (candidate < best) {
                        best = candidate;
                        bestJ = j;                            // запоминаем, чем именно получен минимум
                    }
                }

                // если ни один переход не подошёл, best так и остался бесконечностью,
                // а bestJ минус единицей - узел помечен недостижимым, и на следующем
                // витке прогонки его отбросит проверка isInfinite выше
                valueFunction[k][i] = best;
                policy[k][i] = bestJ;
            }
        }
        return evaluated;
    }

    // стоимость перехода из состояния (h1, v1) в состояние (h2, v2) в выбранном
    // критерии оптимальности — величина c_k(i, j) из уравнения Беллмана.
    public Double transitionCost(double h1, double v1, double h2, double v2) {
        Segment segment = buildSegment(h1, v1, h2, v2);
        return segment == null ? null : cost(segment);
    }

    // ВСЯ ФИЗИКА ЗАДАЧИ. считает, во что обойдётся переход (h1,v1) -> (h2,v2)
    //
    // возвращает null, если переход невозможен. именно null, а не исключение:
    // невозможный переход тут дело обычное, на большой сетке их тысячи, и это
    // штатная ветка работы, а не авария. исключениями мы бы управляли потоком
    private Segment buildSegment(double h1, double v1, double h2, double v2) {
        // энергетическая высота: сколько бы самолёт набрал, разменяв всю скорость.
        // она нужна потому, что при наборе высота и скорость меняются одновременно,
        // и считать их порознь нельзя - машина может разогнаться за счёт снижения
        // и наоборот. Hэ сводит обе формы энергии к одной координате
        double energy1 = h1 + v1 * v1 / (2.0 * Atmosphere.G);
        double energy2 = h2 + v2 * v2 / (2.0 * Atmosphere.G);
        double energyGain = energy2 - energy1;

        // сравниваем с 1e-6, а не с нулём: разность двух близких double почти
        // никогда не бывает ровно нулём, а делить на неё мы будем ниже
        if (energyGain <= 1e-6) {
            return null;                    // энергия не растёт - это не набор
        }

        double rate1 = performance.energyRate(h1, v1);
        double rate2 = performance.energyRate(h2, v2);
        if (rate1 <= 1e-6 || rate2 <= 1e-6) {
            return null;                    // тяга не превышает сопротивление, разгоняться нечем
        }

        // dt = ∫ dHэ / (dHэ/dt). ВАЖНО: трапеция берётся по 1/rate, а не по rate.
        // подынтегральная функция тут обратная, потому что интегрируем время,
        // а не энергию. если усреднить сами скорости роста энергии, ответ
        // получится заметно оптимистичнее правды
        double time = energyGain * 0.5 * (1.0 / rate1 + 1.0 / rate2);

        // rate мог оказаться исчезающе малым, тогда time уедет в бесконечность
        // или в NaN. проверка !(time > 0) ловит и NaN тоже: с NaN любое сравнение ложно
        if (!(time > 0) || Double.isInfinite(time) || Double.isNaN(time)) {
            return null;
        }

        double rateOfClimb = (h2 - h1) / time;
        if (rateOfClimb < task.getMinRateOfClimb()) {
            return null;                    // ползём вверх слишком вяло, у потолка так и бывает
        }

        double fuel = 0.5 * (performance.fuelFlow(h1, v1) + performance.fuelFlow(h2, v2)) * time;

        // скорость V направлена по траектории, а не по горизонту. горизонтальную
        // составляющую достаём теоремой Пифагора: Vx = √(V² − Vy²).
        // max(0, ...) - страховка от отрицательного числа под корнем
        double meanSpeed = 0.5 * (v1 + v2);
        double horizontalSpeed = Math.sqrt(
                Math.max(0.0, meanSpeed * meanSpeed - rateOfClimb * rateOfClimb));
        double distance = horizontalSpeed * time;

        return new Segment(time, fuel, distance, rateOfClimb);
    }

    // ВСЯ разница между тремя критериями - вот эти четыре строки. алгоритм выше
    // ничего о них не знает: он просто минимизирует то число, которое тут вернут
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

    // ищем, с какого узла стартовать. пользователь вводит скорость числом, а нам
    // нужен индекс в сетке - округляем до ближайшего узла
    //
    // если из него набор невозможен, берём ближайший рабочий и пишем об этом
    // в notes. молча подменять исходные данные нельзя: человек должен видеть,
    // что посчитали не совсем то, что он заказывал
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

    // прямой проход: идём снизу вверх и на каждом уровне спрашиваем у policy,
    // какую скорость выбрать. сам минимум уже найден прогонкой, тут мы только
    // разворачиваем ответ в список точек и попутно накапливаем время, топливо,
    // дальность - ради них и хранили policy, иначе пришлось бы искать путь заново
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

    // параметры одного перехода между узлами сетки
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
