package ru.mashinka.bellman.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.mashinka.bellman.core.model.Aircraft;
import ru.mashinka.bellman.core.perf.FlightPerformance;

@DisplayName("Лётно-технические характеристики")
class FlightPerformanceTest {

    private final FlightPerformance performance =
            new FlightPerformance(Aircraft.defaultAirliner());

    @Test
    @DisplayName("Тяга падает с высотой")
    void thrustDecreasesWithAltitude() {
        double previous = performance.thrust(0, 200);
        for (double h = 1000; h <= 11000; h += 1000) {
            double current = performance.thrust(h, 200);
            assertTrue(current < previous, "тяга должна падать, нарушено на высоте " + h + " м");
            previous = current;
        }
    }

    @Test
    @DisplayName("Минимально допустимая истинная скорость растёт с высотой")
    void minimumSpeedGrowsWithAltitude() {
        double previous = performance.minSpeed(0);
        for (double h = 1000; h <= 11000; h += 1000) {
            double current = performance.minSpeed(h);
            assertTrue(current > previous, "Vmin должна расти, нарушено на высоте " + h + " м");
            previous = current;
        }
    }

    @Test
    @DisplayName("Диапазон допустимых скоростей на крейсерской высоте не вырожден")
    void speedRangeIsNotEmptyAtCruiseAltitude() {
        assertTrue(performance.maxSpeed(11000) > performance.minSpeed(11000),
                "на 11 000 м диапазон скоростей схлопнулся — самолёт не долетит");
    }

    @Test
    @DisplayName("Избыток тяги у земли положителен, у потолка вырождается")
    void excessThrustVanishesWithAltitude() {
        assertTrue(performance.excessThrust(0, 150) > 0);
        assertTrue(performance.energyRate(0, 150) > performance.energyRate(11000, 230));
    }

    @Test
    @DisplayName("Поляра даёт минимум сопротивления на наивыгоднейшей скорости")
    void dragHasMinimumAtBestSpeed() {
        double bestSpeed = 0;
        double minDrag = Double.MAX_VALUE;
        for (double v = 100; v <= 280; v += 1) {
            double drag = performance.drag(3000, v);
            if (drag < minDrag) {
                minDrag = drag;
                bestSpeed = v;
            }
        }
        // наивыгоднейшая скорость обязана лежать внутри диапазона, а не на его краю
        assertTrue(bestSpeed > 100 && bestSpeed < 280,
                "минимум сопротивления оказался на границе диапазона: " + bestSpeed);
    }

    @Test
    @DisplayName("Расход топлива положителен и растёт вместе с тягой")
    void fuelFlowIsPositive() {
        assertTrue(performance.fuelFlow(0, 150) > 0);
        assertTrue(performance.fuelFlow(0, 150) > performance.fuelFlow(11000, 230),
                "у земли двигатели должны расходовать больше, чем на высоте");
    }

    @Test
    @DisplayName("Число Маха считается через скорость звука на данной высоте")
    void machUsesLocalSpeedOfSound() {
        assertEquals(1.0, performance.mach(0, 340.294), 1e-3);
        assertEquals(1.0, performance.mach(11000, 295.07), 1e-3);
    }
}
