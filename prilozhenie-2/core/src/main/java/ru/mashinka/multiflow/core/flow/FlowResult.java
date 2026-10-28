package ru.mashinka.multiflow.core.flow;

import java.util.Collections;
import java.util.List;

// итог расчёта потока между S и T: величина, найденные пути и минимальный разрез
//#неизменяемость - поля final, список через unmodifiableList, массивы отдаются копиями
public final class FlowResult {

    private final int value;
    private final List<AugmentingPath> paths;
    private final boolean[] sourceSide;
    private final int[][] residual;

    FlowResult(int value, List<AugmentingPath> paths, boolean[] sourceSide, int[][] residual) {
        this.value = value;
        this.paths = Collections.unmodifiableList(paths);
        this.sourceSide = sourceSide;
        this.residual = residual;
    }

    public int getValue() {
        return value;
    }

    public List<AugmentingPath> getPaths() {
        return paths;
    }

    // сторона разреза: вершины, до которых от S ещё можно дойти, когда пути исчерпаны
    public boolean isOnSourceSide(int node) {
        return sourceSide[node];
    }

    // остаточные пропускные способности после того, как пути исчерпаны
    public int[][] getResidual() {
        int[][] copy = new int[residual.length][];
        for (int i = 0; i < residual.length; i++) {
            copy[i] = residual[i].clone();
        }
        return copy;
    }
}
