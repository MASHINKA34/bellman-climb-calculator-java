package ru.mashinka.multiflow.core.transport;

import java.util.Arrays;

// транспортная задача: m поставщиков, n потребителей, тариф перевозки единицы груза,
// запасы поставщиков и потребности потребителей
//
// Запасы и потребности равны - модель закрытая. Не равны - открытая, и для решения она
// приводится к закрытой: не хватает груза - добавляется фиктивный поставщик (строка),
// груз лишний - фиктивный потребитель (столбец); все тарифы фиктивной строки или столбца - нули.
// Поставщики и потребители внутри нумеруются с нуля, пользователю показываются с единицы.
//#инкапсуляция - таблицы private, наружу только копии и чтение по клеткам
public final class TransportProblem {

    public static final int MIN_SIZE = 2;
    public static final int MAX_SIZE = 10;
    public static final int MAX_COST = 1_000_000;
    public static final int MAX_AMOUNT = 1_000_000;

    public enum Type {
        // запасы равны потребностям
        CLOSED,
        // потребности больше запасов: нужен фиктивный поставщик
        SHORTAGE,
        // запасы больше потребностей: нужен фиктивный потребитель
        EXCESS
    }

    private final int[][] cost;
    private final int[] supply;
    private final int[] demand;
    private final long totalSupply;
    private final long totalDemand;

    private TransportProblem(int[][] cost, int[] supply, int[] demand) {
        this.cost = cost;
        this.supply = supply;
        this.demand = demand;
        this.totalSupply = Arrays.stream(supply).asLongStream().sum();
        this.totalDemand = Arrays.stream(demand).asLongStream().sum();
    }

    //#валидация - отсекаем плохой ввод до расчёта; текст ошибки сразу для пользователя
    public static TransportProblem of(int[][] cost, int[] supply, int[] demand) {
        if (cost == null) {
            throw new IllegalArgumentException("Таблица тарифов не задана");
        }
        if (supply == null) {
            throw new IllegalArgumentException("Запасы поставщиков не заданы");
        }
        if (demand == null) {
            throw new IllegalArgumentException("Потребности потребителей не заданы");
        }
        int m = cost.length;
        if (m < MIN_SIZE || m > MAX_SIZE) {
            throw new IllegalArgumentException("Поставщиков должно быть от " + MIN_SIZE + " до " + MAX_SIZE
                    + ", а задано " + m);
        }
        int n = cost[0] == null ? 0 : cost[0].length;
        if (n < MIN_SIZE || n > MAX_SIZE) {
            throw new IllegalArgumentException("Потребителей должно быть от " + MIN_SIZE + " до " + MAX_SIZE
                    + ", а задано " + n);
        }

        int[][] costCopy = new int[m][n];
        for (int i = 0; i < m; i++) {
            int length = cost[i] == null ? 0 : cost[i].length;
            if (length != n) {
                throw new IllegalArgumentException("Тарифы, поставщик " + (i + 1) + ": " + length
                        + " чисел вместо " + n);
            }
            for (int j = 0; j < n; j++) {
                costCopy[i][j] = checked(cost[i][j], 0, MAX_COST,
                        "Тариф, поставщик " + (i + 1) + ", потребитель " + (j + 1));
            }
        }
        return new TransportProblem(costCopy, copyAmounts(supply, m, "Запас", "поставщик"),
                copyAmounts(demand, n, "Потребность", "потребитель"));
    }

    private static int[] copyAmounts(int[] values, int count, String title, String who) {
        if (values.length != count) {
            throw new IllegalArgumentException(title + ": " + values.length + " чисел вместо " + count);
        }
        int[] copy = new int[count];
        for (int k = 0; k < count; k++) {
            copy[k] = checked(values[k], 1, MAX_AMOUNT, title + ", " + who + " " + (k + 1));
        }
        return copy;
    }

    private static int checked(int value, int min, int max, String where) {
        if (value < min) {
            throw new IllegalArgumentException(where + ": "
                    + (min == 0 ? "не может быть меньше нуля" : "нужно не меньше " + min) + " (" + value + ")");
        }
        if (value > max) {
            throw new IllegalArgumentException(where + ": не больше 1 000 000 (" + value + ")");
        }
        return value;
    }

    public int suppliers() {
        return cost.length;
    }

    public int consumers() {
        return cost[0].length;
    }

    public int cost(int supplier, int consumer) {
        return cost[supplier][consumer];
    }

    public int supply(int supplier) {
        return supply[supplier];
    }

    public int demand(int consumer) {
        return demand[consumer];
    }

    public int[][] getCost() {
        return copy(cost);
    }

    public int[] getSupply() {
        return supply.clone();
    }

    public int[] getDemand() {
        return demand.clone();
    }

    public long getTotalSupply() {
        return totalSupply;
    }

    public long getTotalDemand() {
        return totalDemand;
    }

    public Type type() {
        if (totalSupply == totalDemand) {
            return Type.CLOSED;
        }
        return totalSupply < totalDemand ? Type.SHORTAGE : Type.EXCESS;
    }

    // сколько груза приходится на фиктивную строку или столбец; у закрытой модели - 0
    public long fictitiousAmount() {
        return Math.abs(totalSupply - totalDemand);
    }

    //#закрытая-модель - таблица с добавленной фиктивной строкой или столбцом
    public Balanced balance() {
        int m = suppliers();
        int n = consumers();
        Type type = type();
        int rows = type == Type.SHORTAGE ? m + 1 : m;
        int columns = type == Type.EXCESS ? n + 1 : n;
        int[][] table = new int[rows][columns];
        for (int i = 0; i < m; i++) {
            System.arraycopy(cost[i], 0, table[i], 0, n);
        }
        long[] a = new long[rows];
        long[] b = new long[columns];
        for (int i = 0; i < m; i++) {
            a[i] = supply[i];
        }
        for (int j = 0; j < n; j++) {
            b[j] = demand[j];
        }
        if (type == Type.SHORTAGE) {
            a[m] = fictitiousAmount();
        } else if (type == Type.EXCESS) {
            b[n] = fictitiousAmount();
        }
        return new Balanced(table, a, b, type == Type.SHORTAGE ? m : -1, type == Type.EXCESS ? n : -1);
    }

    private static int[][] copy(int[][] table) {
        int[][] result = new int[table.length][];
        for (int i = 0; i < table.length; i++) {
            result[i] = table[i].clone();
        }
        return result;
    }

    // закрытая модель: сумма запасов равна сумме потребностей
    public static final class Balanced {

        private final int[][] cost;
        private final long[] supply;
        private final long[] demand;
        // номер фиктивной строки и столбца, -1 - нет
        private final int fictitiousRow;
        private final int fictitiousColumn;

        private Balanced(int[][] cost, long[] supply, long[] demand, int fictitiousRow, int fictitiousColumn) {
            this.cost = cost;
            this.supply = supply;
            this.demand = demand;
            this.fictitiousRow = fictitiousRow;
            this.fictitiousColumn = fictitiousColumn;
        }

        public int rows() {
            return cost.length;
        }

        public int columns() {
            return cost[0].length;
        }

        public int cost(int row, int column) {
            return cost[row][column];
        }

        public int[][] getCost() {
            return copy(cost);
        }

        public long[] getSupply() {
            return supply.clone();
        }

        public long[] getDemand() {
            return demand.clone();
        }

        public int getFictitiousRow() {
            return fictitiousRow;
        }

        public int getFictitiousColumn() {
            return fictitiousColumn;
        }

        public boolean isFictitious(int row, int column) {
            return row == fictitiousRow || column == fictitiousColumn;
        }
    }
}
