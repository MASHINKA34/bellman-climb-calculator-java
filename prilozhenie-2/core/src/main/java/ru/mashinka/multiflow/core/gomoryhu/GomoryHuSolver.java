package ru.mashinka.multiflow.core.gomoryhu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import ru.mashinka.multiflow.core.flow.AugmentingPath;
import ru.mashinka.multiflow.core.flow.FlowResult;
import ru.mashinka.multiflow.core.flow.MaxFlow;
import ru.mashinka.multiflow.core.log.CoreLog;
import ru.mashinka.multiflow.core.network.Network;

// многополюсный максимальный поток - дерево Гомори-Ху (Р. Гомори, Т. Ху, 1961)
// без стягивания вершин, по способу Д. Гасфилда (1990)
//
// В начале дерево - одна группа со всеми вершинами. Каждый шаг берёт группу,
// в которой больше одной вершины, выбирает в ней пару S и T, считает максимальный
// поток S -> T по всей исходной сети (таблица n x n, как на паре) и делит группу
// разрезом надвое. Половины соединяет новая ветвь со значением потока.
// n вершин - n-1 шагов: для сети 7 x 7 ровно 6 расчётов потока вместо 21.
//
// Поток между любыми двумя вершинами потом читается по дереву: это самая слабая
// ветвь на пути между ними. Так заполняется итоговая таблица.
public class GomoryHuSolver {

    private static final Logger LOG = Logger.getLogger(GomoryHuSolver.class.getName());

    //#главный - точка входа, отсюда начинать показ
    public MultiFlowSolution solve(Network network, String runId) {
        long startedAt = System.nanoTime();
        int n = network.size();
        List<int[]> components = network.components();

        LOG.info(() -> String.format(Locale.ROOT,
                "run=%s solve start: %d vertices, %d branches, total capacity %d, %s",
                runId, n, network.branchCount(), network.totalCapacity(),
                components.size() == 1
                        ? "connected"
                        : components.size() + " disconnected parts " + describeParts(components)));
        if (LOG.isLoggable(Level.FINE)) {
            for (int i = 0; i < n; i++) {
                int row = i;
                LOG.fine(() -> "run=" + runId + " capacity row " + (row + 1) + ": "
                        + Arrays.toString(network.toMatrix()[row]));
            }
        }

        // дерево групп: в начале одна группа со всеми вершинами и ни одной ветви
        List<int[]> groups = new ArrayList<>();
        groups.add(allVertices(n));
        List<GroupEdge> edges = new ArrayList<>();

        List<Step> steps = new ArrayList<>();
        for (int index = 1; index < n; index++) {
            steps.add(runStep(network, groups, edges, index, n - 1, runId));
        }

        List<TreeEdge> tree = finalTree(groups, edges);
        int[][] table = flowTable(n, tree);

        long elapsed = System.nanoTime() - startedAt;
        LOG.info(() -> String.format(Locale.ROOT, "run=%s solve done: %d steps, tree %s, %s",
                runId, steps.size(), describeTree(tree), CoreLog.millis(elapsed)));
        return new MultiFlowSolution(network, steps, tree, table, components, elapsed);
    }

    // ------------------------------------------------------------------ шаг

