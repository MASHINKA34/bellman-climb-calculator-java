package ru.mashinka.bellman.core.dp;

/**
 * Точка оптимальной траектории набора высоты — один узел сетки,
 * попавший в найденное алгоритмом Беллмана решение.
 *
 * <p>Величины с приставкой «segment» относятся к участку, приведшему в эту точку
 * из предыдущей; для начальной точки они равны нулю.
 */
public class TrajectoryPoint {

    private final int step;
    private final double altitude;
    private final double speed;
    private final double mach;
    private final double indicatedSpeed;
    private final double energyAltitude;

    private final double liftCoefficient;
    private final double thrust;
    private final double drag;
    private final double excessThrust;
    private final double fuelFlow;
    private final double energyRate;

    private final double rateOfClimb;
    private final double segmentTime;
    private final double segmentFuel;
    private final double segmentDistance;

    private final double time;
    private final double fuel;
    private final double distance;

    TrajectoryPoint(int step, double altitude, double speed, double mach, double indicatedSpeed,
                    double energyAltitude, double liftCoefficient, double thrust, double drag,
                    double excessThrust, double fuelFlow, double energyRate, double rateOfClimb,
                    double segmentTime, double segmentFuel, double segmentDistance,
                    double time, double fuel, double distance) {
        this.step = step;
        this.altitude = altitude;
        this.speed = speed;
        this.mach = mach;
        this.indicatedSpeed = indicatedSpeed;
        this.energyAltitude = energyAltitude;
        this.liftCoefficient = liftCoefficient;
        this.thrust = thrust;
        this.drag = drag;
        this.excessThrust = excessThrust;
        this.fuelFlow = fuelFlow;
        this.energyRate = energyRate;
        this.rateOfClimb = rateOfClimb;
        this.segmentTime = segmentTime;
        this.segmentFuel = segmentFuel;
        this.segmentDistance = segmentDistance;
        this.time = time;
        this.fuel = fuel;
        this.distance = distance;
    }

    /** Номер высотного уровня (этапа) k. */
    public int getStep() {
        return step;
    }

    /** Высота H, м. */
    public double getAltitude() {
        return altitude;
    }

    /** Истинная скорость V, м/с. */
    public double getSpeed() {
        return speed;
    }

    /** Число Маха. */
    public double getMach() {
        return mach;
    }

    /** Приборная (индикаторная) скорость, м/с. */
    public double getIndicatedSpeed() {
        return indicatedSpeed;
    }

    /** Энергетическая высота Hэ = H + V²/(2g), м. */
    public double getEnergyAltitude() {
        return energyAltitude;
    }

    /** Коэффициент подъёмной силы Cy. */
    public double getLiftCoefficient() {
        return liftCoefficient;
    }

    /** Располагаемая тяга P, Н. */
    public double getThrust() {
        return thrust;
    }

    /** Лобовое сопротивление Q, Н. */
    public double getDrag() {
        return drag;
    }

    /** Избыток тяги ΔP = P − Q, Н. */
    public double getExcessThrust() {
        return excessThrust;
    }

    /** Секундный расход топлива, кг/с. */
    public double getFuelFlow() {
        return fuelFlow;
    }

    /** Энергетическая скороподъёмность dHэ/dt, м/с. */
    public double getEnergyRate() {
        return energyRate;
    }

    /** Вертикальная скорость Vy на участке, приведшем в точку, м/с. */
    public double getRateOfClimb() {
        return rateOfClimb;
    }

    /** Время прохождения участка, с. */
    public double getSegmentTime() {
        return segmentTime;
    }

    /** Расход топлива на участке, кг. */
    public double getSegmentFuel() {
        return segmentFuel;
    }

    /** Горизонтальная дальность участка, м. */
    public double getSegmentDistance() {
        return segmentDistance;
    }

    /** Время от начала набора, с. */
    public double getTime() {
        return time;
    }

    /** Израсходованное от начала набора топливо, кг. */
    public double getFuel() {
        return fuel;
    }

    /** Пройденная от начала набора горизонтальная дальность, м. */
    public double getDistance() {
        return distance;
    }
}
