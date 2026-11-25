package ru.mashinka.multiflow.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ru.mashinka.multiflow.core.TestLogging;
import ru.mashinka.multiflow.core.transport.TransportExamples;
import ru.mashinka.multiflow.core.transport.TransportProblem;
import ru.mashinka.multiflow.core.transport.TransportSolver;

class TransportJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void logging() {
        TestLogging.init();
    }

    @Test
    void solutionIsValidJsonWithNumbersFromOne() throws Exception {
        JsonNode root = solve(TransportExamples.variant(1), true);

        assertEquals("json-tr", root.get("runId").asText());
        assertEquals(4, root.get("suppliers").asInt());
        assertEquals(5, root.get("consumers").asInt());
        assertEquals("shortage", root.get("type").asText());
        assertTrue(root.get("fictitiousLast").asBoolean());
        assertEquals(550, root.get("totalSupply").asLong());
        assertEquals(560, root.get("totalDemand").asLong());

        // груза не хватает: запасы вывозятся целиком, потребности закрываются не полностью
        assertEquals("eq", root.get("open").get("supplySign").asText());
        assertEquals("le", root.get("open").get("demandSign").asText());

        JsonNode closed = root.get("closed");
        assertEquals(5, closed.get("costs").size());
        assertEquals(5, closed.get("fictitiousRow").asInt(), "номер с единицы");
        assertEquals(0, closed.get("fictitiousColumn").asInt(), "нет");
        assertEquals(10, closed.get("amount").asLong());
        assertEquals(10, closed.get("supply").get(4).asLong());

        JsonNode northWest = root.get("northWest");
        assertEquals(5190, northWest.get("cost").asLong());
        assertEquals(9, northWest.get("filledCount").asInt());
        assertEquals(9, northWest.get("expectedCount").asInt());
        assertEquals(0, northWest.get("zeroCount").asInt());
        JsonNode step = northWest.get("steps").get(0);
        assertEquals(1, step.get("index").asInt());
        assertEquals(1, step.get("row").asInt());
        assertEquals(1, step.get("column").asInt());
        assertEquals(50, step.get("amount").asLong());
        assertEquals("column", step.get("crossed").asText());
        assertEquals(1, step.get("candidates").size());
        assertEquals(0, step.get("demandAfter").get(0).asLong());
        assertTrue(step.get("columnsCrossed").get(0).asBoolean());
        assertEquals(110, northWest.get("amount").get(1).get(1).asLong());
        assertTrue(northWest.get("filled").get(1).get(1).asBoolean());

        assertEquals(3280, root.get("minimumCost").get("cost").asLong());
        assertEquals(2, root.get("otherCosts").size());
        assertEquals(2830, root.get("otherCosts").get(0).asLong());
        assertFalse(root.get("otherCostsTruncated").asBoolean());

        assertEquals(2670, root.get("optimal").get("cost").asLong());
        assertEquals(0, root.get("optimal").get("steps").size());
        assertTrue(root.get("optimalUnique").isBoolean());
    }

    @Test
    void signsOfTheOpenModelFollowTheType() throws Exception {
        JsonNode excess = solve(TransportExamples.variant(2), false);
        assertEquals("excess", excess.get("type").asText());
        assertEquals("le", excess.get("open").get("supplySign").asText(), "запасов больше - вывозится не больше запаса");
        assertEquals("eq", excess.get("open").get("demandSign").asText());
        assertEquals(6, excess.get("closed").get("fictitiousColumn").asInt());
        assertEquals(0, excess.get("closed").get("fictitiousRow").asInt());
        assertFalse(excess.get("fictitiousLast").asBoolean());

        JsonNode closed = solve(TransportProblem.of(new int[][]{{1, 2}, {3, 4}}, new int[]{5, 5}, new int[]{4, 6}), true);
        assertEquals("closed", closed.get("type").asText());
        assertEquals("eq", closed.get("open").get("supplySign").asText());
        assertEquals("eq", closed.get("open").get("demandSign").asText());
        assertEquals(2, closed.get("closed").get("costs").size());
    }

    @Test
    void examplesCarryAllVariants() throws Exception {
        JsonNode variants = MAPPER.readTree(TransportJson.examples()).get("variants");
        assertEquals(27, variants.size());
        assertEquals(1, variants.get(0).get("number").asInt());
        assertEquals(13, variants.get(0).get("costs").get(0).get(0).asInt());
        assertEquals(27, variants.get(26).get("number").asInt());
        assertEquals(200, variants.get(26).get("supply").get(3).asInt());
        assertEquals(30, variants.get(26).get("demand").get(3).asInt());
    }

    private static JsonNode solve(TransportProblem problem, boolean fictitiousLast) throws Exception {
        return MAPPER.readTree(TransportJson.write(
                new TransportSolver().solve(problem, fictitiousLast, "json-tr"), "json-tr"));
    }
}
