package ru.mashinka.bellman.core.model;

/**
 * Критерий оптимальности набора высоты — величина, которую минимизирует алгоритм Беллмана.
 */
public enum Criterion {

    /** Минимум времени набора крейсерской высоты. */
    TIME("минимум времени набора", "с"),

    /** Минимум расхода топлива на наборе. */
    FUEL("минимум расхода топлива", "кг"),

    /** Комбинированный критерий: масса топлива плюс стоимость времени (cost index). */
    COST_INDEX("минимум приведённых затрат (cost index)", "кг у.е.");

    private final String title;
    private final String unit;

    Criterion(String title, String unit) {
        this.title = title;
        this.unit = unit;
    }

    /** Название критерия по-русски — для интерфейса и отчёта. */
    public String getTitle() {
        return title;
    }

    /** Единица измерения функции Беллмана при этом критерии. */
    public String getUnit() {
        return unit;
    }
}
