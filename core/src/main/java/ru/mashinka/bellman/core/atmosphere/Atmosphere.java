package ru.mashinka.bellman.core.atmosphere;

//#статика - все методы static, final и приватный конструктор: создавать объект незачем
//#гост - стандартная атмосфера по ГОСТ 4401-81, до 20 км
public final class Atmosphere {

    // ускорение свободного падения, м/с²
    public static final double G = 9.80665;
    // удельная газовая постоянная сухого воздуха, Дж/(кг·К)
    public static final double R = 287.05287;
    // показатель адиабаты
    public static final double KAPPA = 1.4;

    // температура на уровне моря, К
    public static final double T0 = 288.15;
    // давление на уровне моря, Па
    public static final double P0 = 101325.0;
    // плотность на уровне моря, кг/м³
    public static final double RHO0 = 1.225;

    // высота тропопаузы, м
    public static final double H_TROPOPAUSE = 11000.0;
    // температура в тропопаузе, К
    public static final double T_TROPOPAUSE = 216.65;
    // давление в тропопаузе, Па
    public static final double P_TROPOPAUSE = 22632.06;

    // температурный градиент в тропосфере, К/м
    private static final double LAPSE_RATE = 0.0065;

    private Atmosphere() {
    }

    // температура воздуха на высоте H, К
    public static double temperature(double h) {
        if (h <= H_TROPOPAUSE) {
            return T0 - LAPSE_RATE * h;
        }
        return T_TROPOPAUSE;
    }

    // статическое давление на высоте H, Па
    public static double pressure(double h) {
        if (h <= H_TROPOPAUSE) {
            // p = p0 * (T/T0)^(g/(L*R)), показатель степени ≈ 5,25588
            return P0 * Math.pow(temperature(h) / T0, G / (LAPSE_RATE * R));
        }
        return P_TROPOPAUSE * Math.exp(-G * (h - H_TROPOPAUSE) / (R * T_TROPOPAUSE));
    }

    // массовая плотность воздуха на высоте H, кг/м³
    public static double density(double h) {
        return pressure(h) / (R * temperature(h));
    }

    // относительная плотность воздуха Δ = ρ(H) / ρ₀
    public static double relativeDensity(double h) {
        return density(h) / RHO0;
    }

    // скорость звука на высоте H, м/с
    public static double speedOfSound(double h) {
        return Math.sqrt(KAPPA * R * temperature(h));
    }
}
