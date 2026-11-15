package ru.mashinka.multiflow.core.photo;

import java.util.Collections;
import java.util.List;

// что подставить в форму по фото: тексты клеток и отметки для подсветки
// или текст ошибки, если таблицу подставить нельзя
//#неизменяемость - поля final, массивы наружу отдаются копиями
public final class PhotoResult {

    // клетка распознана
    public static final int OK = 0;
    // число пришлось исправлять или в клетке было два числа - проверить глазами
    public static final int CHECK = 1;
    // клетка не распознана (пусто или прочерк там, где нужно число)
    public static final int MISSING = 2;
    // «Поток»: клетка не распознана, взята из зеркальной c(j,i)
    public static final int MIRROR = 3;
    // «Поток»: c(i,j) и c(j,i) распознаны разными числами, взято число над диагональю
    public static final int CONFLICT = 4;

    private final PhotoTarget target;
    private final String error;
    private final String[][] values;
    private final int[][] flags;
    private final int recognized;
    private final int expected;
    private final List<String> notes;

    private PhotoResult(PhotoTarget target, String error, String[][] values, int[][] flags, int recognized,
                        int expected, List<String> notes) {
        this.target = target;
        this.error = error;
        this.values = values;
        this.flags = flags;
        this.recognized = recognized;
        this.expected = expected;
        this.notes = Collections.unmodifiableList(notes);
    }

    static PhotoResult failure(PhotoTarget target, String error, List<String> notes) {
        return new PhotoResult(target, error, new String[0][0], new int[0][0], 0, 0, notes);
    }

    static PhotoResult success(PhotoTarget target, String[][] values, int[][] flags, int recognized, int expected,
                               List<String> notes) {
        return new PhotoResult(target, null, values, flags, recognized, expected, notes);
    }

    public PhotoTarget getTarget() {
        return target;
    }

    public boolean isOk() {
        return error == null;
    }

    // почему таблицу нельзя подставить - для пользователя; null, если можно
    public String getError() {
        return error;
    }

    public int rows() {
        return values.length;
    }

    public int columns() {
        return values.length == 0 ? 0 : values[0].length;
    }

    // текст клетки для поля ввода; null - клетка пустая
    public String[][] getValues() {
        String[][] copy = new String[values.length][];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i].clone();
        }
        return copy;
    }

    // OK, CHECK, MISSING, MIRROR или CONFLICT для каждой клетки
    public int[][] getFlags() {
        int[][] copy = new int[flags.length][];
        for (int i = 0; i < flags.length; i++) {
            copy[i] = flags[i].clone();
        }
        return copy;
    }

    public int count(int flag) {
        int count = 0;
        for (int[] row : flags) {
            for (int value : row) {
                if (value == flag) {
                    count++;
                }
            }
        }
        return count;
    }

    // сколько клеток распознано и сколько их должно быть
    public int getRecognized() {
        return recognized;
    }

    public int getExpected() {
        return expected;
    }

    // что сделано с таблицей - для пользователя, по-русски
    public List<String> getNotes() {
        return notes;
    }
}
