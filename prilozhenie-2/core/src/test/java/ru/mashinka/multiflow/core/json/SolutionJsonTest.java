package ru.mashinka.multiflow.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ru.mashinka.multiflow.core.TestLogging;
import ru.mashinka.multiflow.core.gomoryhu.GomoryHuSolver;
import ru.mashinka.multiflow.core.gomoryhu.MultiFlowSolution;
import ru.mashinka.multiflow.core.network.Examples;
import ru.mashinka.multiflow.core.network.Network;

class SolutionJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void logging() {
        TestLogging.init();
    }

    @Test
    void solutionIsValidJsonWithVerticesFromOne() throws Exception {
        MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(Examples.defaultNetwork()), "json-1");
        JsonNode root = MAPPER.readTree(SolutionJson.write(solution, "json-1"));

        assertEquals("json-1", root.get("runId").asText());
        assertEquals(7, root.get("n").asInt());
        assertTrue(root.get("connected").asBoolean());
        assertEquals(6, root.get("steps").size());
        assertEquals(6, root.get("tree").size());

        JsonNode first = root.get("steps").get(0);
        assertEquals(1, first.get("s").asInt());
        assertEquals(2, first.get("t").asInt());
        assertEquals(7, first.get("group").size());
        assertEquals(1, first.get("group").get(0).asInt());
        assertEquals(7, first.get("group").get(6).asInt());

        // таблица после вычитания потоков на шаге 1 примера: пути 1-2 (+2), 1-3-4-2 (+3), 1-5-6-2 (+4)
        JsonNode residual = first.get("residual");
        assertEquals(7, residual.size());
        assertEquals(0, residual.get(0).get(1).asInt(), "ветвь 1-2 насыщена");
        assertEquals(4, residual.get(1).get(0).asInt(), "обратно 2-1: 2 + 2");
        assertEquals(6, residual.get(0).get(2).asInt(), "1-3: 9 - 3");
        assertEquals(12, residual.get(2).get(0).asInt(), "3-1: 9 + 3");
        assertEquals(1, residual.get(5).get(1).asInt(), "6-2: 5 - 4");

        // таблица в JSON - та же, что в решении, только вершины считаются с единицы
        JsonNode table = root.get("table");
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < 7; j++) {
                assertEquals(solution.getFlow(i, j), table.get(i).get(j).asInt());
            }
        }
        for (JsonNode edge : root.get("tree")) {
            int u = edge.get("u").asInt();
            int v = edge.get("v").asInt();
            assertTrue(u >= 1 && u <= 7 && v >= 1 && v <= 7);
            assertEquals(solution.getFlow(u - 1, v - 1), edge.get("value").asInt());
        }
    }

    @Test
    void runIdSurvivesQuotesAndLineBreaks() throws Exception {
        String runId = "id \"quoted\" \\ line\nnext end";
        MultiFlowSolution solution = new GomoryHuSolver().solve(Network.of(Examples.defaultNetwork()), runId);
        JsonNode root = MAPPER.readTree(SolutionJson.write(solution, runId));
        assertEquals(runId, root.get("runId").asText());
    }

    @Test
    void exampleJsonCarriesTheMatrix() throws Exception {
        JsonNode root = MAPPER.readTree(SolutionJson.example(Examples.defaultNetwork()));
        assertEquals(7, root.get("n").asInt());
        assertEquals(9, root.get("capacities").get(0).get(2).asInt());
        assertEquals(9, root.get("capacities").get(2).get(0).asInt());
    }
}
