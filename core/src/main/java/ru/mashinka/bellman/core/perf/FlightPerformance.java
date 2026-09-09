package ru.mashinka.bellman.core.perf;

import ru.mashinka.bellman.core.atmosphere.Atmosphere;
import ru.mashinka.bellman.core.model.Aircraft;

/**
 * Лётно-технические характеристики самолёта в точке (H, V):
 * располагаемая тяга, лобовое сопротивление, расход топлива,
 * энергетическая скороподъёмность и границы диапазона скоростей.
 *
 * <p>Масса самолёта в пределах одного расчёта считается постоянной —
 * стандартное допущение для задачи о наборе высоты, так как выгорающее
 * на наборе топливо составляет единицы процентов от взлётной массы.
 */
public class FlightPerformance {

    private final Aircraft aircraft;

    public FlightPerformance(Aircraft aircraft) {
        this.aircraft = aircraft;
    }

    public Aircraft getAircraft() {
        return aircraft;
    }

    /** Число Маха, соответствующее истинной скорости V на высоте H. */
    public double mach(double h, double v) {
        return v / Atmosphere.speedOfSound(h);
    }

    /**
     * Располагаемая тяга силовой установки, Н.
     * P = P₀ · Δⁿ · (1 − k_M·M), где Δ — относительная плотность воздуха.
     */
    public double thrust(double h, double v) {
        double sigma = Atmosphere.relativeDensity(h);
        double machFactor = 1.0 - aircraft.getThrustMachFactor() * mach(h, v);
        if (machFactor < 0.1) {
            machFactor = 0.1;
        }
        return aircraft.getThrustSeaLevel()
                * Math.pow(sigma, aircraft.getThrustAltitudeExponent())
                * machFactor;
    }

    /** Коэффициент подъёмной силы в горизонтальном полёте при данных H и V. */
    public double liftCoefficient(double h, double v) {
        double rho = Atmosphere.density(h);
        return 2.0 * aircraft.getMass() * Atmosphere.G / (rho * v * v * aircraft.getWingArea());
    }

    /** Коэффициент лобового сопротивления по квадратичной поляре. */
    public double dragCoefficient(double h, double v) {
        double cy = liftCoefficient(h, v);
        return aircraft.getCx0() + aircraft.getInducedDragFactor() * cy * cy;
    }

    /** Сила лобового сопротивления, Н. */
    public double drag(double h, double v) {
        double rho = Atmosphere.density(h);
        return dragCoefficient(h, v) * rho * v * v / 2.0 * aircraft.getWingArea();
    }

    /** Удельный расход топлива на высоте H при скорости V, кг/(Н·с). */
    public double specificFuelConsumption(double h, double v) {
        double temperatureFactor = Math.sqrt(Atmosphere.temperature(h) / Atmosphere.T0);
        double machFactor = 1.0 + aircraft.getSfcMachFactor() * mach(h, v);
        return aircraft.getSfcSeaLevel() / 3600.0 * temperatureFactor * machFactor;
    }

    /** Часовой (секундный) расход топлива, кг/с. */
    public double fuelFlow(double h, double v) {
        return specificFuelConsumption(h, v) * thrust(h, v);
    }

    /** Избыток тяги ΔP = P − Q, Н. Отрицательное значение — набор невозможен. */
    public double excessThrust(double h, double v) {
        return thrust(h, v) - drag(h, v);
    }

    /**
     * Энергетическая скороподъёмность dHэ/dt = V·(P − Q)/(m·g), м/с.
     *
     * <p>Это ключевая величина энергетического метода: она показывает,
     * с какой скоростью растёт энергетическая высота Hэ = H + V²/(2g).
     */
    public double energyRate(double h, double v) {
        return v * excessThrust(h, v) / (aircraft.getMass() * Atmosphere.G);
    }

    /** Минимально допустимая истинная скорость на высоте H (сваливание с запасом), м/с. */
    public double minSpeed(double h) {
        double rho = Atmosphere.density(h);
        double stallSpeed = Math.sqrt(2.0 * aircraft.getMass() * Atmosphere.G
                / (rho * aircraft.getWingArea() * aircraft.getCyMax()));
        return stallSpeed * aircraft.getStallMargin();
    }

    /**
     * Максимально допустимая истинная скорость на высоте H, м/с —
     * меньшее из ограничения по числу Маха и по приборной скорости.
     */
    public double maxSpeed(double h) {
        double byMach = aircraft.getMaxMach() * Atmosphere.speedOfSound(h);
        double byIndicated = aircraft.getMaxIndicatedSpeed() / Math.sqrt(Atmosphere.relativeDensity(h));
        return Math.min(byMach, byIndicated);
    }

    /** Допустима ли точка (H, V) по эксплуатационным ограничениям скорости. */
    public boolean isSpeedAllowed(double h, double v) {
        return v >= minSpeed(h) && v <= maxSpeed(h);
    }
}
