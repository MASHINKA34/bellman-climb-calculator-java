package ru.mashinka.bellman.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.mashinka.bellman.core.dp.BellmanSolver;
import ru.mashinka.bellman.core.dp.ClimbCalculationException;
import ru.mashinka.bellman.core.dp.ClimbSolution;
import ru.mashinka.bellman.core.dp.TrajectoryPoint;
import ru.mashinka.bellman.core.model.Aircraft;
import ru.mashinka.bellman.core.model.ClimbTask;
import ru.mashinka.bellman.core.model.Criterion;

@DisplayName("Алгоритм Беллмана: набор крейсерской высоты")
class BellmanSolverTest {

    private static ClimbSolution solve(Criterion criterion) {
        ClimbTask task = ClimbTask.defaultTask();
        task.setCriterion(criterion);
        return new BellmanSolver(Aircraft.defaultAirliner(), task).solve();
    }

    @Test
    @DisplayName("Траектория начинается на исходной высоте и заканчивается на крейсерской")
    void trajectorySpansTheWholeClimb() {
        ClimbTask task = ClimbTask.defaultTask();
        ClimbSolution solution = new BellmanSolver(Aircraft.defaultAirliner(), task).solve();

        List<TrajectoryPoint> trajectory = solution.getTrajectory();
        assertEquals(task.getStartAltitude(), trajectory.get(0).getAltitude(), 1e-9);
        assertEquals(task.getCruiseAltitude(),
                trajectory.get(trajectory.size() - 1).getAltitude(), 1e-9);
        assertEquals(solution.getAltitudes().length, trajectory.size());
    }

    @Test
    @DisplayName("Высота вдоль траектории строго растёт, а время и топливо накапливаются")
    void trajectoryIsMonotonic() {
        ClimbSolution solution = solve(Criterion.FUEL);
        List<TrajectoryPoint> trajectory = solution.getTrajectory();

        for (int i = 1; i < trajectory.size(); i++) {
            TrajectoryPoint previous = trajectory.get(i - 1);
            TrajectoryPoint current = trajectory.get(i);
            assertTrue(current.getAltitude() > previous.getAltitude(),
                    "высота не растёт на шаге " + i);
            assertTrue(current.getTime() > previous.getTime(), "время не растёт на шаге " + i);
            assertTrue(current.getFuel() > previous.getFuel(), "топливо не растёт на шаге " + i);
            assertTrue(current.getRateOfClimb() > 0, "неположительная Vy на шаге " + i);
        }
    }

    @Test
    @DisplayName("Значение функции Беллмана совпадает с затратами по восстановленной траектории")
    void valueFunctionMatchesRestoredTrajectory() {
        for (Criterion criterion : Criterion.values()) {
            ClimbTask task = ClimbTask.defaultTask();
            task.setCriterion(criterion);
            ClimbSolution solution = new BellmanSolver(Aircraft.defaultAirliner(), task).solve();

            double expected;
            if (criterion == Criterion.TIME) {
                expected = solution.getTotalTime();
            } else if (criterion == Criterion.FUEL) {
                expected = solution.getTotalFuel();
            } else {
                expected = solution.getTotalFuel()
                        + task.getCostIndexKgPerSecond() * solution.getTotalTime();
            }

            assertEquals(expected, solution.getOptimalValue(), 1e-6,
                    "функция Беллмана разошлась с траекторией при критерии " + criterion);
        }
    }

    @Test
    @DisplayName("Каждый критерий действительно минимизирует свою величину")
    void eachCriterionOptimisesItsOwnValue() {
        ClimbSolution byTime = solve(Criterion.TIME);
        ClimbSolution byFuel = solve(Criterion.FUEL);

        assertTrue(byTime.getTotalTime() <= byFuel.getTotalTime() + 1e-6,
                "решение по времени оказалось медленнее решения по топливу");
        assertTrue(byFuel.getTotalFuel() <= byTime.getTotalFuel() + 1e-6,
                "решение по топливу оказалось прожорливее решения по времени");
    }

    @Test
    @DisplayName("Комбинированный критерий лежит между крайними режимами")
    void costIndexLiesBetweenExtremes() {
        ClimbSolution byTime = solve(Criterion.TIME);
        ClimbSolution byFuel = solve(Criterion.FUEL);
        ClimbSolution byCostIndex = solve(Criterion.COST_INDEX);

        assertTrue(byCostIndex.getTotalTime() >= byTime.getTotalTime() - 1e-6);
        assertTrue(byCostIndex.getTotalFuel() >= byFuel.getTotalFuel() - 1e-6);
    }

