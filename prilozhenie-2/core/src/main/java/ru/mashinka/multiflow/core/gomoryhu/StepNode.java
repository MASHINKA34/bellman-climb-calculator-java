package ru.mashinka.multiflow.core.gomoryhu;

// вершина сети на шаге: либо обычная вершина, либо стянутая часть (A, B, ...)
public final class StepNode {

    private final String label;
    private final int[] members;
    private final boolean contracted;

    private StepNode(String label, int[] members, boolean contracted) {
        this.label = label;
        this.members = members.clone();
        this.contracted = contracted;
    }

    static StepNode single(int vertex) {
        return new StepNode(String.valueOf(vertex + 1), new int[]{vertex}, false);
    }

    static StepNode contracted(String label, int[] members) {
        return new StepNode(label, members, true);
    }

    // подпись в таблице шага: номер вершины с единицы или буква стянутой части
    public String getLabel() {
        return label;
    }

    // исходные вершины, из которых состоит эта вершина шага (с нуля, по возрастанию)
    public int[] getMembers() {
        return members.clone();
    }

    public boolean isContracted() {
        return contracted;
    }
}
