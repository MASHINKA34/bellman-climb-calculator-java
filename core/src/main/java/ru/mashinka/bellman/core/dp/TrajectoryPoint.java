package ru.mashinka.bellman.core.dp;

// точка оптимальной траектории - узел сетки, попавший в решение
// поля segment* относятся к участку, приведшему в эту точку; в начальной точке нули
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

    // номер высотного уровня (этапа) k
    public int getStep() {
        return step;
    }

    // высота H, м
    public double getAltitude() {
        return altitude;
    }

    // истинная скорость V, м/с
    public double getSpeed() {
        return speed;
    }

    // число Маха
    public double getMach() {
        return mach;
    }

    // приборная (индикаторная) скорость, м/с
    public double getIndicatedSpeed() {
        return indicatedSpeed;
    }

    // энергетическая высота Hэ = H + V²/(2g), м
    public double getEnergyAltitude() {
        return energyAltitude;
    }

    // коэффициент подъёмной силы Cy
    public double getLiftCoefficient() {
        return liftCoefficient;
    }

    // располагаемая тяга P, Н
    public double getThrust() {
        return thrust;
    }

    // лобовое сопротивление Q, Н
    public double getDrag() {
        return drag;
    }

    // избыток тяги ΔP = P − Q, Н
    public double getExcessThrust() {
        return excessThrust;
    }

    // секундный расход топлива, кг/с
    public double getFuelFlow() {
        return fuelFlow;
    }

    // энергетическая скороподъёмность dHэ/dt, м/с
    public double getEnergyRate() {
        return energyRate;
    }

    // вертикальная скорость Vy на участке, приведшем в точку, м/с
    public double getRateOfClimb() {
        return rateOfClimb;
    }

    // время прохождения участка, с
    public double getSegmentTime() {
        return segmentTime;
    }

    // расход топлива на участке, кг
    public double getSegmentFuel() {
        return segmentFuel;
    }

    // горизонтальная дальность участка, м
    public double getSegmentDistance() {
        return segmentDistance;
    }

    // время от начала набора, с
    public double getTime() {
        return time;
    }

    // израсходованное от начала набора топливо, кг
    public double getFuel() {
        return fuel;
    }

    // пройденная от начала набора горизонтальная дальность, м
    public double getDistance() {
        return distance;
    }
}
