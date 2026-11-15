package ru.mashinka.multiflow.core.photo;

// какую таблицу снимали: от этого зависят размер и правила подстановки
public enum PhotoTarget {

    // матрица пропускных способностей во вкладке «Поток» - квадратная, симметричная
    FLOW("flow"),
    // издержки производства во вкладке «Размещение»: виды x предприятия
    PRODUCTION("production"),
    // издержки сбыта: виды x предприятия
    SALES("sales"),
    // план: для каждого вида объём и цена - два столбца
    PLAN("plan");

    private final String id;

    PhotoTarget(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static PhotoTarget fromId(String id) {
        for (PhotoTarget target : values()) {
            if (target.id.equals(id)) {
                return target;
            }
        }
        throw new IllegalArgumentException("Неизвестная таблица для фото: " + id);
    }
}
