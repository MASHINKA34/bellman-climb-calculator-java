package ru.mashinka.multiflow.core.placement;

import java.util.Collections;
import java.util.List;

// одна итерация венгерского метода в том виде, в каком её пишут на бумаге:
//   шаг 1 - редукция строк и столбцов (только на первой итерации),
//   шаг 2 - назначения по нулям,
//   шаг 3 - вычёркивание нулей линиями и модификация матрицы (если назначение неполное)
// Все суммы - в рублях; на экран они выводятся в тыс. руб., как в учебнике.
//#неизменяемость - поля final, массивы наружу отдаются копиями
public final class HungarianIteration {

    // отметки клеток на шаге 2
    public static final int NOT_ZERO = 0;
    // назначение по правилу а) или б): единственный невычеркнутый ноль в строке или столбце
    public static final int ASSIGNED = 1;
    // назначение на выбор: в каждой строке и столбце осталось по два нуля и больше
    public static final int CHOSEN = 2;
    public static final int CROSSED = 3;

    private final int index;
    private final Reduction reduction;
    private final Assignment assignment;
    private final Cover cover;

    HungarianIteration(int index, Reduction reduction, Assignment assignment, Cover cover) {
        this.index = index;
        this.reduction = reduction;
        this.assignment = assignment;
        this.cover = cover;
    }

    // номер с единицы
    public int getIndex() {
        return index;
    }

    // шаг 1; null на всех итерациях, кроме первой
    public Reduction getReduction() {
        return reduction;
    }

    public Assignment getAssignment() {
        return assignment;
    }

    // шаг 3; null, если на шаге 2 назначение получилось полным
    public Cover getCover() {
        return cover;
    }

    public boolean isLast() {
        return cover == null;
    }

    // ------------------------------------------------------------------ шаг 1

    public static final class Reduction {

        private final long[] rowMin;
        private final long[][] afterRows;
        private final long[] columnMin;
        private final long[][] afterColumns;

        Reduction(long[] rowMin, long[][] afterRows, long[] columnMin, long[][] afterColumns) {
            this.rowMin = rowMin;
            this.afterRows = afterRows;
            this.columnMin = columnMin;
            this.afterColumns = afterColumns;
        }

        // минимум каждой строки исходной матрицы
        public long[] getRowMin() {
            return rowMin.clone();
        }

        public long[][] getAfterRows() {
            return copy(afterRows);
        }

        // минимум каждого столбца после вычитания минимумов строк
        public long[] getColumnMin() {
            return columnMin.clone();
        }

        // редуцированная матрица: в каждой строке и каждом столбце есть ноль
        public long[][] getAfterColumns() {
            return copy(afterColumns);
        }
    }

    // ------------------------------------------------------------------ шаг 2

    // назначение одного нуля и правило, по которому оно сделано
    public static final class Pick {

        public enum Rule {
            // а) в строке ровно один невычеркнутый ноль
            ROW,
            // б) в столбце ровно один невычеркнутый ноль
            COLUMN,
            // в каждой строке и столбце осталось по два нуля и больше - ноль выбран
            CHOICE
        }

        private final int row;
        private final int column;
        private final Rule rule;

        Pick(int row, int column, Rule rule) {
            this.row = row;
            this.column = column;
            this.rule = rule;
        }

        public int getRow() {
            return row;
        }

        public int getColumn() {
            return column;
        }

        public Rule getRule() {
            return rule;
        }
    }

    public static final class Assignment {

        private final long[][] matrix;
        private final int[][] marks;
        private final List<Pick> picks;
        private final int maxMatching;
        private final boolean completedBySearch;
        private final int[] columns;

        Assignment(long[][] matrix, int[][] marks, List<Pick> picks, int maxMatching,
                   boolean completedBySearch, int[] columns) {
            this.matrix = matrix;
            this.marks = marks;
            this.picks = Collections.unmodifiableList(picks);
            this.maxMatching = maxMatching;
            this.completedBySearch = completedBySearch;
            this.columns = columns;
        }

        // матрица, в которой ищутся назначения
        public long[][] getMatrix() {
            return copy(matrix);
        }

        // NOT_ZERO, ASSIGNED, CHOSEN или CROSSED для каждой клетки
        public int[][] getMarks() {
            int[][] result = new int[marks.length][];
            for (int i = 0; i < marks.length; i++) {
                result[i] = marks[i].clone();
            }
            return result;
        }

        // назначения в том порядке, в каком их сделали правила учебника
        public List<Pick> getPicks() {
            return picks;
        }

        // наибольшее число назначений, которое вообще можно сделать по нулям этой матрицы
        public int getMaxMatching() {
            return maxMatching;
        }

        // правила учебника не дали полного назначения, хотя оно есть, - оно найдено перебором нулей
        public boolean isCompletedBySearch() {
            return completedBySearch;
        }

        public boolean isComplete() {
            return columns != null;
        }

        // полное назначение: columns[строка] = столбец; null, если назначение неполное
        public int[] getColumns() {
            return columns == null ? null : columns.clone();
        }
    }

    // ------------------------------------------------------------------ шаг 3

    // линия, которой вычеркнуты нули: строка или столбец
    public static final class Line {

        private final boolean row;
        private final int index;
        private final int zeros;

        Line(boolean row, int index, int zeros) {
            this.row = row;
            this.index = index;
            this.zeros = zeros;
        }

        public boolean isRow() {
            return row;
        }

        public int getIndex() {
            return index;
        }

        // сколько ещё не вычеркнутых нулей было в линии, когда её провели
        public int getZeros() {
            return zeros;
        }
    }

    public static final class Cover {

        private final List<Line> lines;
        private final int ruleLineCount;
        private final boolean replaced;
        private final boolean[] rows;
        private final boolean[] columns;
        private final long minimum;
        private final long[][] after;

        Cover(List<Line> lines, int ruleLineCount, boolean replaced, boolean[] rows, boolean[] columns,
              long minimum, long[][] after) {
            this.lines = Collections.unmodifiableList(lines);
            this.ruleLineCount = ruleLineCount;
            this.replaced = replaced;
            this.rows = rows;
            this.columns = columns;
            this.minimum = minimum;
            this.after = after;
        }

        // линии в порядке, в каком их провели
        public List<Line> getLines() {
            return lines;
        }

        // сколько линий дало правило учебника «строка или столбец с наибольшим числом нулей»
        public int getRuleLineCount() {
            return ruleLineCount;
        }

        // правило дало лишние линии, поэтому взято наименьшее их число
        public boolean isReplaced() {
            return replaced;
        }

        public boolean[] getRows() {
            return rows.clone();
        }

        public boolean[] getColumns() {
            return columns.clone();
        }

        // минимальный невычеркнутый элемент
        public long getMinimum() {
            return minimum;
        }

        // матрица после модификации: из невычеркнутых вычтен минимум, на пересечениях линий прибавлен
        public long[][] getAfter() {
            return copy(after);
        }
    }

    static long[][] copy(long[][] matrix) {
        long[][] result = new long[matrix.length][];
        for (int i = 0; i < matrix.length; i++) {
            result[i] = matrix[i].clone();
        }
        return result;
    }
}
