package ru.mashinka.multiflow.core.json;

import ru.mashinka.multiflow.core.placement.HungarianIteration;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Assignment;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Cover;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Line;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Pick;
import ru.mashinka.multiflow.core.placement.HungarianIteration.Reduction;
import ru.mashinka.multiflow.core.placement.PlacementProblem;
import ru.mashinka.multiflow.core.placement.PlacementSolution;

// ответ вкладки «Размещение» для страницы. Пишется в ядре по той же причине, что и
// SolutionJson: приложение и сайт отдают один и тот же текст.
// Номера видов и предприятий в полях product/plant/row/column/index - с единицы;
// позиции в массивах (строки и столбцы матриц) - с нуля.
// Деньги - в рублях целыми числами; в тыс. руб. их переводит страница.
public final class PlacementJson {

    private PlacementJson() {
    }

    // встроенный пример для кнопки «Пример» во вкладке «Размещение»
    public static String example(int[][] production, int[][] sales, int[] volume, int[] price) {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("products").value(production.length);
        json.name("plants").value(production[0].length);
        json.name("production").matrix(production);
        json.name("sales").matrix(sales);
        json.name("volume").numbers(volume);
        json.name("price").numbers(price);
        json.endObject();
        return json.toString();
    }

    public static String write(PlacementSolution solution, String runId) {
        PlacementProblem problem = solution.getProblem();
        int m = problem.products();

        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("runId").value(runId);
        json.name("products").value(m);
        json.name("plants").value(problem.plants());
        json.name("fictitious").value(solution.getFictitious());

        json.name("production").matrix(problem.getProduction());
        json.name("sales").matrix(problem.getSales());
        json.name("volume").numbers(problem.getVolume());
        json.name("price").numbers(problem.getPrice());
        json.name("unitCost").matrix(problem.unitCostTable());
        json.name("unitProfit").matrix(problem.unitProfitTable());
        json.name("profit").matrix(problem.profitTable());

        json.name("maxProfit").value(solution.getMaxProfit());
        json.name("cost").matrix(solution.getCost());

        json.name("iterations").beginArray();
        for (HungarianIteration iteration : solution.getIterations()) {
            writeIteration(json, iteration);
        }
        json.endArray();

        json.name("columns").numbers(solution.getColumns());
        json.name("placements").beginArray();
        for (int i = 0; i < m; i++) {
            int plant = solution.plantOf(i);
            json.beginObject()
                    .name("product").value(i + 1)
                    .name("plant").value(plant + 1)
                    .name("profit").value(problem.profit(i, plant))
                    .endObject();
        }
        json.endArray();
        json.name("totalProfit").value(solution.getTotalProfit());

        // другие размещения: предприятие (с единицы) для каждого вида
        json.name("alternatives").beginArray();
        for (int[] plan : solution.getAlternatives()) {
            json.beginArray();
            for (int plant : plan) {
                json.value(plant + 1);
            }
            json.endArray();
        }
        json.endArray();
        json.name("alternativeCount").value(solution.getAlternativeCount());
        json.name("alternativesTruncated").value(solution.isAlternativesTruncated());

        json.name("elapsedMs").value(solution.getElapsedNanos() / 1_000_000.0);
        json.endObject();
        return json.toString();
    }

    private static void writeIteration(JsonWriter json, HungarianIteration iteration) {
        json.beginObject();
        json.name("index").value(iteration.getIndex());

        Reduction reduction = iteration.getReduction();
        json.name("reduction");
        if (reduction == null) {
            json.value((String) null);
        } else {
            json.beginObject()
                    .name("rowMin").numbers(reduction.getRowMin())
                    .name("afterRows").matrix(reduction.getAfterRows())
                    .name("columnMin").numbers(reduction.getColumnMin())
                    .name("afterColumns").matrix(reduction.getAfterColumns())
                    .endObject();
        }

        Assignment assignment = iteration.getAssignment();
        json.name("assignment").beginObject();
        json.name("matrix").matrix(assignment.getMatrix());
        // 0 - не ноль, 1 - назначен по правилу, 2 - назначен на выбор, 3 - вычеркнут
        json.name("marks").matrix(assignment.getMarks());
        json.name("picks").beginArray();
        for (Pick pick : assignment.getPicks()) {
            json.beginObject()
                    .name("row").value(pick.getRow() + 1)
                    .name("column").value(pick.getColumn() + 1)
                    .name("rule").value(ruleName(pick.getRule()))
                    .endObject();
        }
        json.endArray();
        json.name("maxMatching").value(assignment.getMaxMatching());
        json.name("complete").value(assignment.isComplete());
        json.name("completedBySearch").value(assignment.isCompletedBySearch());
        json.name("columns");
        if (assignment.isComplete()) {
            json.numbers(assignment.getColumns());
        } else {
            json.value((String) null);
        }
        json.endObject();

        Cover cover = iteration.getCover();
        json.name("cover");
        if (cover == null) {
            json.value((String) null);
        } else {
            json.beginObject();
            json.name("lines").beginArray();
            for (Line line : cover.getLines()) {
                json.beginObject()
                        .name("type").value(line.isRow() ? "row" : "column")
                        .name("index").value(line.getIndex() + 1)
                        .name("zeros").value(line.getZeros())
                        .endObject();
            }
            json.endArray();
            json.name("ruleLineCount").value(cover.getRuleLineCount());
            json.name("replaced").value(cover.isReplaced());
            json.name("rows").flags(cover.getRows());
            json.name("columns").flags(cover.getColumns());
            json.name("minimum").value(cover.getMinimum());
            json.name("after").matrix(cover.getAfter());
            json.endObject();
        }
        json.endObject();
    }

    private static String ruleName(Pick.Rule rule) {
        switch (rule) {
            case ROW:
                return "row";
            case COLUMN:
                return "column";
            default:
                return "choice";
        }
    }
}