    private Step runStep(Network network, List<int[]> groups, List<GroupEdge> edges,
                         int index, int total, String runId) {
        long startedAt = System.nanoTime();
        int n = network.size();

        //#выбор-пары - делим группу с самой маленькой вершиной из ещё не разделённых,
        // S и T - две её первые вершины. От выбора пары итоговая таблица не зависит
        int split = chooseGroup(groups);
        int[] group = groups.get(split);
        int source = group[0];
        int sink = group[1];

        //#без-стягивания - таблица шага - вся исходная сеть, строки 1..n по порядку, как на паре
        List<StepNode> nodes = new ArrayList<>();
        for (int vertex = 0; vertex < n; vertex++) {
            nodes.add(StepNode.single(vertex));
        }
        int size = n;
        int[][] capacity = network.toMatrix();

        LOG.info(() -> String.format(Locale.ROOT, "run=%s step %d/%d: split group %s with S=%d T=%d, full network %dx%d",
                runId, index, total, CoreLog.vertices(group), source + 1, sink + 1, n, n));
        if (LOG.isLoggable(Level.FINE)) {
            for (int a = 0; a < size; a++) {
                int row = a;
                LOG.fine(() -> "run=" + runId + " step " + index + " table row " + nodes.get(row).getLabel()
                        + ": " + Arrays.toString(capacity[row]));
            }
        }

        FlowResult flow = MaxFlow.compute(capacity, source, sink);

        if (LOG.isLoggable(Level.FINE)) {
            List<AugmentingPath> paths = flow.getPaths();
            for (int p = 0; p < paths.size(); p++) {
                int number = p + 1;
                AugmentingPath path = paths.get(p);
                LOG.fine(() -> "run=" + runId + " step " + index + " path " + number + ": "
                        + describePath(path, nodes) + ", +" + path.getAmount());
            }
        }

        // таблица после вычитания потоков: её показывает экран, по ней же виден разрез
        int[][] residual = flow.getResidual();
        if (LOG.isLoggable(Level.FINE)) {
            for (int a = 0; a < size; a++) {
                int row = a;
                LOG.fine(() -> "run=" + runId + " step " + index + " residual row " + nodes.get(row).getLabel()
                        + ": " + Arrays.toString(residual[row]));
            }
        }

        //#xst - потоки по ветвям: Xst = R - (R - Xst). По направлению потока +f, в обратную клетку -f,
        // поэтому таблица после вычитания - ровно R минус Xst клетка в клетку
        int[][] branchFlow = new int[size][size];
        int loaded = 0;
        for (int a = 0; a < size; a++) {
            for (int b = 0; b < size; b++) {
                branchFlow[a][b] = capacity[a][b] - residual[a][b];
                if (branchFlow[a][b] > 0) {
                    loaded++;
                }
            }
        }
        if (LOG.isLoggable(Level.FINE)) {
            for (int a = 0; a < size; a++) {
                int row = a;
                LOG.fine(() -> "run=" + runId + " step " + index + " xst row " + nodes.get(row).getLabel()
                        + ": " + Arrays.toString(branchFlow[row]));
            }
        }
        checkBalance(branchFlow, flow.getValue(), source, sink, nodes, index, runId);
        int branchesWithFlow = loaded;

        // сторона S разреза - вершины, до которых от S можно дойти по ненулевым клеткам R - Xst
        boolean[] onSourceSide = new boolean[n];
        for (int vertex = 0; vertex < n; vertex++) {
            onSourceSide[vertex] = flow.isOnSourceSide(vertex);
        }
        int[] sourceSide = select(allVertices(n), onSourceSide, true);
        int[] sinkSide = select(allVertices(n), onSourceSide, false);

        //#разрез - ветви исходной сети между сторонами; их сумма обязана совпасть с потоком
        List<CutEdge> cutEdges = new ArrayList<>();
        long cutSum = 0;
        for (int u : sourceSide) {
            for (int v : sinkSide) {
                int c = network.capacity(u, v);
                if (c > 0) {
                    cutEdges.add(new CutEdge(u, v, c));
                    cutSum += c;
                }
            }
        }
        if (cutSum != flow.getValue()) {
            // по теореме Форда-Фалкерсона так быть не может; если случилось - ошибка в коде
            String message = "run=" + runId + " step " + index + ": cut " + CoreLog.vertices(sourceSide)
                    + " | " + CoreLog.vertices(sinkSide) + " sums to " + cutSum
                    + ", but max flow is " + flow.getValue();
            LOG.severe(message);
            throw new IllegalStateException(message);
        }

        //#ветвь-дерева - группа делится по разрезу, половины соединяет ветвь со значением потока
        int[] sourcePart = select(group, onSourceSide, true);
        int[] sinkPart = select(group, onSourceSide, false);
        groups.set(split, sourcePart);
        groups.add(sinkPart);
        int sinkGroup = groups.size() - 1;

        //#перевешивание (Гасфилд) - соседняя группа, висевшая на делимой, остаётся у той половины,
        // на чьей стороне разреза лежит её первая (самая маленькая) вершина. Эта вершина уже была
        // S или T на прошлом шаге, поэтому по ней и решается, куда идёт вся соседняя часть
        int moved = 0;
        for (int e = 0; e < edges.size(); e++) {
            GroupEdge edge = edges.get(e);
            if (!edge.touches(split)) {
                continue;
            }
            int neighbour = edge.other(split);
            int anchor = groups.get(neighbour)[0];
            boolean toSink = !onSourceSide[anchor];
            if (toSink) {
                edges.set(e, edge.reattach(split, sinkGroup));
                moved++;
            }
            LOG.fine(() -> String.format(Locale.ROOT,
                    "run=%s step %d: branch to group %s (value %d) stays with %s side, anchor vertex %d",
                    runId, index, CoreLog.vertices(groups.get(neighbour)), edge.getValue(),
                    toSink ? "T" : "S", anchor + 1));
        }
        int movedBranches = moved;
        edges.add(new GroupEdge(split, sinkGroup, flow.getValue(), index));
        GroupTree treeAfter = new GroupTree(groups, edges, edges.size() - 1);

        long elapsed = System.nanoTime() - startedAt;
        LOG.info(() -> String.format(Locale.ROOT,
                "run=%s step %d/%d done: max flow %d->%d = %d via %d paths, flow on %d branches, cut %s | %s (%s), "
                        + "new branch %s - %s = %d, %d old branches moved to T half, %s",
                runId, index, total, source + 1, sink + 1, flow.getValue(), flow.getPaths().size(), branchesWithFlow,
                CoreLog.vertices(sourceSide), CoreLog.vertices(sinkSide), describeCut(cutEdges),
                CoreLog.vertices(sourcePart), CoreLog.vertices(sinkPart), flow.getValue(), movedBranches,
                CoreLog.millis(elapsed)));
        if (flow.getValue() == 0) {
            LOG.warning(() -> String.format(Locale.ROOT,
                    "run=%s step %d/%d: S=%d and T=%d lie in different parts of the network, "
                            + "no path between them, branch weight 0",
                    runId, index, total, source + 1, sink + 1));
        }

        return new Step(index, total, source, sink, group, nodes, capacity, branchFlow, residual, flow.getPaths(),
                flow.getValue(), sourceSide, sinkSide, cutEdges, sourcePart, sinkPart,
                treeAfter, elapsed);
    }

