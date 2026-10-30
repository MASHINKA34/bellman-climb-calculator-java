package ru.mashinka.multiflow.core.gomoryhu;

import java.util.Collections;
import java.util.List;

import ru.mashinka.multiflow.core.network.Network;

// результат: шаги, готовое дерево Гомори-Ху и итоговая таблица потоков между всеми парами
//#неизменяемость - поля final, списки через unmodifiableList, таблица отдаётся копией
//#дженерики - List<Step>, List<TreeEdge>
public final class MultiFlowSolution {

    private final Network network;
    private final List<Step> steps;
    private final List<TreeEdge> tree;
    private final int[][] table;
    private final List<int[]> components;
    private final long elapsedNanos;

    MultiFlowSolution(Network network, List<Step> steps, List<TreeEdge> tree, int[][] table,
                      List<int[]> components, long elapsedNanos) {
        this.network = network;
        this.steps = Collections.unmodifiableList(steps);
        this.tree = Collections.unmodifiableList(tree);
        this.table = table;
        this.components = Collections.unmodifiableList(components);
        this.elapsedNanos = elapsedNanos;
    }

    public Network getNetwork() {
        return network;
    }

    public int getSize() {
        return network.size();
    }

    public List<Step> getSteps() {
        return steps;
    }

    // ветви готового дерева в порядке появления
    public List<TreeEdge> getTree() {
        return tree;
    }

    // максимальный поток между вершинами i и j (с нуля); на диагонали 0
    public int getFlow(int i, int j) {
        return table[i][j];
    }

    public int[][] getTable() {
        int[][] copy = new int[table.length][];
        for (int i = 0; i < table.length; i++) {
            copy[i] = table[i].clone();
        }
        return copy;
    }

    // части сети без ветвей между собой; если часть одна - сеть связная
    public List<int[]> getComponents() {
        return components;
    }

    public boolean isConnected() {
        return components.size() == 1;
    }

    public long getElapsedNanos() {
        return elapsedNanos;
    }
}
