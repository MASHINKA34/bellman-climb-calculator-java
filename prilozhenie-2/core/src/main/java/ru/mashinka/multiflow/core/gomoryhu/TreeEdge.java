package ru.mashinka.multiflow.core.gomoryhu;

// ветвь готового дерева Гомори–Ху: две вершины и максимальный поток между ними
public final class TreeEdge {

    private final int u;
    private final int v;
    private final int value;
    private final int step;

    TreeEdge(int u, int v, int value, int step) {
        this.u = u;
        this.v = v;
        this.value = value;
        this.step = step;
    }

    public int getU() {
        return u;
    }

    public int getV() {
        return v;
    }

    public int getValue() {
        return value;
    }

    // шаг, на котором ветвь появилась
    public int getStep() {
        return step;
    }
}
