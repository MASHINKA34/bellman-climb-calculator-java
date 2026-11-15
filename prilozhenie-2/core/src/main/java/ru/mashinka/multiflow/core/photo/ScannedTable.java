package ru.mashinka.multiflow.core.photo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// таблица, собранная из чисел на фото: строки и столбцы по положению чисел в кадре,
// пустая клетка - null. Номера строк и столбцов (1, 2, 3...) здесь ещё не отброшены:
// что из них заголовок, решает PhotoFit - это зависит от того, какую таблицу снимали
public final class ScannedTable {

    // клетка: текст числа так, как его подставят в поле ввода
    public static final class Cell {

        private final String text;
        private final boolean uncertain;
        private final boolean dash;

        Cell(String text, boolean uncertain, boolean dash) {
            this.text = text;
            this.uncertain = uncertain;
            this.dash = dash;
        }

        public String getText() {
            return text;
        }

        // число пришлось исправлять (буква O вместо нуля, точка между тысячами,
        // два числа в одной клетке) - его стоит проверить глазами
        public boolean isUncertain() {
            return uncertain;
        }

        // прочерк: диагональ матрицы или «нет значения»
        public boolean isDash() {
            return dash;
        }

        // целое число, если текст - только цифры; иначе null
        public Long number() {
            if (dash || text.isEmpty() || text.length() > 12) {
                return null;
            }
            for (int k = 0; k < text.length(); k++) {
                if (!Character.isDigit(text.charAt(k))) {
                    return null;
                }
            }
            return Long.parseLong(text);
        }
    }

    private final List<List<Cell>> grid;
    private final int tokens;
    private final int ignored;
    private final List<String> notes;

    ScannedTable(List<List<Cell>> grid, int tokens, int ignored, List<String> notes) {
        this.grid = grid;
        this.tokens = tokens;
        this.ignored = ignored;
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
    }

    static ScannedTable empty(int tokens, int ignored) {
        return new ScannedTable(new ArrayList<>(), tokens, ignored, new ArrayList<>());
    }

    public int rows() {
        return grid.size();
    }

    public int columns() {
        return grid.isEmpty() ? 0 : grid.get(0).size();
    }

    public Cell cell(int row, int column) {
        return grid.get(row).get(column);
    }

    // сколько слов вернуло распознавание
    public int getTokens() {
        return tokens;
    }

    // сколько слов отброшено: не числа (подписи, заголовки) и одиночные числа по краям
    public int getIgnored() {
        return ignored;
    }

    // что сделано с таблицей - для пользователя, по-русски
    public List<String> getNotes() {
        return notes;
    }

    public int filled() {
        int count = 0;
        for (List<Cell> row : grid) {
            for (Cell cell : row) {
                if (cell != null) {
                    count++;
                }
            }
        }
        return count;
    }

    // таблица строкой для логов: 1 2 3 | 20 23 _ ...
    public String describe() {
        StringBuilder text = new StringBuilder();
        for (int r = 0; r < grid.size(); r++) {
            if (r > 0) {
                text.append(" | ");
            }
            List<Cell> row = grid.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (c > 0) {
                    text.append(' ');
                }
                Cell cell = row.get(c);
                text.append(cell == null ? "_" : cell.isDash() ? "-" : cell.getText() + (cell.isUncertain() ? "?" : ""));
            }
        }
        return text.toString();
    }
}
