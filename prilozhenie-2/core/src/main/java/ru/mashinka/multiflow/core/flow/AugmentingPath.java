package ru.mashinka.multiflow.core.flow;

// один найденный путь от S к T и сколько по нему удалось пропустить
// nodes - номера вершин той сети, в которой искали путь (на шаге это сеть со стянутыми вершинами)
public final class AugmentingPath {

    private final int[] nodes;
    private final int amount;

    AugmentingPath(int[] nodes, int amount) {
        this.nodes = nodes.clone();
        this.amount = amount;
    }

    public int[] getNodes() {
        return nodes.clone();
    }

    // самая слабая ветвь пути: больше по нему не пройдёт
    public int getAmount() {
        return amount;
    }
}
