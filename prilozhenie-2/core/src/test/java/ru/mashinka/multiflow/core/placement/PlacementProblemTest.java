package ru.mashinka.multiflow.core.placement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlacementProblemTest {

    // таблицы пособия: прибыль на единицу и общая прибыль считаются так же, как в книге
    @Test
    void derivedTablesFollowTheBook() {
        PlacementProblem problem = PlacementExamples.textbook();
        assertEquals(3, problem.products());
        assertEquals(5, problem.plants());
        assertArrayEquals(new int[]{40, 73, 58, 25, 48}, problem.unitCostTable()[0], "производство + сбыт");
        assertArrayEquals(new int[]{15, -18, -3, 30, 7}, problem.unitProfitTable()[0], "55 - издержки");
        assertArrayEquals(new int[]{35, -69, 36, -20, -45}, problem.unitProfitTable()[1]);
        assertArrayEquals(new int[]{20, 17, 23, 11, 17}, problem.unitProfitTable()[2]);
        assertArrayEquals(new long[]{525_000, -630_000, -105_000, 1_050_000, 245_000}, problem.profitTable()[0]);
        assertArrayEquals(new long[]{5_600_000, -11_040_000, 5_760_000, -3_200_000, -7_200_000}, problem.profitTable()[1]);
        assertArrayEquals(new long[]{1_080_000, 918_000, 1_242_000, 594_000, 918_000}, problem.profitTable()[2]);
    }

    @Test
    void largestNumbersDoNotOverflow() {
        int[][] zero = {{0, 0}, {0, 0}};
        int[][] max = {{PlacementProblem.MAX_MONEY, PlacementProblem.MAX_MONEY},
                {PlacementProblem.MAX_MONEY, PlacementProblem.MAX_MONEY}};
        int[] volume = {PlacementProblem.MAX_VOLUME, PlacementProblem.MAX_VOLUME};
        PlacementProblem rich = PlacementProblem.of(zero, zero, volume, new int[]{1_000_000, 1_000_000});
        assertEquals(100_000_000_000_000L, rich.profit(0, 0));
        PlacementProblem poor = PlacementProblem.of(max, max, volume, new int[]{0, 0});
        assertEquals(-200_000_000_000_000L, poor.profit(1, 1));
    }

    @Test
    void copiesDoNotLeakIntoTheProblem() {
        int[][] production = PlacementExamples.production();
        PlacementProblem problem = PlacementProblem.of(production, PlacementExamples.sales(),
                PlacementExamples.volume(), PlacementExamples.price());
        production[0][0] = 999;
        problem.getProduction()[0][0] = 999;
        assertEquals(20, problem.production(0, 0));
    }

    @Test
    void rejectsMoreProductsThanPlants() {
        int[][] table = {{1, 1}, {1, 1}, {1, 1}};
        assertMessage("больше, чем предприятий", () -> PlacementProblem.of(table, table, new int[]{1, 1, 1},
                new int[]{1, 1, 1}));
    }

    @Test
    void rejectsSizesOutsideTheLimits() {
        int[][] one = {{1, 1, 1}};
        assertMessage("Видов продукции должно быть от 2 до 10", () -> PlacementProblem.of(one, one, new int[]{1},
                new int[]{1}));
        int[][] wide = new int[2][11];
        assertMessage("Предприятий должно быть от 2 до 10", () -> PlacementProblem.of(wide, wide, new int[]{1, 1},
                new int[]{1, 1}));
    }

    @Test
    void rejectsBadShapes() {
        int[][] good = {{1, 1, 1}, {1, 1, 1}};
        int[][] ragged = {{1, 1, 1}, {1, 1}};
        assertMessage("Издержки сбыта, вид 2: 2 чисел вместо 3", () -> PlacementProblem.of(good, ragged,
                new int[]{1, 1}, new int[]{1, 1}));
        assertMessage("Плановый объём: 1 чисел вместо 2", () -> PlacementProblem.of(good, good, new int[]{1},
                new int[]{1, 1}));
        assertMessage("Издержки производства, вид 2: 0 чисел вместо 3",
                () -> PlacementProblem.of(new int[][]{{1, 1, 1}, null}, good, new int[]{1, 1}, new int[]{1, 1}));
        assertMessage("Плановая цена не задана", () -> PlacementProblem.of(good, good, new int[]{1, 1}, null));
    }

    @Test
    void rejectsBadNumbers() {
        int[][] good = {{1, 1, 1}, {1, 1, 1}};
        int[][] negative = {{1, 1, 1}, {1, -4, 1}};
        int[][] huge = {{1, 1, 1_000_001}, {1, 1, 1}};
        assertMessage("Издержки производства, вид 2, предприятие 2: не может быть отрицательным (-4)",
                () -> PlacementProblem.of(negative, good, new int[]{1, 1}, new int[]{1, 1}));
        assertMessage("Издержки сбыта, вид 1, предприятие 3: не больше 1 000 000",
                () -> PlacementProblem.of(good, huge, new int[]{1, 1}, new int[]{1, 1}));
        assertMessage("Плановый объём, вид 2: должен быть не меньше 1 (0)",
                () -> PlacementProblem.of(good, good, new int[]{1, 0}, new int[]{1, 1}));
        assertMessage("Плановый объём, вид 1: не больше 100 000 000",
                () -> PlacementProblem.of(good, good, new int[]{100_000_001, 1}, new int[]{1, 1}));
        assertMessage("Плановая цена, вид 1: не может быть отрицательной (-1)",
                () -> PlacementProblem.of(good, good, new int[]{1, 1}, new int[]{-1, 1}));
    }

    private static void assertMessage(String expected, Runnable action) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(error.getMessage().contains(expected),
                "ожидалось «" + expected + "», а пришло «" + error.getMessage() + "»");
    }
}
