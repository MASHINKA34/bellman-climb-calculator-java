package ru.mashinka.multiflow.core.gomoryhu;

import java.util.Collections;
import java.util.List;

import ru.mashinka.multiflow.core.flow.AugmentingPath;

// один шаг алгоритма - то, что по записке пишется для каждой пары S, T:
// сеть шага, пути «исчерпания», максимальный поток, разрез и новая ветвь дерева
//#неизменяемость - поля final, списки через unmodifiableList, массивы отдаются копиями
public final class Step {

    private final int index;
    private final int total;
    private final int source;
    private final int sink;
    private final int[] group;
    private final List<StepNode> nodes;
    private final int[][] capacity;
    private final List<AugmentingPath> paths;
    private final int maxFlow;
    private final int[] sourceSide;
    private final int[] sinkSide;
    private final List<CutEdge> cutEdges;
    private final int[] sourcePart;
    private final int[] sinkPart;
    private final GroupTree treeAfter;
    private final long elapsedNanos;

    Step(int index, int total, int source, int sink, int[] group, List<StepNode> nodes,
         int[][] capacity, List<AugmentingPath> paths, int maxFlow,
         int[] sourceSide, int[] sinkSide, List<CutEdge> cutEdges,
         int[] sourcePart, int[] sinkPart, GroupTree treeAfter, long elapsedNanos) {
        this.index = index;
        this.total = total;
        this.source = source;
        this.sink = sink;
        this.group = group.clone();
        this.nodes = Collections.unmodifiableList(nodes);
        this.capacity = capacity;
        this.paths = Collections.unmodifiableList(paths);
        this.maxFlow = maxFlow;
        this.sourceSide = sourceSide.clone();
        this.sinkSide = sinkSide.clone();
        this.cutEdges = Collections.unmodifiableList(cutEdges);
        this.sourcePart = sourcePart.clone();
        this.sinkPart = sinkPart.clone();
        this.treeAfter = treeAfter;
        this.elapsedNanos = elapsedNanos;
    }

    // номер шага с единицы
    public int getIndex() {
        return index;
    }

    // сколько всего шагов: n - 1
    public int getTotal() {
        return total;
    }

    // S и T - исходные вершины, нумерация с нуля
    public int getSource() {
        return source;
    }

    public int getSink() {
        return sink;
    }

    // группа дерева, которую делит этот шаг
    public int[] getGroup() {
        return group.clone();
    }

    // вершины сети шага в порядке строк таблицы: сначала вершины группы, потом стянутые части
    // S всегда в строке 0, T - в строке 1
    public List<StepNode> getNodes() {
        return nodes;
    }

    // таблица пропускных способностей сети шага; у стянутых частей ветви сложены
    public int[][] getCapacity() {
        int[][] copy = new int[capacity.length][];
        for (int i = 0; i < capacity.length; i++) {
            copy[i] = capacity[i].clone();
        }
        return copy;
    }

    // пути от S к T в порядке нахождения; номера вершин - строки таблицы шага
    public List<AugmentingPath> getPaths() {
        return paths;
    }

    public int getMaxFlow() {
        return maxFlow;
    }

    // разрез в исходных вершинах: сторона S и сторона T
    public int[] getSourceSide() {
        return sourceSide.clone();
    }

    public int[] getSinkSide() {
        return sinkSide.clone();
    }

    // ветви исходной сети, которые пересекает разрез; их сумма равна потоку
    public List<CutEdge> getCutEdges() {
        return cutEdges;
    }

    // половины поделённой группы: новая ветвь дерева соединяет именно их
    public int[] getSourcePart() {
        return sourcePart.clone();
    }

    public int[] getSinkPart() {
        return sinkPart.clone();
    }

    // дерево сразу после этого шага
    public GroupTree getTreeAfter() {
        return treeAfter;
    }

    public long getElapsedNanos() {
        return elapsedNanos;
    }
}