    @Test
    @DisplayName("На мелкой сетке решение совпадает с полным перебором всех траекторий")
    void matchesExhaustiveSearchOnSmallGrid() {
        ClimbTask task = new ClimbTask();
        task.setStartAltitude(0);
        task.setCruiseAltitude(2000);
        task.setAltitudeStep(1000);
        task.setSpeedMin(120);
        task.setSpeedMax(200);
        task.setSpeedStep(20);
        task.setStartSpeed(140);
        task.setMaxSpeedChangePerStep(100);
        task.setMinRateOfClimb(0);
        task.setTargetCruiseMach(null);
        task.setCriterion(Criterion.FUEL);

        BellmanSolver solver = new BellmanSolver(Aircraft.defaultAirliner(), task);
        ClimbSolution solution = solver.solve();

        double[] altitudes = solution.getAltitudes();
        double[] speeds = solution.getSpeeds();
        boolean[][] feasible = solution.getFeasible();
        int start = indexOf(speeds, 140);

        // полный перебор: все сочетания скоростей на промежуточном и конечном уровне
        double bruteForce = Double.POSITIVE_INFINITY;
        for (int j = 0; j < speeds.length; j++) {
            if (!feasible[1][j]) {
                continue;
            }
            Double first = solver.transitionCost(altitudes[0], speeds[start], altitudes[1], speeds[j]);
            if (first == null) {
                continue;
            }
            for (int k = 0; k < speeds.length; k++) {
                if (!feasible[2][k]) {
                    continue;
                }
                Double second = solver.transitionCost(altitudes[1], speeds[j], altitudes[2], speeds[k]);
                if (second == null) {
                    continue;
                }
                bruteForce = Math.min(bruteForce, first + second);
            }
        }

        assertTrue(Double.isFinite(bruteForce), "перебор не нашёл ни одной допустимой траектории");
        assertEquals(bruteForce, solution.getOptimalValue(), 1e-9,
                "динамическое программирование разошлось с полным перебором");
    }

    @Test
    @DisplayName("Недостижимая высота честно объявляется недостижимой")
    void unreachableAltitudeIsReported() {
        ClimbTask task = ClimbTask.defaultTask();
        task.setCruiseAltitude(19000);
        task.setSpeedMax(320);
        task.setTargetCruiseMach(null);

        ClimbCalculationException error = assertThrows(ClimbCalculationException.class,
                () -> new BellmanSolver(Aircraft.defaultAirliner(), task).solve());
        assertNotNull(error.getMessage());
    }

    @Test
    @DisplayName("Перегруженный самолёт не набирает крейсерскую высоту")
    void overweightAircraftCannotClimb() {
        Aircraft aircraft = Aircraft.defaultAirliner();
        aircraft.setMass(160000);

        ClimbTask task = ClimbTask.defaultTask();
        task.setTargetCruiseMach(null);

        assertThrows(ClimbCalculationException.class,
                () -> new BellmanSolver(aircraft, task).solve());
    }

    @Test
    @DisplayName("Результат физически правдоподобен для среднемагистрального самолёта")
    void resultIsPhysicallyPlausible() {
        ClimbSolution solution = solve(Criterion.FUEL);

        double minutes = solution.getTotalTime() / 60.0;
        assertTrue(minutes > 10 && minutes < 60,
                "время набора 11 000 м вышло за разумные пределы: " + minutes + " мин");
        assertTrue(solution.getTotalFuel() > 500 && solution.getTotalFuel() < 5000,
                "расход топлива вышел за разумные пределы: " + solution.getTotalFuel() + " кг");
        assertTrue(solution.getTotalDistance() > 50_000 && solution.getTotalDistance() < 500_000,
                "дальность набора вышла за разумные пределы: " + solution.getTotalDistance() + " м");
    }

    @Test
    @DisplayName("Некорректные исходные данные отвергаются с внятным сообщением")
    void invalidInputIsRejected() {
        ClimbTask task = ClimbTask.defaultTask();
        task.setCruiseAltitude(-100);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new BellmanSolver(Aircraft.defaultAirliner(), task).solve());
        assertTrue(error.getMessage().contains("крейсерская высота"),
                "сообщение об ошибке не объясняет причину: " + error.getMessage());
    }

    private static int indexOf(double[] values, double target) {
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - target) < 1e-9) {
                return i;
            }
        }
        throw new IllegalStateException("в сетке нет скорости " + target);
    }
}
