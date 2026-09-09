package ru.mashinka.bellman.core.model;

/**
 * Исходные данные самолёта: массовые, геометрические и аэродинамические характеристики,
 * а также характеристики силовой установки.
 *
 * <p>Аэродинамика задана квадратичной полярой Cx = Cx0 + A·Cy²,
 * располагаемая тяга — высотно-скоростной характеристикой P = P₀·Δⁿ·(1 − k_M·M),
 * удельный расход топлива — Ce = Ce₀·√(T/T₀)·(1 + k_Ce·M).
 */
public class Aircraft {

    private String name = "Среднемагистральный самолёт (условный)";

    /** Полётная масса, кг. */
    private double mass = 70000.0;
    /** Площадь крыла, м². */
    private double wingArea = 124.6;

    /** Коэффициент лобового сопротивления при нулевой подъёмной силе. */
    private double cx0 = 0.020;
    /** Коэффициент отвала поляры A в выражении Cx = Cx0 + A·Cy². */
    private double inducedDragFactor = 0.045;
    /** Максимальный коэффициент подъёмной силы в полётной конфигурации. */
    private double cyMax = 1.40;
    /** Запас по скорости сваливания: Vmin = k·V_св. */
    private double stallMargin = 1.25;

    /** Суммарная тяга всех двигателей у земли на номинальном режиме набора, Н. */
    private double thrustSeaLevel = 180000.0;
    /** Показатель степени n в законе изменения тяги по высоте P = P₀·Δⁿ. */
    private double thrustAltitudeExponent = 0.85;
    /** Коэффициент падения тяги по числу Маха k_M. */
    private double thrustMachFactor = 0.25;

    /** Удельный расход топлива у земли, кг/(Н·ч). */
    private double sfcSeaLevel = 0.036;
    /** Коэффициент роста удельного расхода по числу Маха. */
    private double sfcMachFactor = 0.90;

    /** Максимально допустимое число Маха. */
    private double maxMach = 0.82;
    /** Максимальная приборная (индикаторная) скорость, м/с. */
    private double maxIndicatedSpeed = 175.0;

    /** Самолёт по умолчанию — условный среднемагистральный лайнер. */
    public static Aircraft defaultAirliner() {
        return new Aircraft();
    }

    /** Проверка исходных данных: физически бессмысленные значения отсекаются сразу. */
    public void validate() {
        require(mass > 0, "масса самолёта должна быть больше нуля");
        require(wingArea > 0, "площадь крыла должна быть больше нуля");
        require(cx0 > 0, "коэффициент Cx0 должен быть больше нуля");
        require(inducedDragFactor > 0, "коэффициент отвала поляры A должен быть больше нуля");
        require(cyMax > 0, "Cy max должен быть больше нуля");
        require(stallMargin >= 1.0, "запас по скорости сваливания не может быть меньше 1");
        require(thrustSeaLevel > 0, "тяга у земли должна быть больше нуля");
        require(thrustAltitudeExponent > 0, "показатель степени n должен быть больше нуля");
        require(thrustMachFactor >= 0, "коэффициент k_M не может быть отрицательным");
        require(sfcSeaLevel > 0, "удельный расход топлива должен быть больше нуля");
        require(sfcMachFactor >= 0, "коэффициент k_Ce не может быть отрицательным");
        require(maxMach > 0, "максимальное число Маха должно быть больше нуля");
        require(maxIndicatedSpeed > 0, "максимальная приборная скорость должна быть больше нуля");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("Некорректные данные самолёта: " + message);
        }
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getMass() {
        return mass;
    }

    public void setMass(double mass) {
        this.mass = mass;
    }

    public double getWingArea() {
        return wingArea;
    }

    public void setWingArea(double wingArea) {
        this.wingArea = wingArea;
    }

    public double getCx0() {
        return cx0;
    }

    public void setCx0(double cx0) {
        this.cx0 = cx0;
    }

    public double getInducedDragFactor() {
        return inducedDragFactor;
    }

    public void setInducedDragFactor(double inducedDragFactor) {
        this.inducedDragFactor = inducedDragFactor;
    }

    public double getCyMax() {
        return cyMax;
    }

    public void setCyMax(double cyMax) {
        this.cyMax = cyMax;
    }

    public double getStallMargin() {
        return stallMargin;
    }

    public void setStallMargin(double stallMargin) {
        this.stallMargin = stallMargin;
    }

    public double getThrustSeaLevel() {
        return thrustSeaLevel;
    }

    public void setThrustSeaLevel(double thrustSeaLevel) {
        this.thrustSeaLevel = thrustSeaLevel;
    }

    public double getThrustAltitudeExponent() {
        return thrustAltitudeExponent;
    }

    public void setThrustAltitudeExponent(double thrustAltitudeExponent) {
        this.thrustAltitudeExponent = thrustAltitudeExponent;
    }

    public double getThrustMachFactor() {
        return thrustMachFactor;
    }

    public void setThrustMachFactor(double thrustMachFactor) {
        this.thrustMachFactor = thrustMachFactor;
    }

    public double getSfcSeaLevel() {
        return sfcSeaLevel;
    }

    public void setSfcSeaLevel(double sfcSeaLevel) {
        this.sfcSeaLevel = sfcSeaLevel;
    }

    public double getSfcMachFactor() {
        return sfcMachFactor;
    }

    public void setSfcMachFactor(double sfcMachFactor) {
        this.sfcMachFactor = sfcMachFactor;
    }

    public double getMaxMach() {
        return maxMach;
    }

    public void setMaxMach(double maxMach) {
        this.maxMach = maxMach;
    }

    public double getMaxIndicatedSpeed() {
        return maxIndicatedSpeed;
    }

    public void setMaxIndicatedSpeed(double maxIndicatedSpeed) {
        this.maxIndicatedSpeed = maxIndicatedSpeed;
    }
}
