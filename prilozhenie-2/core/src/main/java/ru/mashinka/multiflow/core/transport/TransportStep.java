package ru.mashinka.multiflow.core.transport;

// всё, что пишется на одном шаге построения начального плана: какая клетка, сколько груза,
// что осталось у поставщиков и потребителей и что вычеркнуто
//#неизменяемость - поля final, массивы наружу отдаются копиями
public final class TransportStep {

    // что вычёркивается после шага
    public enum Crossed {
        ROW("row"),
        COLUMN("column"),
        // последний шаг: вместе с клеткой заканчиваются и строка, и столбец
        BOTH("both");

        private final String id;

        Crossed(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }
    }

    private final int index;
    private final int row;
    private final int column;
    private final long amount;
    private final int cost;
    // клетки, из которых выбирали, {строка, столбец}, выбранная - первая;
    // у северо-западного угла выбора нет - список из одной клетки
    private final int[][] candidates;
    private final Crossed crossed;
    // запас и потребность закончились одновременно, а вычёркивается только одно:
    // дальше в плане появится базисный ноль
    private final boolean degenerate;
    private final long[] supplyAfter;
    private final long[] demandAfter;
    private final boolean[] rowsCrossed;
    private final boolean[] columnsCrossed;

    TransportStep(int index, int row, int column, long amount, int cost, int[][] candidates, Crossed crossed,
                  boolean degenerate, long[] supplyAfter, long[] demandAfter, boolean[] rowsCrossed,
                  boolean[] columnsCrossed) {
        this.index = index;
        this.row = row;
        this.column = column;
        this.amount = amount;
        this.cost = cost;
        this.candidates = copy(candidates);
        this.crossed = crossed;
        this.degenerate = degenerate;
        this.supplyAfter = supplyAfter.clone();
        this.demandAfter = demandAfter.clone();
        this.rowsCrossed = rowsCrossed.clone();
        this.columnsCrossed = columnsCrossed.clone();
    }

    public int getIndex() {
        return index;
    }

    public int getRow() {
        return row;
    }

    public int getColumn() {
        return column;
    }

    public long getAmount() {
        return amount;
    }

    public int getCost() {
        return cost;
    }

    public int[][] getCandidates() {
        return copy(candidates);
    }

    public Crossed getCrossed() {
        return crossed;
    }

    public boolean isDegenerate() {
        return degenerate;
    }

    // остатки после этого шага
    public long[] getSupplyAfter() {
        return supplyAfter.clone();
    }

    public long[] getDemandAfter() {
        return demandAfter.clone();
    }

    // что вычеркнуто после этого шага
    public boolean[] getRowsCrossed() {
        return rowsCrossed.clone();
    }

    public boolean[] getColumnsCrossed() {
        return columnsCrossed.clone();
    }

    private static int[][] copy(int[][] table) {
        int[][] result = new int[table.length][];
        for (int i = 0; i < table.length; i++) {
            result[i] = table[i].clone();
        }
        return result;
    }
}
