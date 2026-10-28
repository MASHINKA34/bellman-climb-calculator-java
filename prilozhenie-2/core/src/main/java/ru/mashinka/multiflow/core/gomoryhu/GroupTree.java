package ru.mashinka.multiflow.core.gomoryhu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// снимок дерева после шага: группы вершин и ветви между ними
// после последнего шага каждая группа - одна вершина, и это уже готовое дерево Гомори–Ху
public final class GroupTree {

    private final List<int[]> groups;
    private final List<GroupEdge> edges;
    private final int newEdge;

    GroupTree(List<int[]> groups, List<GroupEdge> edges, int newEdge) {
        // копии: решатель продолжает менять свои списки на следующих шагах
        List<int[]> groupsCopy = new ArrayList<>();
        for (int[] group : groups) {
            groupsCopy.add(group.clone());
        }
        this.groups = Collections.unmodifiableList(groupsCopy);
        this.edges = Collections.unmodifiableList(new ArrayList<>(edges));
        this.newEdge = newEdge;
    }

    // группа по номеру: вершины с нуля, по возрастанию
    public int[] getGroup(int index) {
        return groups.get(index).clone();
    }

    public int getGroupCount() {
        return groups.size();
    }

    public List<GroupEdge> getEdges() {
        return edges;
    }

    // номер ветви, добавленной на этом шаге, в списке getEdges()
    public int getNewEdge() {
        return newEdge;
    }
}
