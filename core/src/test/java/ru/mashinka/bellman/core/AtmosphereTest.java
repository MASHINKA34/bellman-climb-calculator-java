package ru.mashinka.bellman.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.mashinka.bellman.core.atmosphere.Atmosphere;

@DisplayName("Стандартная атмосфера")
class AtmosphereTest {

    @Test
    @DisplayName("На уровне моря совпадает с табличными значениями ГОСТ 4401-81")
    void seaLevelMatchesStandardValues() {
        assertEquals(288.15, Atmosphere.temperature(0), 1e-9);
        assertEquals(101325.0, Atmosphere.pressure(0), 1e-6);
        assertEquals(1.225, Atmosphere.density(0), 1e-3);
        assertEquals(340.29, Atmosphere.speedOfSound(0), 0.01);
        assertEquals(1.0, Atmosphere.relativeDensity(0), 1e-3);
    }

    @Test
    @DisplayName("В тропопаузе совпадает с табличными значениями")
    void tropopauseMatchesStandardValues() {
        assertEquals(216.65, Atmosphere.temperature(11000), 1e-9);
        assertEquals(22632.0, Atmosphere.pressure(11000), 5.0);
        assertEquals(0.3639, Atmosphere.density(11000), 1e-3);
        assertEquals(295.07, Atmosphere.speedOfSound(11000), 0.05);
    }

    @Test
    @DisplayName("В стратосфере температура постоянна, а давление продолжает падать")
    void stratosphereIsIsothermal() {
        assertEquals(216.65, Atmosphere.temperature(15000), 1e-9);
        assertEquals(216.65, Atmosphere.temperature(20000), 1e-9);
        assertTrue(Atmosphere.pressure(15000) < Atmosphere.pressure(11000));
        assertTrue(Atmosphere.pressure(20000) < Atmosphere.pressure(15000));
    }

    @Test
    @DisplayName("Плотность монотонно убывает с высотой")
    void densityDecreasesWithAltitude() {
        double previous = Atmosphere.density(0);
        for (double h = 500; h <= 20000; h += 500) {
            double current = Atmosphere.density(h);
            assertTrue(current < previous,
                    "плотность должна убывать, нарушено на высоте " + h + " м");
            previous = current;
        }
    }

    @Test
    @DisplayName("Давление непрерывно на границе тропосферы и стратосферы")
    void pressureIsContinuousAtTropopause() {
        double below = Atmosphere.pressure(11000 - 0.001);
        double above = Atmosphere.pressure(11000 + 0.001);
        assertEquals(below, above, 1.0);
    }
}
