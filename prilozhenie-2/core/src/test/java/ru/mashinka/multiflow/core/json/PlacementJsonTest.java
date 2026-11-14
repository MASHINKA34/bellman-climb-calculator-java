package ru.mashinka.multiflow.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ru.mashinka.multiflow.core.TestLogging;
import ru.mashinka.multiflow.core.placement.HungarianSolver;
import ru.mashinka.multiflow.core.placement.PlacementExamples;
import ru.mashinka.multiflow.core.placement.PlacementSolution;

class PlacementJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void logging() {
        TestLogging.init();
    }

    @Test
    void solutionIsValidJsonWithNumbersFromOne() throws Exception {
        PlacementSolution solution = new HungarianSolver().solve(PlacementExamples.textbook(), "json-pl");
        JsonNode root = MAPPER.readTree(PlacementJson.write(solution, "json-pl"));

        assertEquals("json-pl", root.get("runId").asText());
        assertEquals(3, root.get("products").asInt());
        assertEquals(5, root.get("plants").asInt());
        assertEquals(2, root.get("fictitious").asInt());
        assertEquals(7_892_000L, root.get("totalProfit").asLong());
        assertEquals(5_760_000L, root.get("maxProfit").asLong());
        assertEquals(-11_040_000L, root.get("profit").get(1).get(1).asLong());
        assertEquals(30, root.get("unitProfit").get(0).get(3).asInt());
        assertEquals(5, root.get("cost").size());

        JsonNode placements = root.get("placements");
        assertEquals(3, placements.size());
        assertEquals(1, placements.get(0).get("product").asInt());
        assertEquals(4, placements.get(0).get("plant").asInt());
        assertEquals(1_050_000L, placements.get(0).get("profit").asLong());

        JsonNode iterations = root.get("iterations");
        assertEquals(2, iterations.size());
        JsonNode first = iterations.get(0);
        assertEquals(4_710_000L, first.get("reduction").get("rowMin").get(0).asLong());
        assertFalse(first.get("assignment").get("complete").asBoolean());
        assertTrue(first.get("assignment").get("columns").isNull());
        assertEquals("choice", first.get("assignment").get("picks").get(2).get("rule").asText());
        assertEquals(1, first.get("assignment").get("marks").get(0).get(3).asInt(), "ноль (1,4) назначен по правилу");
        assertEquals(2, first.get("assignment").get("marks").get(3).get(0).asInt(), "ноль (4,1) назначен на выбор");
        assertEquals(3, first.get("assignment").get("marks").get(2).get(2).asInt(), "ноль (3,3) вычеркнут");
        JsonNode cover = first.get("cover");
        assertEquals(4, cover.get("lines").size());
        assertEquals("row", cover.get("lines").get(0).get("type").asText());
        assertEquals(4, cover.get("lines").get(0).get("index").asInt());
        assertEquals(160_000L, cover.get("minimum").asLong());
        assertTrue(cover.get("rows").get(3).asBoolean());

        JsonNode second = iterations.get(1);
        assertTrue(second.get("reduction").isNull());
        assertTrue(second.get("cover").isNull());
        assertTrue(second.get("assignment").get("complete").asBoolean());
        assertEquals(3, second.get("assignment").get("columns").get(0).asInt(), "столбцы в матрице - с нуля");
        assertEquals(0, root.get("alternativeCount").asInt());
    }

    @Test
    void exampleJsonCarriesTheInput() throws Exception {
        JsonNode root = MAPPER.readTree(PlacementJson.example(PlacementExamples.production(),
                PlacementExamples.sales(), PlacementExamples.volume(), PlacementExamples.price()));
        assertEquals(3, root.get("products").asInt());
        assertEquals(5, root.get("plants").asInt());
        assertEquals(35, root.get("production").get(0).get(4).asInt());
        assertEquals(90, root.get("sales").get(1).get(1).asInt());
        assertEquals(160_000, root.get("volume").get(1).asInt());
        assertEquals(30, root.get("price").get(2).asInt());
    }
}
