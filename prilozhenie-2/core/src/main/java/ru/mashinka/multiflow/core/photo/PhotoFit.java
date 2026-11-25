package ru.mashinka.multiflow.core.photo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

// таблица с фото -> клетки нужного поля ввода
//   1. верхняя строка 1, 2, 3... - номера столбцов, левый столбец 1, 2, 3... - номера строк:
//      они не подставляются;
//   2. размер проверяется по правилам вкладки;
//   3. «Поток»: матрица симметричная, поэтому клетки сверяются с зеркальными - нераспознанная
//      берётся из пары, разные числа в паре отмечаются;
//   4. «Размещение»: пустая клетка - не распознана, её надо заполнить вручную;
//   5. «Транспортная»: правый столбец - запасы, нижняя строка - потребности, угол пустой.
public class PhotoFit {

    private static final Logger LOG = Logger.getLogger(PhotoFit.class.getName());

    static final int MIN_VERTICES = 3;
    static final int MAX_VERTICES = 10;
    static final int MIN_SIZE = 2;
    static final int MAX_SIZE = 10;

    //#главный - точка входа: таблица с фото и цель -> что подставить
    public PhotoResult fit(ScannedTable table, PhotoTarget target, String scanId) {
        List<String> notes = new ArrayList<>(table.getNotes());
        if (table.rows() == 0 || table.filled() == 0) {
            return fail(target, scanId, "На фото не найдено ни одного числа. Снимите таблицу ближе и ровнее, "
                    + "без бликов и тени.", notes);
        }

        //#заголовки - номера столбцов сверху и номера строк слева
        int top = 0;
        int left = 0;
        int corner = numberingRowStart(table, 0);
        if (corner >= 0 && table.rows() > 1) {
            top = 1;
            notes.add("Верхняя строка 1, 2, 3... - номера столбцов, она не подставляется.");
            if (corner == 1) {
                left = 1;
                notes.add("Левый столбец - номера строк, он не подставляется.");
            }
        }
        if (left == 0 && table.columns() > 1 && isNumberingColumn(table, 0, top)) {
            left = 1;
            notes.add("Левый столбец 1, 2, 3... - номера строк, он не подставляется.");
        }
        int rows = table.rows() - top;
        int columns = table.columns() - left;

        PhotoResult result;
        switch (target) {
            case FLOW:
                result = fitFlow(table, top, left, rows, columns, notes, scanId);
                break;
            case PLAN:
                result = fitPlan(table, top, left, rows, columns, notes, scanId);
                break;
            case TRANSPORT:
                result = fitTransport(table, top, left, rows, columns, notes, scanId);
                break;
            default:
                result = fitCosts(table, target, top, left, rows, columns, notes, scanId);
        }
        if (result.isOk()) {
            LOG.info(() -> String.format(Locale.ROOT,
                    "scan=%s fit %s: %dx%d (header row %s, header column %s), recognized %d of %d, "
                            + "check %d, missing %d, mirror %d, conflict %d",
                    scanId, target.getId(), result.rows(), result.columns(), yes(table, rows, true),
                    yes(table, columns, false), result.getRecognized(), result.getExpected(),
                    result.count(PhotoResult.CHECK), result.count(PhotoResult.MISSING),
                    result.count(PhotoResult.MIRROR), result.count(PhotoResult.CONFLICT)));
        }
        return result;
    }

    // ------------------------------------------------------------------ «Поток»

