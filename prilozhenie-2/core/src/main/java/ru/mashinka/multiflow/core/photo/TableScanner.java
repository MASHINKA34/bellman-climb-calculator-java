package ru.mashinka.multiflow.core.photo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import ru.mashinka.multiflow.core.log.CoreLog;

// таблица из слов, распознанных на фото. Распознавание отдаёт слова с прямоугольниками,
// но не говорит, где строки и столбцы таблицы, - это восстанавливается по положению:
//   1. каждое слово приводится к числу: «1O5» -> 105, «35.000» -> 35000, «-» - прочерк;
//      подписи («Вид», «руб.») отбрасываются;
//   2. строки - цепочки слов слева направо, которые перекрываются по высоте
//      (так строка держится, даже если фото снято немного под углом);
//   3. «35 000» - одно число, если между частями узкий промежуток;
//   4. столбцы - слова, которые перекрываются по ширине;
//   5. одиночные числа по краям (номер страницы, «Таблица 1») отбрасываются.
public class TableScanner {

    private static final Logger LOG = Logger.getLogger(TableScanner.class.getName());

    // слова одной строки должны перекрываться по высоте хотя бы на столько
    static final double ROW_OVERLAP = 0.4;
    // «35 000»: промежуток между частями числа меньше этой доли высоты цифр
    static final double THOUSANDS_GAP = 0.6;
    // запас при сравнении столбцов, доля высоты цифр
    static final double COLUMN_PAD = 0.15;

    private static final Pattern DASH = Pattern.compile("[-–—−_~]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern GROUPED = Pattern.compile("\\d{1,3}([.,]\\d{3})+");
    private static final Pattern DECIMAL = Pattern.compile("\\d+[.,]\\d+");
    private static final Pattern NEGATIVE = Pattern.compile("[-–—−]\\d+");
    private static final Pattern SHORT = Pattern.compile("\\d{1,3}");
    private static final Pattern TRIPLE = Pattern.compile("\\d{3}");

    // слово после чистки: число, прочерк или ничего
    static final class Normalized {
        final String text;
        final boolean uncertain;
        final boolean dash;

        Normalized(String text, boolean uncertain, boolean dash) {
            this.text = text;
            this.uncertain = uncertain;
            this.dash = dash;
        }
    }

    // слово на своём месте; при склейке «35 000» части объединяются
    private static final class Item {
        String text;
        boolean uncertain;
        final boolean dash;
        int left;
        int top;
        int right;
        int bottom;

        Item(Normalized normalized, PhotoToken token) {
            this.text = normalized.text;
            this.uncertain = normalized.uncertain;
            this.dash = normalized.dash;
            this.left = token.getLeft();
            this.top = token.getTop();
            this.right = token.getRight();
            this.bottom = token.getBottom();
        }

        int height() {
            return Math.max(1, bottom - top);
        }

        double centerX() {
            return (left + right) / 2.0;
        }

        double centerY() {
            return (top + bottom) / 2.0;
        }

        long area() {
            return (long) Math.max(1, right - left) * height();
        }

        // то же число на том же месте
        boolean sameAs(Item other) {
            long width = Math.min(right, other.right) - Math.max(left, other.left);
            long tall = Math.min(bottom, other.bottom) - Math.max(top, other.top);
            return text.equals(other.text) && dash == other.dash && width > 0 && tall > 0
                    && width * tall >= DOUBLE_OVERLAP * Math.min(area(), other.area());
        }
    }

    //#главный - точка входа: слова с фото -> таблица
    public ScannedTable scan(List<PhotoToken> tokens, String scanId) {
        long startedAt = System.nanoTime();
        List<Item> items = new ArrayList<>();
        int ignored = 0;
        for (PhotoToken token : tokens) {
            Normalized normalized = normalize(token.getText());
            if (normalized == null) {
                ignored++;
                LOG.fine(() -> "scan=" + scanId + " ignored word " + token);
                continue;
            }
            items.add(new Item(normalized, token));
        }
        if (items.isEmpty()) {
            int skipped = ignored;
            LOG.info(() -> String.format(Locale.ROOT, "scan=%s no numbers among %d words (%d ignored), %s",
                    scanId, tokens.size(), skipped, CoreLog.millis(System.nanoTime() - startedAt)));
            return ScannedTable.empty(tokens.size(), ignored);
        }

        int doubles = dropDoubles(items);
        if (doubles > 0) {
            LOG.fine(() -> "scan=" + scanId + " dropped " + doubles + " numbers found twice at the same place");
        }
        double height = medianHeight(items);
        List<String> notes = new ArrayList<>();

        List<List<Item>> rows = groupRows(items);
        int merged = 0;
        for (List<Item> row : rows) {
            merged += mergeThousands(row, height);
        }
        if (merged > 0) {
            notes.add("Склеено чисел, записанных с пробелом между тысячами: " + merged + ".");
        }

        List<int[]> columns = groupColumns(rows, height);
        int joined = joinLonelyColumns(rows, columns, height);
        if (joined > 0) {
            LOG.fine(() -> "scan=" + scanId + " joined " + joined + " column parts that never share a row "
                    + "(header centered, numbers aligned to a side)");
        }
        List<List<ScannedTable.Cell>> grid = new ArrayList<>();
        int collisions = 0;
        for (List<Item> row : rows) {
            ScannedTable.Cell[] cells = new ScannedTable.Cell[columns.size()];
            for (Item item : row) {
                int column = columnOf(columns, item);
                ScannedTable.Cell existing = cells[column];
                if (existing == null) {
                    cells[column] = new ScannedTable.Cell(cellText(item.text), item.uncertain, item.dash);
                    continue;
                }
                collisions++;
                // два числа в одной клетке: «35» и «000» с широким промежутком - склеиваем,
                // иначе оставляем первое и просим проверить
                if (!existing.isDash() && !item.dash && SHORT.matcher(existing.getText()).matches()
                        && TRIPLE.matcher(item.text).matches()) {
                    cells[column] = new ScannedTable.Cell(cellText(existing.getText() + item.text), true, false);
                } else {
                    cells[column] = new ScannedTable.Cell(existing.getText(), true, existing.isDash());
                }
            }
            grid.add(new ArrayList<>(Arrays.asList(cells)));
        }
        if (collisions > 0) {
            notes.add("В " + collisions + " клетках оказалось по два числа - проверьте выделенные клетки.");
        }

        int trimmed = trimEdges(grid);
        if (trimmed > 0) {
            notes.add("Отброшено одиночных чисел по краям фото (номер страницы, подпись): " + trimmed + ".");
        }
        ignored += trimmed;

        ScannedTable table = new ScannedTable(grid, tokens.size(), ignored, notes);
        long elapsed = System.nanoTime() - startedAt;
        int skipped = ignored;
        int mergedTotal = merged;
        int collisionTotal = collisions;
        LOG.info(() -> String.format(Locale.ROOT,
                "scan=%s table %dx%d from %d words (%d numbers, %d ignored, %d merged, %d collisions), "
                        + "digit height %.0f px, %s",
                scanId, table.rows(), table.columns(), tokens.size(), items.size(), skipped, mergedTotal,
                collisionTotal, height, CoreLog.millis(elapsed)));
        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine(() -> "scan=" + scanId + " table: " + table.describe());
        }
        return table;
    }

