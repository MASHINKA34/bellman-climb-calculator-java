package ru.mashinka.multiflow.core.gomoryhu;

// строка (и столбец) таблицы шага - одна вершина исходной сети
public final class StepNode {

    private final String label;
    private final int[] members;

    private StepNode(String label, int[] members) {
        this.label = label;
        this.members = members.clone();
    }

    static StepNode single(int vertex) {
        return new StepNode(String.valueOf(vertex + 1), new int[]{vertex});
    }

    // подпись в таблице шага: номер вершины с единицы
    public String getLabel() {
        return label;
    }

    // исходная вершина этой строки (с нуля), массивом из одного элемента
    public int[] getMembers() {
        return members.clone();
    }
}
