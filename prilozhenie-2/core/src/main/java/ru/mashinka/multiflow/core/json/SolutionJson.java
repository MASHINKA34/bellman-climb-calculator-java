package ru.mashinka.multiflow.core.json;

import ru.mashinka.multiflow.core.flow.AugmentingPath;
import ru.mashinka.multiflow.core.gomoryhu.CutEdge;
import ru.mashinka.multiflow.core.gomoryhu.GroupEdge;
import ru.mashinka.multiflow.core.gomoryhu.GroupTree;
import ru.mashinka.multiflow.core.gomoryhu.MultiFlowSolution;
import ru.mashinka.multiflow.core.gomoryhu.Step;
import ru.mashinka.multiflow.core.gomoryhu.StepNode;
import ru.mashinka.multiflow.core.gomoryhu.TreeEdge;

// ответ для страницы в JSON. Пишется в ядре, чтобы приложение и сайт отдавали
// один и тот же текст до последнего символа
// номера вершин - с единицы, как их видит пользователь;
// индексы в массивах (строки таблицы шага, группы дерева) - с нуля
public final class SolutionJson {

    private SolutionJson() {
    }

    // встроенный пример для кнопки «Пример»
    public static String example(int[][] capacities) {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("n").value(capacities.length);
        json.name("capacities").matrix(capacities);
        json.endObject();
        return json.toString();
    }

    public static String write(MultiFlowSolution solution, String runId) {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("runId").value(runId);
        json.name("n").value(solution.getSize());
        json.name("capacities").matrix(solution.getNetwork().toMatrix());
        json.name("connected").value(solution.isConnected());

        json.name("components").beginArray();
        for (int[] part : solution.getComponents()) {
            json.vertices(part);
        }
        json.endArray();

        json.name("steps").beginArray();
        for (Step step : solution.getSteps()) {
            writeStep(json, step);
        }
        json.endArray();

        json.name("tree").beginArray();
        for (TreeEdge edge : solution.getTree()) {
            json.beginObject()
                    .name("u").value(edge.getU() + 1)
                    .name("v").value(edge.getV() + 1)
                    .name("value").value(edge.getValue())
                    .name("step").value(edge.getStep())
                    .endObject();
        }
        json.endArray();

        json.name("table").matrix(solution.getTable());
        json.name("elapsedMs").value(solution.getElapsedNanos() / 1_000_000.0);
        json.endObject();
        return json.toString();
    }

    private static void writeStep(JsonWriter json, Step step) {
        json.beginObject();
        json.name("index").value(step.getIndex());
        json.name("total").value(step.getTotal());
        json.name("s").value(step.getSource() + 1);
        json.name("t").value(step.getSink() + 1);
        json.name("group").vertices(step.getGroup());

        // строки и столбцы таблицы шага; S - строка 0, T - строка 1
        json.name("nodes").beginArray();
        for (StepNode node : step.getNodes()) {
            json.beginObject()
                    .name("label").value(node.getLabel())
                    .name("members").vertices(node.getMembers())
                    .name("contracted").value(node.isContracted())
                    .endObject();
        }
        json.endArray();
        json.name("capacities").matrix(step.getCapacity());

        // пути - индексы строк таблицы шага
        json.name("paths").beginArray();
        for (AugmentingPath path : step.getPaths()) {
            json.beginObject().name("nodes").beginArray();
            for (int node : path.getNodes()) {
                json.value(node);
            }
            json.endArray().name("amount").value(path.getAmount()).endObject();
        }
        json.endArray();

        json.name("maxFlow").value(step.getMaxFlow());
        json.name("sourceSide").vertices(step.getSourceSide());
        json.name("sinkSide").vertices(step.getSinkSide());

        json.name("cutEdges").beginArray();
        for (CutEdge edge : step.getCutEdges()) {
            json.beginObject()
                    .name("u").value(edge.getU() + 1)
                    .name("v").value(edge.getV() + 1)
                    .name("capacity").value(edge.getCapacity())
                    .endObject();
        }
        json.endArray();

        json.name("sourcePart").vertices(step.getSourcePart());
        json.name("sinkPart").vertices(step.getSinkPart());
        json.name("tree");
        writeGroupTree(json, step.getTreeAfter());
        json.name("elapsedMs").value(step.getElapsedNanos() / 1_000_000.0);
        json.endObject();
    }

    // a и b у ветвей - индексы в groups
    private static void writeGroupTree(JsonWriter json, GroupTree tree) {
        json.beginObject();
        json.name("groups").beginArray();
        for (int i = 0; i < tree.getGroupCount(); i++) {
            json.vertices(tree.getGroup(i));
        }
        json.endArray();

        json.name("edges").beginArray();
        for (GroupEdge edge : tree.getEdges()) {
            json.beginObject()
                    .name("a").value(edge.getA())
                    .name("b").value(edge.getB())
                    .name("value").value(edge.getValue())
                    .name("step").value(edge.getStep())
                    .endObject();
        }
        json.endArray();
        json.name("newEdge").value(tree.getNewEdge());
        json.endObject();
    }
}
