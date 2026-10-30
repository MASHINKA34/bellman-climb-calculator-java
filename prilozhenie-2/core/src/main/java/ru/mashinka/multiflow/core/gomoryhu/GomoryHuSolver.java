package ru.mashinka.multiflow.core.gomoryhu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
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
//
// В начале дерево - одна группа со всеми вершинами. Каждый шаг берёт группу,
// в которой больше одной вершины, выбирает в ней пару S и T, стягивает всё, что
// лежит за пределами группы, считает максимальный поток S -> T и делит группу
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

        //#стягивание - всё, что висит на группе через одну ветвь дерева, сжимается в одну вершину
        List<Integer> hangingEdges = new ArrayList<>();
        List<int[]> parts = new ArrayList<>();
        for (int e = 0; e < edges.size(); e++) {
            GroupEdge edge = edges.get(e);
            if (edge.touches(split)) {
                hangingEdges.add(e);
                parts.add(collectPart(groups, edges, edge.other(split), split));
            }
        }

        // буквы A, B, C... раздаются по возрастанию самой маленькой вершины части
        Integer[] order = new Integer[parts.size()];
        for (int k = 0; k < order.length; k++) {
            order[k] = k;
        }
        Arrays.sort(order, (x, y) -> Integer.compare(parts.get(x)[0], parts.get(y)[0]));

        // вершины сети шага: сначала вершины группы (S - строка 0, T - строка 1), потом части
        List<StepNode> nodes = new ArrayList<>();
        int[] nodeOf = new int[n];
        Arrays.fill(nodeOf, -1);
        for (int vertex : group) {
            nodeOf[vertex] = nodes.size();
            nodes.add(StepNode.single(vertex));
        }
        for (int k = 0; k < order.length; k++) {
            int[] members = parts.get(order[k]);
            for (int vertex : members) {
                nodeOf[vertex] = nodes.size();
            }
            nodes.add(StepNode.contracted(String.valueOf((char) ('A' + k)), members));
        }
        for (int vertex = 0; vertex < n; vertex++) {
            if (nodeOf[vertex] == -1) {
                // группы дерева всегда делят вершины без остатка; иначе это ошибка в коде
                String message = "run=" + runId + " step " + index + ": vertex " + (vertex + 1)
                        + " belongs to no node of the step network";
                LOG.severe(message);
                throw new IllegalStateException(message);
            }
        }

        // таблица шага: ветви, ведущие в одну стянутую часть, складываются
        int size = nodes.size();
        int[][] capacity = new int[size][size];
        for (int u = 0; u < n; u++) {
            for (int v = u + 1; v < n; v++) {
                int c = network.capacity(u, v);
                int a = nodeOf[u];
                int b = nodeOf[v];
                if (c > 0 && a != b) {
                    capacity[a][b] += c;
                    capacity[b][a] += c;
                }
            }
        }

        LOG.info(() -> String.format(Locale.ROOT, "run=%s step %d/%d: split group %s with S=%d T=%d, contracted %s",
                runId, index, total, CoreLog.vertices(group), source + 1, sink + 1, describeContracted(nodes)));
        if (LOG.isLoggable(Level.FINE)) {
            for (int a = 0; a < size; a++) {
                int row = a;
                LOG.fine(() -> "run=" + runId + " step " + index + " table row " + nodes.get(row).getLabel()
                        + ": " + Arrays.toString(capacity[row]));
            }
        }

        FlowResult flow = MaxFlow.compute(capacity, 0, 1);

        if (LOG.isLoggable(Level.FINE)) {
            List<AugmentingPath> paths = flow.getPaths();
            for (int p = 0; p < paths.size(); p++) {
                int number = p + 1;
                AugmentingPath path = paths.get(p);
                LOG.fine(() -> "run=" + runId + " step " + index + " path " + number + ": "
                        + describePath(path, nodes) + ", +" + path.getAmount());
            }
        }

        // разрез переводим обратно в исходные вершины: стянутая часть целиком на одной стороне
        boolean[] onSourceSide = new boolean[n];
        for (int vertex = 0; vertex < n; vertex++) {
            onSourceSide[vertex] = flow.isOnSourceSide(nodeOf[vertex]);
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

        //#перевешивание - ветвь к стянутой части остаётся у той половины группы,
        // на чьей стороне разреза оказалась эта часть
        for (int k = 0; k < hangingEdges.size(); k++) {
            int e = hangingEdges.get(k);
            if (!onSourceSide[parts.get(k)[0]]) {
                edges.set(e, edges.get(e).reattach(split, sinkGroup));
            }
        }
        edges.add(new GroupEdge(split, sinkGroup, flow.getValue(), index));
        GroupTree treeAfter = new GroupTree(groups, edges, edges.size() - 1);

        long elapsed = System.nanoTime() - startedAt;
        LOG.info(() -> String.format(Locale.ROOT,
                "run=%s step %d/%d done: max flow %d->%d = %d via %d paths, cut %s | %s (%s), new branch %s - %s = %d, %s",
                runId, index, total, source + 1, sink + 1, flow.getValue(), flow.getPaths().size(),
                CoreLog.vertices(sourceSide), CoreLog.vertices(sinkSide), describeCut(cutEdges),
                CoreLog.vertices(sourcePart), CoreLog.vertices(sinkPart), flow.getValue(),
                CoreLog.millis(elapsed)));
        if (flow.getValue() == 0) {
            LOG.warning(() -> String.format(Locale.ROOT,
                    "run=%s step %d/%d: S=%d and T=%d lie in different parts of the network, "
                            + "no path between them, branch weight 0",
                    runId, index, total, source + 1, sink + 1));
        }

        return new Step(index, total, source, sink, group, nodes, capacity, flow.getPaths(),
                flow.getValue(), sourceSide, sinkSide, cutEdges, sourcePart, sinkPart,
                treeAfter, elapsed);
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

    // все вершины части дерева, висящей на ветви: обходим группы, не заходя в делимую
    private static int[] collectPart(List<int[]> groups, List<GroupEdge> edges, int start, int excluded) {
        boolean[] seen = new boolean[groups.size()];
        seen[excluded] = true;
        seen[start] = true;
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(start);

        List<Integer> vertices = new ArrayList<>();
        while (!stack.isEmpty()) {
            int current = stack.pop();
            for (int vertex : groups.get(current)) {
                vertices.add(vertex);
            }
            for (GroupEdge edge : edges) {
                if (edge.touches(current)) {
                    int next = edge.other(current);
                    if (!seen[next]) {
                        seen[next] = true;
                        stack.push(next);
                    }
                }
            }
        }

        int[] result = new int[vertices.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = vertices.get(i);
        }
        Arrays.sort(result);
        return result;
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

    private static String describeContracted(List<StepNode> nodes) {
        StringBuilder text = new StringBuilder();
        for (StepNode node : nodes) {
            if (node.isContracted()) {
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(node.getLabel()).append('=').append(CoreLog.vertices(node.getMembers()));
            }
        }
        return text.length() == 0 ? "nothing" : text.toString();
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

    private static String describeCut(List<CutEdge> cutEdges) {
        StringBuilder text = new StringBuilder();
        for (CutEdge edge : cutEdges) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append('(').append(edge.getU() + 1).append(',').append(edge.getV() + 1).append(")=")
                    .append(edge.getCapacity());
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
