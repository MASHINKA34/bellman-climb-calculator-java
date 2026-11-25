package ru.mashinka.multiflow.core.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransportProblemTest {

    private static final int[][] COST = {{1, 2, 3}, {4, 5, 6}};

    @Test
    void examplesFollowThePaper() {
        assertEquals(27, TransportExamples.count());
        TransportProblem first = TransportExamples.variant(1);
        assertEquals(4, first.suppliers());
        assertEquals(5, first.consumers());
        assertArrayEquals(new int[]{13, 5, 13, 2, 5}, first.getCost()[0]);
        assertArrayEquals(new int[]{100, 130, 140, 180}, first.getSupply());
        assertArrayEquals(new int[]{50, 160, 130, 10, 210}, first.getDemand());
        assertEquals(550, first.getTotalSupply());
        assertEquals(560, first.getTotalDemand());
        assertArrayEquals(new int[]{7, 9, 13, 7, 7}, TransportExamples.variant(27).getCost()[0]);
        for (int number = 1; number <= TransportExamples.count(); number++) {
            TransportProblem problem = TransportExamples.variant(number);
            assertEquals(4, problem.suppliers(), "вариант " + number);
            assertEquals(5, problem.consumers(), "вариант " + number);
            assertTrue(problem.type() != TransportProblem.Type.CLOSED, "в задании все варианты открытые");
        }
        assertMessage("Вариант должен быть от 1 до 27, а задан 28", () -> TransportExamples.variant(28));
        assertMessage("Вариант должен быть от 1 до 27, а задан 0", () -> TransportExamples.variant(0));
    }

    @Test
    void copiesDoNotLeakIntoTheProblem() {
        int[][] cost = {{1, 2}, {3, 4}};
        int[] supply = {5, 6};
        TransportProblem problem = TransportProblem.of(cost, supply, new int[]{7, 8});
        cost[0][0] = 99;
        supply[0] = 99;
        problem.getCost()[0][1] = 99;
        problem.balance().getCost()[0][0] = 99;
        problem.balance().getSupply()[0] = 99;
        assertEquals(1, problem.cost(0, 0));
        assertEquals(2, problem.cost(0, 1));
        assertEquals(5, problem.supply(0));
        assertEquals(1, problem.balance().cost(0, 0));
        TransportExamples.variant(1).getCost()[0][0] = 99;
        assertEquals(13, TransportExamples.variant(1).cost(0, 0));
    }

    @Test
    void rejectsSizesOutsideTheLimits() {
        assertMessage("Поставщиков должно быть от 2 до 10, а задано 1",
                () -> TransportProblem.of(new int[][]{{1, 2}}, new int[]{1}, new int[]{1, 1}));
        assertMessage("Потребителей должно быть от 2 до 10, а задано 11",
                () -> TransportProblem.of(new int[2][11], new int[]{1, 1}, new int[11]));
        assertMessage("Потребителей должно быть от 2 до 10, а задано 0",
                () -> TransportProblem.of(new int[][]{null, {1, 2}}, new int[]{1, 1}, new int[]{1, 1}));
    }

    @Test
    void rejectsBadShapes() {
        assertMessage("Тарифы, поставщик 2: 2 чисел вместо 3",
                () -> TransportProblem.of(new int[][]{{1, 2, 3}, {4, 5}}, new int[]{1, 1}, new int[]{1, 1, 1}));
        assertMessage("Запас: 1 чисел вместо 2", () -> TransportProblem.of(COST, new int[]{1}, new int[]{1, 1, 1}));
        assertMessage("Потребность: 2 чисел вместо 3",
                () -> TransportProblem.of(COST, new int[]{1, 1}, new int[]{1, 1}));
        assertMessage("Таблица тарифов не задана", () -> TransportProblem.of(null, new int[]{1}, new int[]{1}));
        assertMessage("Запасы поставщиков не заданы", () -> TransportProblem.of(COST, null, new int[]{1, 1, 1}));
        assertMessage("Потребности потребителей не заданы", () -> TransportProblem.of(COST, new int[]{1, 1}, null));
    }

    @Test
    void rejectsBadNumbers() {
        assertMessage("Тариф, поставщик 1, потребитель 2: не может быть меньше нуля (-2)",
                () -> TransportProblem.of(new int[][]{{1, -2}, {3, 4}}, new int[]{1, 1}, new int[]{1, 1}));
        assertMessage("Тариф, поставщик 2, потребитель 1: не больше 1 000 000 (1000001)",
                () -> TransportProblem.of(new int[][]{{1, 2}, {1_000_001, 4}}, new int[]{1, 1}, new int[]{1, 1}));
        assertMessage("Запас, поставщик 2: нужно не меньше 1 (0)",
                () -> TransportProblem.of(COST, new int[]{1, 0}, new int[]{1, 1, 1}));
        assertMessage("Потребность, потребитель 3: не больше 1 000 000 (2000000)",
                () -> TransportProblem.of(COST, new int[]{1, 1}, new int[]{1, 1, 2_000_000}));
    }

    private static void assertMessage(String expected, Runnable action) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(error.getMessage().contains(expected),
                "ожидалось «" + expected + "», а пришло «" + error.getMessage() + "»");
    }
}