    private PhotoResult fitFlow(ScannedTable table, int top, int left, int rows, int columns, List<String> notes,
                                String scanId) {
        if (rows != columns) {
            return fail(PhotoTarget.FLOW, scanId, "Матрица должна быть квадратной, а распознано " + rows + " "
                    + plural(rows, "строка", "строки", "строк") + " и " + columns + " "
                    + plural(columns, "столбец", "столбца", "столбцов")
                    + ". Снимите матрицу целиком, ровно и без бликов.", notes);
        }
        int n = rows;
        if (n < MIN_VERTICES || n > MAX_VERTICES) {
            return fail(PhotoTarget.FLOW, scanId, "Вершин должно быть от " + MIN_VERTICES + " до " + MAX_VERTICES
                    + ", а распознана матрица " + n + "×" + n + ".", notes);
        }

        String[][] values = new String[n][n];
        int[][] flags = new int[n][n];
        int branches = 0;
        //#зеркало - c(i,j) = c(j,i): пара клеток проверяет и дополняет друг друга
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                ScannedTable.Cell upper = table.cell(top + i, left + j);
                ScannedTable.Cell lower = table.cell(top + j, left + i);
                String a = flowText(upper);
                String b = flowText(lower);
                String value;
                if (a != null && b != null) {
                    if (a.equals(b)) {
                        value = a;
                        int flag = uncertain(upper) || uncertain(lower) ? PhotoResult.CHECK : PhotoResult.OK;
                        flags[i][j] = flag;
                        flags[j][i] = flag;
                    } else if (b.isEmpty()) {
                        // под диагональю прочерк, над ней число: матрицу записали треугольником
                        value = a;
                        flags[i][j] = uncertain(upper) ? PhotoResult.CHECK : PhotoResult.OK;
                        flags[j][i] = PhotoResult.MIRROR;
                    } else if (a.isEmpty()) {
                        value = b;
                        flags[j][i] = uncertain(lower) ? PhotoResult.CHECK : PhotoResult.OK;
                        flags[i][j] = PhotoResult.MIRROR;
                    } else {
                        value = a;
                        flags[i][j] = PhotoResult.CONFLICT;
                        flags[j][i] = PhotoResult.CONFLICT;
                    }
                } else if (a != null) {
                    value = a;
                    flags[i][j] = uncertain(upper) ? PhotoResult.CHECK : PhotoResult.OK;
                    flags[j][i] = a.isEmpty() ? PhotoResult.OK : PhotoResult.MIRROR;
                } else if (b != null) {
                    value = b;
                    flags[j][i] = uncertain(lower) ? PhotoResult.CHECK : PhotoResult.OK;
                    flags[i][j] = b.isEmpty() ? PhotoResult.OK : PhotoResult.MIRROR;
                } else {
                    // пусто с обеих сторон - ветви нет (пустая клетка в матрице законна)
                    value = "";
                }
                String text = value.isEmpty() || value.equals("0") ? null : value;
                values[i][j] = text;
                values[j][i] = text;
                if (text != null) {
                    branches++;
                }
            }
        }
        return PhotoResult.success(PhotoTarget.FLOW, values, flags, branches, n * (n - 1) / 2, notes);
    }

    // «Поток»: прочерк - ветви нет (пустая строка), null - клетка не распознана
    private static String flowText(ScannedTable.Cell cell) {
        if (cell == null) {
            return null;
        }
        return cell.isDash() ? "" : cell.getText();
    }

    // ------------------------------------------------------------------ «Размещение»

    private PhotoResult fitCosts(ScannedTable table, PhotoTarget target, int top, int left, int rows, int columns,
                                 List<String> notes, String scanId) {
        if (rows < MIN_SIZE || rows > MAX_SIZE) {
            return fail(target, scanId, "Видов продукции (строк) должно быть от " + MIN_SIZE + " до " + MAX_SIZE
                    + ", а распознано " + rows + ".", notes);
        }
        if (columns < MIN_SIZE || columns > MAX_SIZE) {
            return fail(target, scanId, "Предприятий (столбцов) должно быть от " + MIN_SIZE + " до " + MAX_SIZE
                    + ", а распознано " + columns + ".", notes);
        }
        if (rows > columns) {
            return fail(target, scanId, "Строк (видов продукции) - " + rows + ", а столбцов (предприятий) - "
                    + columns + ": видов не может быть больше, чем предприятий. Возможно, таблица снята боком "
                    + "или в кадр попало лишнее.", notes);
        }
        return fill(table, target, top, left, rows, columns, notes);
    }

    private PhotoResult fitPlan(ScannedTable table, int top, int left, int rows, int columns, List<String> notes,
                                String scanId) {
        if (columns != 2) {
            return fail(PhotoTarget.PLAN, scanId, "В плане должно быть 2 столбца - объём и цена, а распознано "
                    + columns + ". Снимите только таблицу плана: вид, объём, цена.", notes);
        }
        if (rows < MIN_SIZE || rows > MAX_SIZE) {
            return fail(PhotoTarget.PLAN, scanId, "Видов продукции (строк) должно быть от " + MIN_SIZE + " до "
                    + MAX_SIZE + ", а распознано " + rows + ".", notes);
        }
        return fill(table, PhotoTarget.PLAN, top, left, rows, columns, notes);
    }

    // ------------------------------------------------------------------ «Транспортная»

    // таблица снимается целиком, как в задании: тарифы m x n, справа запасы, снизу потребности
    private PhotoResult fitTransport(ScannedTable table, int top, int left, int rows, int columns, List<String> notes,
                                     String scanId) {
        if (rows < MIN_SIZE + 1 || rows > MAX_SIZE + 1) {
            return fail(PhotoTarget.TRANSPORT, scanId, "Строк должно быть от " + (MIN_SIZE + 1) + " до "
                    + (MAX_SIZE + 1) + " вместе с нижней строкой потребностей, а распознано " + rows
                    + ". Снимите таблицу целиком: тарифы, справа запасы, снизу потребности.", notes);
        }
        if (columns < MIN_SIZE + 1 || columns > MAX_SIZE + 1) {
            return fail(PhotoTarget.TRANSPORT, scanId, "Столбцов должно быть от " + (MIN_SIZE + 1) + " до "
                    + (MAX_SIZE + 1) + " вместе с правым столбцом запасов, а распознано " + columns
                    + ". Снимите таблицу целиком: тарифы, справа запасы, снизу потребности.", notes);
        }
        int lastRow = rows - 1;
        int lastColumn = columns - 1;
        PhotoResult filled = fill(table, PhotoTarget.TRANSPORT, top, left, rows, columns, notes);
        String[][] values = filled.getValues();
        int[][] flags = filled.getFlags();
        int recognized = filled.getRecognized();
        //#угол - на пересечении запасов и потребностей числа нет; если нашлось, оно не подставляется
        if (values[lastRow][lastColumn] != null) {
            recognized--;
            notes.add("В правом нижнем углу найдено число " + values[lastRow][lastColumn] + ", оно не подставляется. "
                    + "Проверьте, что правый столбец - запасы, а нижняя строка - потребности.");
        }
        values[lastRow][lastColumn] = null;
        flags[lastRow][lastColumn] = PhotoResult.OK;
        return PhotoResult.success(PhotoTarget.TRANSPORT, values, flags, recognized, rows * columns - 1, notes);
    }

    private static PhotoResult fill(ScannedTable table, PhotoTarget target, int top, int left, int rows, int columns,
                                    List<String> notes) {
        String[][] values = new String[rows][columns];
        int[][] flags = new int[rows][columns];
        int recognized = 0;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < columns; j++) {
                ScannedTable.Cell cell = table.cell(top + i, left + j);
                if (cell == null || cell.isDash()) {
                    flags[i][j] = PhotoResult.MISSING;
                    continue;
                }
                values[i][j] = cell.getText();
                flags[i][j] = cell.isUncertain() || cell.number() == null ? PhotoResult.CHECK : PhotoResult.OK;
                recognized++;
            }
        }
        return PhotoResult.success(target, values, flags, recognized, rows * columns, notes);
    }

    // ------------------------------------------------------------------ заголовки

    // доля номеров, которую нужно распознать: распознавание иногда теряет одиночную цифру,
    // и строка «1 2 3 _ 5 6 7» всё равно остаётся строкой номеров
    static final double NUMBERING_SHARE = 0.6;

    // строка - номера 1, 2, 3... до последнего столбца; перед ними может стоять пустая клетка
    // (угол таблицы). Каждый найденный номер должен стоять в своём столбце, пропуски
    // допускаются. Возвращает столбец, с которого начинаются номера (0 или 1), или -1
    static int numberingRowStart(ScannedTable table, int row) {
        int columns = table.columns();
        for (int first = 0; first <= 1; first++) {
            if (first == 1 && table.cell(row, 0) != null) {
                break;
            }
            if (isNumbering(table, row, first, columns - first, true)) {
                return first;
            }
        }
        return -1;
    }

    static boolean isNumberingColumn(ScannedTable table, int column, int fromRow) {
        return isNumbering(table, column, fromRow, table.rows() - fromRow, false);
    }

    // клетки line (строки или столбца) начиная с позиции from: на позиции from + k либо пусто,
    // либо номер k + 1; найдено не меньше двух номеров и не меньше NUMBERING_SHARE от всех
    private static boolean isNumbering(ScannedTable table, int line, int from, int count, boolean row) {
        if (count < 2) {
            return false;
        }
        int found = 0;
        for (int k = 0; k < count; k++) {
            ScannedTable.Cell cell = row ? table.cell(line, from + k) : table.cell(from + k, line);
            if (cell == null) {
                continue;
            }
            Long number = cell.number();
            if (number == null || number != k + 1) {
                return false;
            }
            found++;
        }
        return found >= 2 && found >= Math.ceil(NUMBERING_SHARE * count);
    }

    // ------------------------------------------------------------------ мелочи

    private static boolean uncertain(ScannedTable.Cell cell) {
        return cell != null && (cell.isUncertain() || (!cell.isDash() && cell.number() == null));
    }

    private static PhotoResult fail(PhotoTarget target, String scanId, String error, List<String> notes) {
        LOG.info(() -> "scan=" + scanId + " fit " + target.getId() + " rejected: " + error);
        return PhotoResult.failure(target, error, notes);
    }

    // для строки лога: были ли отброшены заголовки (размер в таблице больше подставленного)
    private static String yes(ScannedTable table, int kept, boolean rows) {
        return (rows ? table.rows() : table.columns()) > kept ? "dropped" : "none";
    }

    static String plural(int count, String one, String few, String many) {
        int mod10 = count % 10;
        int mod100 = count % 100;
        if (mod10 == 1 && mod100 != 11) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return few;
        }
        return many;
    }
}
