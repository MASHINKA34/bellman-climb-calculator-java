package ru.mashinka.multiflow.core.json;

import ru.mashinka.multiflow.core.transport.TransportExamples;
import ru.mashinka.multiflow.core.transport.TransportPlan;
import ru.mashinka.multiflow.core.transport.TransportProblem;
import ru.mashinka.multiflow.core.transport.TransportProblem.Balanced;
import ru.mashinka.multiflow.core.transport.TransportSolution;
import ru.mashinka.multiflow.core.transport.TransportStep;

// ответ вкладки «Транспортная» для страницы. Пишется в ядре по той же причине, что и
// SolutionJson: приложение и сайт отдают один и тот же текст.
// Номера строк и столбцов в полях row/column - с единицы; позиции в массивах - с нуля.
public final class TransportJson {

    private TransportJson() {
    }

    // все встроенные варианты сразу: страница показывает их списком
    public static String examples() {
        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("variants").beginArray();
        for (int number = 1; number <= TransportExamples.count(); number++) {
            TransportProblem problem = TransportExamples.variant(number);
            json.beginObject();
            json.name("number").value(number);
            json.name("costs").matrix(problem.getCost());
            json.name("supply").numbers(problem.getSupply());
            json.name("demand").numbers(problem.getDemand());
            json.endObject();
        }
        json.endArray();
        json.endObject();
        return json.toString();
    }

    public static String write(TransportSolution solution, String runId) {
        TransportProblem problem = solution.getProblem();
        Balanced table = solution.getBalanced();

        JsonWriter json = new JsonWriter();
        json.beginObject();
        json.name("runId").value(runId);
        json.name("suppliers").value(problem.suppliers());
        json.name("consumers").value(problem.consumers());
        json.name("costs").matrix(problem.getCost());
        json.name("supply").numbers(problem.getSupply());
        json.name("demand").numbers(problem.getDemand());
        json.name("totalSupply").value(problem.getTotalSupply());
        json.name("totalDemand").value(problem.getTotalDemand());
        json.name("type").value(typeName(problem.type()));
        json.name("fictitiousLast").value(solution.isFictitiousLast());

        // открытая модель: чего не хватает, то в ограничениях «равно», чего больше - «не больше»
        json.name("open").beginObject()
                .name("supplySign").value(problem.type() == TransportProblem.Type.EXCESS ? "le" : "eq")
                .name("demandSign").value(problem.type() == TransportProblem.Type.SHORTAGE ? "le" : "eq")
                .endObject();

        // закрытая модель: таблица вместе с фиктивной строкой или столбцом; номер фиктивной - с единицы, 0 - нет
        json.name("closed").beginObject()
                .name("costs").matrix(table.getCost())
                .name("supply").numbers(table.getSupply())
                .name("demand").numbers(table.getDemand())
                .name("fictitiousRow").value(table.getFictitiousRow() + 1)
                .name("fictitiousColumn").value(table.getFictitiousColumn() + 1)
                .name("amount").value(problem.fictitiousAmount())
                .endObject();

        json.name("northWest");
        writePlan(json, solution.getNorthWest(), table);

        json.name("minimumCost");
        writePlan(json, solution.getMinimumCost(), table);
        json.name("otherCosts").beginArray();
        for (long cost : solution.getOtherCosts()) {
            json.value(cost);
        }
        json.endArray();
        json.name("otherCostsTruncated").value(solution.isOtherCostsTruncated());

        json.name("optimal");
        writePlan(json, solution.getOptimal(), table);
        json.name("optimalUnique").value(solution.isOptimalUnique());

        json.name("elapsedMs").value(solution.getElapsedNanos() / 1_000_000.0);
        json.endObject();
        return json.toString();
    }

    private static void writePlan(JsonWriter json, TransportPlan plan, Balanced table) {
        json.beginObject();
        json.name("amount").matrix(plan.getAmount());
        json.name("filled").beginArray();
        for (boolean[] row : plan.getFilled()) {
            json.flags(row);
        }
        json.endArray();
        json.name("cost").value(plan.getCost());
        json.name("filledCount").value(plan.filledCount());
        json.name("expectedCount").value(table.rows() + table.columns() - 1);
        json.name("zeroCount").value(plan.zeroCount());
        json.name("steps").beginArray();
        for (TransportStep step : plan.getSteps()) {
            json.beginObject();
            json.name("index").value(step.getIndex());
            json.name("row").value(step.getRow() + 1);
            json.name("column").value(step.getColumn() + 1);
            json.name("cost").value(step.getCost());
            json.name("amount").value(step.getAmount());
            // из каких клеток выбирали (с равным тарифом), выбранная - первая
            json.name("candidates").beginArray();
            for (int[] cell : step.getCandidates()) {
                json.vertices(cell);
            }
            json.endArray();
            json.name("crossed").value(step.getCrossed().getId());
            json.name("degenerate").value(step.isDegenerate());
            json.name("supplyAfter").numbers(step.getSupplyAfter());
            json.name("demandAfter").numbers(step.getDemandAfter());
            json.name("rowsCrossed").flags(step.getRowsCrossed());
            json.name("columnsCrossed").flags(step.getColumnsCrossed());
            json.endObject();
        }
        json.endArray();
        json.endObject();
    }

    private static String typeName(TransportProblem.Type type) {
        switch (type) {
            case SHORTAGE:
                return "shortage";
            case EXCESS:
                return "excess";
            default:
                return "closed";
        }
    }
}
