package ru.mashinka.bellman.core.model;

//#enum - перечисление с полями и конструктором, а не просто список констант
//#перечисление - три варианта, четвёртого быть не может: компилятор не даст
//
// критерий оптимальности набора высоты — величина, которую минимизирует алгоритм Беллмана
public enum Criterion {

    // минимум времени набора крейсерской высоты
    TIME("минимум времени набора", "с"),

    // минимум расхода топлива на наборе
    FUEL("минимум расхода топлива", "кг"),

    // комбинированный критерий: масса топлива плюс стоимость времени (cost index)
    COST_INDEX("минимум приведённых затрат (cost index)", "кг у.е.");

    private final String title;
    private final String unit;

    Criterion(String title, String unit) {
        this.title = title;
        this.unit = unit;
    }

    // название критерия по-русски — для интерфейса и отчёта
    public String getTitle() {
        return title;
    }

    // единица измерения функции Беллмана при этом критерии
    public String getUnit() {
        return unit;
    }
}
