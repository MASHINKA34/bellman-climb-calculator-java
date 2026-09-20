package ru.mashinka.bellman.core.model;

// условия задачи: откуда и докуда набирать, сетка состояний, ограничения
public class ClimbTask {

    // высота начала набора, м
    private double startAltitude = 0.0;
    // крейсерская высота — конец набора, м
    private double cruiseAltitude = 11000.0;
    // шаг сетки по высоте, м
    private double altitudeStep = 500.0;

    // истинная скорость в начале набора, м/с
    private double startSpeed = 130.0;

    // нижняя граница сетки по скорости, м/с
    private double speedMin = 100.0;
    // верхняя граница сетки по скорости, м/с
    private double speedMax = 280.0;
    // шаг сетки по скорости, м/с
    private double speedStep = 5.0;

    // максимальное изменение скорости за один шаг по высоте, м/с
    private double maxSpeedChangePerStep = 30.0;
    // минимально допустимая вертикальная скорость набора, м/с
    private double minRateOfClimb = 0.5;

    // критерий оптимальности
    private Criterion criterion = Criterion.FUEL;
    // стоимость времени в кг топлива на минуту полёта (используется при COST_INDEX)
    private double costIndexKgPerMinute = 12.0;

    // заданное число Маха в конце набора. Если задано, оптимизация заканчивается
    // только в тех узлах крейсерской высоты, которые ему соответствуют.
    // Значение null — крейсерская скорость выбирается алгоритмом.
    private Double targetCruiseMach = 0.78;
    // допуск по числу Маха при выборе конечного узла
    private double cruiseMachTolerance = 0.02;

    // условия задачи по умолчанию: набор от земли до 11 000 м
    public static ClimbTask defaultTask() {
        return new ClimbTask();
    }

    // проверка условий задачи
    public void validate() {
        require(cruiseAltitude > startAltitude,
                "крейсерская высота должна быть больше высоты начала набора");
        require(altitudeStep > 0, "шаг по высоте должен быть больше нуля");
        require(altitudeStep <= cruiseAltitude - startAltitude,
                "шаг по высоте не может превышать весь диапазон набора");
        require(speedMax > speedMin, "верхняя граница скорости должна быть больше нижней");
        require(speedStep > 0, "шаг по скорости должен быть больше нуля");
        require(speedStep <= speedMax - speedMin,
                "шаг по скорости не может превышать диапазон скоростей");
        require(startSpeed >= speedMin && startSpeed <= speedMax,
                "начальная скорость должна лежать внутри сетки скоростей");
        require(maxSpeedChangePerStep > 0,
                "допустимое изменение скорости за шаг должно быть больше нуля");
        require(minRateOfClimb >= 0, "минимальная вертикальная скорость не может быть отрицательной");
        require(criterion != null, "не задан критерий оптимальности");
        require(cruiseMachTolerance > 0, "допуск по числу Маха должен быть больше нуля");
    }

    // стоимость времени в кг топлива на секунду — во внутренних единицах расчёта
    public double getCostIndexKgPerSecond() {
        return costIndexKgPerMinute / 60.0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("Некорректные условия задачи: " + message);
        }
    }

    public double getStartAltitude() {
        return startAltitude;
    }

    public void setStartAltitude(double startAltitude) {
        this.startAltitude = startAltitude;
    }

    public double getCruiseAltitude() {
        return cruiseAltitude;
    }

    public void setCruiseAltitude(double cruiseAltitude) {
        this.cruiseAltitude = cruiseAltitude;
    }

    public double getAltitudeStep() {
        return altitudeStep;
    }

    public void setAltitudeStep(double altitudeStep) {
        this.altitudeStep = altitudeStep;
    }

    public double getStartSpeed() {
        return startSpeed;
    }

    public void setStartSpeed(double startSpeed) {
        this.startSpeed = startSpeed;
    }

    public double getSpeedMin() {
        return speedMin;
    }

    public void setSpeedMin(double speedMin) {
        this.speedMin = speedMin;
    }

    public double getSpeedMax() {
        return speedMax;
    }

    public void setSpeedMax(double speedMax) {
        this.speedMax = speedMax;
    }

    public double getSpeedStep() {
        return speedStep;
    }

    public void setSpeedStep(double speedStep) {
        this.speedStep = speedStep;
    }

    public double getMaxSpeedChangePerStep() {
        return maxSpeedChangePerStep;
    }

    public void setMaxSpeedChangePerStep(double maxSpeedChangePerStep) {
        this.maxSpeedChangePerStep = maxSpeedChangePerStep;
    }

    public double getMinRateOfClimb() {
        return minRateOfClimb;
    }

    public void setMinRateOfClimb(double minRateOfClimb) {
        this.minRateOfClimb = minRateOfClimb;
    }

    public Criterion getCriterion() {
        return criterion;
    }

    public void setCriterion(Criterion criterion) {
        this.criterion = criterion;
    }

    public double getCostIndexKgPerMinute() {
        return costIndexKgPerMinute;
    }

    public void setCostIndexKgPerMinute(double costIndexKgPerMinute) {
        this.costIndexKgPerMinute = costIndexKgPerMinute;
    }

    public Double getTargetCruiseMach() {
        return targetCruiseMach;
    }

    public void setTargetCruiseMach(Double targetCruiseMach) {
        this.targetCruiseMach = targetCruiseMach;
    }

    public double getCruiseMachTolerance() {
        return cruiseMachTolerance;
    }

    public void setCruiseMachTolerance(double cruiseMachTolerance) {
        this.cruiseMachTolerance = cruiseMachTolerance;
    }
}
