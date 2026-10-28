package ru.mashinka.multiflow.core.gomoryhu;

// ветвь исходной сети, которую пересекает разрез: u на стороне S, v на стороне T
public final class CutEdge {

    private final int u;
    private final int v;
    private final int capacity;

    CutEdge(int u, int v, int capacity) {
        this.u = u;
        this.v = v;
        this.capacity = capacity;
    }

    public int getU() {
        return u;
    }

    public int getV() {
        return v;
    }

    public int getCapacity() {
        return capacity;
    }
}
