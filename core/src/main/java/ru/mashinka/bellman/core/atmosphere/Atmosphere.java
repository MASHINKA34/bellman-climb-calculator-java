package ru.mashinka.bellman.core.atmosphere;

/**
 * Стандартная атмосфера (ГОСТ 4401-81 / ISA) для диапазона высот 0...20 000 м.
 *
 * <p>Тропосфера (H &lt; 11 000 м) — линейное падение температуры с градиентом 0,0065 К/м;
 * стратосфера (11 000...20 000 м) — изотермический слой T = 216,65 К.
 *
 * <p>Класс не имеет состояния: все методы статические, внешних зависимостей нет.
 */
public final class Atmosphere {

    /** Ускорение свободного падения, м/с². */
    public static final double G = 9.80665;
    /** Удельная газовая постоянная сухого воздуха, Дж/(кг·К). */
    public static final double R = 287.05287;
    /** Показатель адиабаты. */
    public static final double KAPPA = 1.4;

    /** Температура на уровне моря, К. */
    public static final double T0 = 288.15;
    /** Давление на уровне моря, Па. */
    public static final double P0 = 101325.0;
    /** Плотность на уровне моря, кг/м³. */
    public static final double RHO0 = 1.225;

    /** Высота тропопаузы, м. */
    public static final double H_TROPOPAUSE = 11000.0;
    /** Температура в тропопаузе, К. */
    public static final double T_TROPOPAUSE = 216.65;
    /** Давление в тропопаузе, Па. */
    public static final double P_TROPOPAUSE = 22632.06;

    /** Температурный градиент в тропосфере, К/м. */
    private static final double LAPSE_RATE = 0.0065;

    private Atmosphere() {
    }

    /** Температура воздуха на высоте H, К. */
    public static double temperature(double h) {
        if (h <= H_TROPOPAUSE) {
            return T0 - LAPSE_RATE * h;
        }
        return T_TROPOPAUSE;
    }

    /** Статическое давление на высоте H, Па. */
    public static double pressure(double h) {
        if (h <= H_TROPOPAUSE) {
            // p = p0 * (T/T0)^(g/(L*R)), показатель степени ≈ 5,25588
            return P0 * Math.pow(temperature(h) / T0, G / (LAPSE_RATE * R));
        }
        return P_TROPOPAUSE * Math.exp(-G * (h - H_TROPOPAUSE) / (R * T_TROPOPAUSE));
    }

    /** Массовая плотность воздуха на высоте H, кг/м³. */
    public static double density(double h) {
        return pressure(h) / (R * temperature(h));
    }

    /** Относительная плотность воздуха Δ = ρ(H) / ρ₀. */
    public static double relativeDensity(double h) {
        return density(h) / RHO0;
    }

    /** Скорость звука на высоте H, м/с. */
    public static double speedOfSound(double h) {
        return Math.sqrt(KAPPA * R * temperature(h));
    }
}