    // баланс Xst: из S выходит ровно поток, в T ровно поток входит,
    // в остальных вершинах сколько вошло, столько и вышло. Иначе ошибка в коде, а не в данных
    private static void checkBalance(int[][] branchFlow, int value, int source, int sink, List<StepNode> nodes,
                                     int index, String runId) {
        for (int a = 0; a < branchFlow.length; a++) {
            long sum = 0;
            for (int b = 0; b < branchFlow.length; b++) {
                sum += branchFlow[a][b];
                if (branchFlow[a][b] != -branchFlow[b][a]) {
                    String message = "run=" + runId + " step " + index + ": xst is not antisymmetric at "
                            + nodes.get(a).getLabel() + "," + nodes.get(b).getLabel() + ": " + branchFlow[a][b]
                            + " vs " + branchFlow[b][a];
                    LOG.severe(message);
                    throw new IllegalStateException(message);
                }
            }
            long expected = a == source ? value : a == sink ? -value : 0;
            if (sum != expected) {
                String message = "run=" + runId + " step " + index + ": xst row " + nodes.get(a).getLabel()
                        + " sums to " + sum + ", expected " + expected + " (max flow " + value + ")";
                LOG.severe(message);
                throw new IllegalStateException(message);
            }
        }
    }

    private static int chooseGroup(List<int[]> groups) {
        int best = -1;
        for (int i = 0; i < groups.size(); i++) {
            int[] group = groups.get(i);
            if (group.length >= 2 && (best == -1 || group[0] < groups.get(best)[0])) {
                best = i;
            }
        }
        if (best == -1) {
            throw new IllegalStateException("no group with two or more vertices is left to split");
        }
        return best;
    }