    // ------------------------------------------------------------------ слова

    //#распознавание - слово -> число: буквы, похожие на цифры, исправляются только рядом с цифрами
    static Normalized normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String text = stripEdges(raw.trim());
        if (text.isEmpty()) {
            return null;
        }
        if (DASH.matcher(text).matches()) {
            return new Normalized("", false, true);
        }
        text = text.replace(" ", "").replace(" ", "").replace(" ", "").replace(" ", "");

        boolean uncertain = false;
        boolean hasDigit = false;
        for (int k = 0; k < text.length(); k++) {
            if (Character.isDigit(text.charAt(k))) {
                hasDigit = true;
                break;
            }
        }
        if (hasDigit || isZeroLike(text)) {
            String mapped = mapLookalikes(text);
            uncertain = !mapped.equals(text);
            text = mapped;
        }

        if (GROUPED.matcher(text).matches()) {
            // 35.000 и 35,000 - тысячи: в задачах только целые числа
            return new Normalized(text.replace(".", "").replace(",", ""), true, false);
        }
        if (DIGITS.matcher(text).matches()) {
            // ведущие нули не трогаем: «000» - вторая половина «35 000», её ещё склеивать
            return new Normalized(text, uncertain, false);
        }
        if (DECIMAL.matcher(text).matches() || NEGATIVE.matcher(text).matches()) {
            // дробное или отрицательное оставляем как есть: форма сама покажет ошибку
            return new Normalized(text.replace('−', '-').replace('–', '-').replace('—', '-'), true, false);
        }
        return null;
    }

    private static boolean isZeroLike(String text) {
        return text.equals("O") || text.equals("o") || text.equals("О") || text.equals("о");
    }

    // буквы, которые распознавание часто ставит вместо цифр
    private static String mapLookalikes(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int k = 0; k < text.length(); k++) {
            char symbol = text.charAt(k);
            switch (symbol) {
                case 'O':
                case 'o':
                case 'О':
                case 'о':
                    result.append('0');
                    break;
                case 'l':
                case 'I':
                case '|':
                case 'і':
                case 'ı':
                    result.append('1');
                    break;
                case 'Z':
                case 'z':
                    result.append('2');
                    break;
                case 'З':
                case 'з':
                    result.append('3');
                    break;
                case 'S':
                case 's':
                    result.append('5');
                    break;
                case 'б':
                    result.append('6');
                    break;
                case 'B':
                case 'В':
                    result.append('8');
                    break;
                case 'g':
                    result.append('9');
                    break;
                default:
                    result.append(symbol);
            }
        }
        return result.toString();
    }

    // скобки, кавычки, точки и черта таблицы по краям слова: «(1)», «35,», «|20»
    private static String stripEdges(String text) {
        String opening = "([{<«\"'|";
        String closing = ")]}>»\"'.,;:|";
        int start = 0;
        int end = text.length();
        while (start < end && opening.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && closing.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(start, end);
    }

    // текст клетки: у чисел ведущие нули убираются («07» -> «7»), остальное как есть
    private static String cellText(String text) {
        return DIGITS.matcher(text).matches() ? stripZeros(text) : text;
    }

    private static String stripZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }

    // два одинаковых числа на одном месте должны совпадать хотя бы на столько (доля меньшего)
    static final double DOUBLE_OVERLAP = 0.6;

    // распознавание иногда отдаёт одно и то же число дважды - отдельно и в составе слипшегося слова:
    // остаётся то, у которого прямоугольник меньше
    private static int dropDoubles(List<Item> items) {
        int before = items.size();
        for (int k = 0; k < items.size(); k++) {
            Item item = items.get(k);
            if (items.removeIf(other -> other != item && other.area() >= item.area() && item.sameAs(other))) {
                k = -1;
            }
        }
        return before - items.size();
    }

    // ------------------------------------------------------------------ строки и столбцы

    private static double medianHeight(List<Item> items) {
        List<Integer> heights = new ArrayList<>();
        for (Item item : items) {
            if (!item.dash) {
                heights.add(item.height());
            }
        }
        if (heights.isEmpty()) {
            for (Item item : items) {
                heights.add(item.height());
            }
        }
        heights.sort(Integer::compare);
        return heights.get(heights.size() / 2);
    }

    //#строки - слово присоединяется к строке, последнее слово которой левее и перекрывается
    // с ним по высоте; из подходящих строк - с наибольшим перекрытием
    private static List<List<Item>> groupRows(List<Item> items) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> a.left != b.left ? Integer.compare(a.left, b.left) : Integer.compare(a.top, b.top));
        List<List<Item>> rows = new ArrayList<>();
        for (Item item : sorted) {
            List<Item> best = null;
            double bestRatio = 0;
            for (List<Item> row : rows) {
                Item last = row.get(row.size() - 1);
                if (last.centerX() >= item.centerX()) {
                    continue;
                }
                double overlap = Math.min(last.bottom, item.bottom) - Math.max(last.top, item.top);
                double ratio = overlap / Math.min(last.height(), item.height());
                if (ratio >= ROW_OVERLAP && ratio > bestRatio) {
                    best = row;
                    bestRatio = ratio;
                }
            }
            if (best == null) {
                best = new ArrayList<>();
                rows.add(best);
            }
            best.add(item);
        }
        rows.sort((a, b) -> Double.compare(meanY(a), meanY(b)));
        return rows;
    }

    private static double meanY(List<Item> row) {
        double sum = 0;
        for (Item item : row) {
            sum += item.centerY();
        }
        return sum / row.size();
    }

    // «35 000»: короткое число и три цифры сразу за ним - одно число
    private static int mergeThousands(List<Item> row, double height) {
        int merged = 0;
        int k = 0;
        while (k < row.size() - 1) {
            Item a = row.get(k);
            Item b = row.get(k + 1);
            int gap = b.left - a.right;
            if (!a.dash && !b.dash && SHORT.matcher(a.text).matches() && TRIPLE.matcher(b.text).matches()
                    && gap < THOUSANDS_GAP * height && gap > -0.2 * height) {
                a.text = a.text + b.text;
                a.uncertain = a.uncertain || b.uncertain;
                a.right = b.right;
                a.top = Math.min(a.top, b.top);
                a.bottom = Math.max(a.bottom, b.bottom);
                row.remove(k + 1);
                merged++;
                continue;
            }
            k++;
        }
        return merged;
    }

    //#столбцы - отрезки слов по ширине, которые перекрываются, - один столбец
    private static List<int[]> groupColumns(List<List<Item>> rows, double height) {
        List<Item> all = new ArrayList<>();
        for (List<Item> row : rows) {
            all.addAll(row);
        }
        all.sort((a, b) -> Integer.compare(a.left, b.left));
        double pad = COLUMN_PAD * height;
        List<int[]> columns = new ArrayList<>();
        for (Item item : all) {
            int[] last = columns.isEmpty() ? null : columns.get(columns.size() - 1);
            if (last == null || item.left > last[1] + pad) {
                columns.add(new int[]{item.left, item.right});
            } else {
                last[1] = Math.max(last[1], item.right);
            }
        }
        return columns;
    }

    // номер столбца в заголовке стоит по центру клетки, а числа прижаты к краю - по ширине
    // они не перекрываются, и столбец распадается на два куска. Соседние куски, которые
    // ни разу не встречаются в одной строке и стоят ближе ширины своих чисел, - один столбец
    private static int joinLonelyColumns(List<List<Item>> rows, List<int[]> columns, double height) {
        int joined = 0;
        boolean changed = true;
        while (changed && columns.size() > 1) {
            changed = false;
            List<Set<Integer>> rowsOf = new ArrayList<>();
            for (int c = 0; c < columns.size(); c++) {
                rowsOf.add(new HashSet<>());
            }
            for (int r = 0; r < rows.size(); r++) {
                for (Item item : rows.get(r)) {
                    rowsOf.get(columnOf(columns, item)).add(r);
                }
            }
            for (int c = 0; c + 1 < columns.size(); c++) {
                int[] a = columns.get(c);
                int[] b = columns.get(c + 1);
                int gap = b[0] - a[1];
                int widest = Math.max(a[1] - a[0], b[1] - b[0]);
                if (gap < Math.max(widest, height) && Collections.disjoint(rowsOf.get(c), rowsOf.get(c + 1))) {
                    columns.set(c, new int[]{a[0], Math.max(a[1], b[1])});
                    columns.remove(c + 1);
                    joined++;
                    changed = true;
                    break;
                }
            }
        }
        return joined;
    }

    private static int columnOf(List<int[]> columns, Item item) {
        double x = item.centerX();
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int c = 0; c < columns.size(); c++) {
            int[] span = columns.get(c);
            if (x >= span[0] && x <= span[1]) {
                return c;
            }
            double distance = Math.min(Math.abs(x - span[0]), Math.abs(x - span[1]));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = c;
            }
        }
        return best;
    }

    // одиночное число в крайней строке или столбце, когда в остальных их много, - не таблица
    private static int trimEdges(List<List<ScannedTable.Cell>> grid) {
        int trimmed = 0;
        boolean changed = true;
        while (changed && !grid.isEmpty()) {
            changed = false;
            int[] rowCounts = new int[grid.size()];
            for (int r = 0; r < grid.size(); r++) {
                rowCounts[r] = count(grid.get(r));
            }
            int rowMedian = median(rowCounts);
            if (grid.size() > 1 && rowMedian >= 3) {
                if (rowCounts[0] == 1) {
                    grid.remove(0);
                    trimmed++;
                    changed = true;
                    continue;
                }
                if (rowCounts[grid.size() - 1] == 1) {
                    grid.remove(grid.size() - 1);
                    trimmed++;
                    changed = true;
                    continue;
                }
            }
            int columns = grid.get(0).size();
            int[] columnCounts = new int[columns];
            for (List<ScannedTable.Cell> row : grid) {
                for (int c = 0; c < columns; c++) {
                    if (row.get(c) != null) {
                        columnCounts[c]++;
                    }
                }
            }
            int columnMedian = median(columnCounts);
            if (columns > 1 && columnMedian >= 3) {
                if (columnCounts[0] == 1) {
                    removeColumn(grid, 0);
                    trimmed++;
                    changed = true;
                    continue;
                }
                if (columnCounts[columns - 1] == 1) {
                    removeColumn(grid, columns - 1);
                    trimmed++;
                    changed = true;
                    continue;
                }
            }
            // после удаления строк могли остаться совсем пустые столбцы
            for (int c = columns - 1; c >= 0; c--) {
                if (columnCounts[c] == 0) {
                    removeColumn(grid, c);
                    changed = true;
                }
            }
        }
        return trimmed;
    }

    private static void removeColumn(List<List<ScannedTable.Cell>> grid, int column) {
        for (List<ScannedTable.Cell> row : grid) {
            row.remove(column);
        }
    }

    private static int count(List<ScannedTable.Cell> row) {
        int count = 0;
        for (ScannedTable.Cell cell : row) {
            if (cell != null) {
                count++;
            }
        }
        return count;
    }

    private static int median(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted.length == 0 ? 0 : sorted[sorted.length / 2];
    }
}
