package ru.mashinka.multiflow.core.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ru.mashinka.multiflow.core.TestLogging;

class NetworkTest {

    @BeforeAll
    static void logging() {
        TestLogging.init();
    }

    @Test
    void exampleIsValidSymmetricSevenBySeven() {
        Network network = Network.of(Examples.defaultNetwork());
        assertEquals(7, network.size());
        assertEquals(12, network.branchCount());
        assertEquals(72, network.totalCapacity());
        assertEquals(1, network.components().size());
    }

    @Test
    void matrixIsCopiedBothWays() {
        int[][] matrix = Examples.defaultNetwork();
        Network network = Network.of(matrix);
        matrix[0][2] = 100;
        assertEquals(9, network.capacity(0, 2));

        int[][] copy = network.toMatrix();
        copy[0][2] = 100;
        assertEquals(9, network.capacity(0, 2));
    }

    @Test
    void rejectsAsymmetricMatrixAndNamesBothCells() {
        int[][] matrix = Examples.defaultNetwork();
        matrix[1][3] = 9;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Network.of(matrix));
        assertTrue(error.getMessage().contains("(2,4) = 9"), error.getMessage());
        assertTrue(error.getMessage().contains("(4,2) = 7"), error.getMessage());
    }

    @Test
    void rejectsNegativeCapacity() {
        int[][] matrix = Examples.defaultNetwork();
        matrix[0][1] = -2;
        matrix[1][0] = -2;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Network.of(matrix));
        assertTrue(error.getMessage().contains("(1,2)"), error.getMessage());
    }

    @Test
    void rejectsTooLargeCapacity() {
        int[][] matrix = Examples.defaultNetwork();
        matrix[0][1] = Network.MAX_CAPACITY + 1;
        matrix[1][0] = Network.MAX_CAPACITY + 1;
        assertThrows(IllegalArgumentException.class, () -> Network.of(matrix));
    }

    @Test
    void rejectsNonZeroDiagonal() {
        int[][] matrix = Examples.defaultNetwork();
        matrix[2][2] = 1;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Network.of(matrix));
        assertTrue(error.getMessage().contains("(3,3)"), error.getMessage());
    }

    @Test
    void rejectsWrongSizes() {
        assertThrows(IllegalArgumentException.class, () -> Network.of(null));
        assertThrows(IllegalArgumentException.class, () -> Network.of(new int[1][1]));
        assertThrows(IllegalArgumentException.class, () -> Network.of(new int[11][11]));
        assertThrows(IllegalArgumentException.class, () -> Network.of(new int[][]{{0, 1}, {1}}));
    }

    @Test
    void rejectsNetworkWithoutBranches() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Network.of(new int[7][7]));
        assertTrue(error.getMessage().contains("хотя бы одну"), error.getMessage());
    }

    @Test
    void findsDisconnectedParts() {
        int[][] matrix = new int[6][6];
        link(matrix, 0, 1, 3);
        link(matrix, 1, 2, 4);
        link(matrix, 3, 4, 5);
        // вершина 6 (индекс 5) ни с чем не связана
        List<int[]> parts = Network.of(matrix).components();
        assertEquals(3, parts.size());
        assertEquals("[0, 1, 2]", java.util.Arrays.toString(parts.get(0)));
        assertEquals("[3, 4]", java.util.Arrays.toString(parts.get(1)));
        assertEquals("[5]", java.util.Arrays.toString(parts.get(2)));
    }

    static void link(int[][] matrix, int u, int v, int capacity) {
        matrix[u][v] = capacity;
        matrix[v][u] = capacity;
    }
}