    // ------------------------------------------------------------------ итог

    // после последнего шага каждая группа - одна вершина, ветви групп становятся ветвями дерева
    private static List<TreeEdge> finalTree(List<int[]> groups, List<GroupEdge> edges) {
        List<TreeEdge> tree = new ArrayList<>();
        for (GroupEdge edge : edges) {
            int[] a = groups.get(edge.getA());
            int[] b = groups.get(edge.getB());
            if (a.length != 1 || b.length != 1) {
                throw new IllegalStateException("after the last step a tree group still has "
                        + Math.max(a.length, b.length) + " vertices");
            }
            tree.add(new TreeEdge(Math.min(a[0], b[0]), Math.max(a[0], b[0]), edge.getValue(), edge.getStep()));
        }
        return tree;
    }

    //#итоговая-таблица - поток между i и j равен самой слабой ветви на пути между ними в дереве
    private static int[][] flowTable(int n, List<TreeEdge> tree) {
        List<List<int[]>> adjacency = new ArrayList<>();
        for (int v = 0; v < n; v++) {
            adjacency.add(new ArrayList<>());
        }
        for (TreeEdge edge : tree) {
            adjacency.get(edge.getU()).add(new int[]{edge.getV(), edge.getValue()});
            adjacency.get(edge.getV()).add(new int[]{edge.getU(), edge.getValue()});
        }

        int[][] table = new int[n][n];
        for (int from = 0; from < n; from++) {
            walk(adjacency, from, -1, Integer.MAX_VALUE, table[from]);
            table[from][from] = 0;
        }
        return table;
    }

    //#рекурсия - обход дерева вглубь; в каждую вершину несём минимум пройденного пути
    private static void walk(List<List<int[]>> adjacency, int vertex, int parent, int weakest, int[] row) {
        row[vertex] = weakest;
        for (int[] next : adjacency.get(vertex)) {
            if (next[0] != parent) {
                walk(adjacency, next[0], vertex, Math.min(weakest, next[1]), row);
            }
        }
    }

    // ------------------------------------------------------------------ мелочи

    private static int[] allVertices(int n) {
        int[] all = new int[n];
        for (int v = 0; v < n; v++) {
            all[v] = v;
        }
        return all;
    }

    private static int[] select(int[] vertices, boolean[] onSourceSide, boolean wanted) {
        int count = 0;
        for (int vertex : vertices) {
            if (onSourceSide[vertex] == wanted) {
                count++;
            }
        }
        int[] result = new int[count];
        int k = 0;
        for (int vertex : vertices) {
            if (onSourceSide[vertex] == wanted) {
                result[k++] = vertex;
            }
        }
        return result;
    }

    private static String describeParts(List<int[]> parts) {
        StringBuilder text = new StringBuilder();
        for (int[] part : parts) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(CoreLog.vertices(part));
        }
        return text.toString();
    }

    private static String describePath(AugmentingPath path, List<StepNode> nodes) {
        StringBuilder text = new StringBuilder();
        for (int node : path.getNodes()) {
            if (text.length() > 0) {
                text.append(" -> ");
            }
            text.append(nodes.get(node).getLabel());
        }
        return text.toString();
    }

    // ветви разреза так же, как на экране: меньший номер вперёд, как клетка над диагональю
    private static String describeCut(List<CutEdge> cutEdges) {
        StringBuilder text = new StringBuilder();
        for (CutEdge edge : cutEdges) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append('(').append(Math.min(edge.getU(), edge.getV()) + 1).append(',')
                    .append(Math.max(edge.getU(), edge.getV()) + 1).append(")=").append(edge.getCapacity());
        }
        return text.length() == 0 ? "no branches" : text.toString();
    }

    private static String describeTree(List<TreeEdge> tree) {
        StringBuilder text = new StringBuilder("[");
        for (TreeEdge edge : tree) {
            if (text.length() > 1) {
                text.append(", ");
            }
            text.append(edge.getU() + 1).append('-').append(edge.getV() + 1).append(':').append(edge.getValue());
        }
        return text.append(']').toString();
    }
}
