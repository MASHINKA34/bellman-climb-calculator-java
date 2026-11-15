package ru.mashinka.multiflow.core.photo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ru.mashinka.multiflow.core.TestLogging;
import ru.mashinka.multiflow.core.json.PhotoJson;
import ru.mashinka.multiflow.core.network.Examples;
import ru.mashinka.multiflow.core.placement.PlacementExamples;

// фото здесь не настоящие: слова раскладываются по координатам так, как их вернуло бы
// распознавание для таблицы на снимке - с заголовками, наклоном, пропусками и мусором
class PhotoTableTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void logging() {
        TestLogging.init();
    }

    // ------------------------------------------------------------------ слова

    @Test
    void wordsBecomeNumbers() {
        assertNumber("105", true, "1O5");
        assertNumber("35000", true, "35.000");
        assertNumber("35000", true, "35,000");
        assertNumber("3", false, "(3)");
        assertNumber("20", false, "|20");
        assertNumber("55", false, "55.");
        assertNumber("000", false, "000");
        assertNumber("0", true, "O");
        assertNumber("35", true, "3S");
        assertNumber("12,5", true, "12,5");
        assertTrue(TableScanner.normalize("—").dash);
        assertTrue(TableScanner.normalize("-").dash);
        assertNull(TableScanner.normalize("Вид"));
        assertNull(TableScanner.normalize("руб."));
        assertNull(TableScanner.normalize("S"), "одинокая буква без цифр - не число");
        assertNull(TableScanner.normalize("П1"));
    }

    // ------------------------------------------------------------------ «Размещение»

    @Test
    void productionTableWithHeadersMatchesTheBook() {
        int[][] production = PlacementExamples.production();
        Sheet sheet = new Sheet().header(true).labels(true);
        PhotoResult result = fit(sheet.table(text(production)), PhotoTarget.PRODUCTION);

        assertTrue(result.isOk(), result.getError());
        assertArrayEquals(text(production), result.getValues());
        assertEquals(15, result.getRecognized());
        assertEquals(15, result.getExpected());
        assertEquals(0, result.count(PhotoResult.CHECK));
        assertTrue(result.getNotes().stream().anyMatch(note -> note.contains("номера столбцов")));
    }

    @Test
    void tableWithoutHeadersIsTakenAsIs() {
        int[][] sales = PlacementExamples.sales();
        PhotoResult result = fit(new Sheet().table(text(sales)), PhotoTarget.SALES);
        assertTrue(result.isOk(), result.getError());
        assertArrayEquals(text(sales), result.getValues());
    }

    // снято под углом, числа чуть гуляют: строки и столбцы всё равно на месте
    @Test
    void skewedAndJitteredPhotoStillReadsTheTable() {
        int[][] production = PlacementExamples.production();
        Sheet sheet = new Sheet().header(true).labels(true).skew(0.04).jitter(4, new Random(5));
        PhotoResult result = fit(sheet.table(text(production)), PhotoTarget.PRODUCTION);
        assertTrue(result.isOk(), result.getError());
        assertArrayEquals(text(production), result.getValues());
    }

    // номера столбцов по центру клетки, числа прижаты влево - столбцы не распадаются
    @Test
    void centeredHeaderOverLeftAlignedNumbers() {
        int[][] production = PlacementExamples.production();
        Sheet sheet = new Sheet().header(true).labels(true).align(Align.LEFT).headerAlign(Align.CENTER);
        PhotoResult result = fit(sheet.table(text(production)), PhotoTarget.PRODUCTION);
        assertTrue(result.isOk(), result.getError());
        assertArrayEquals(text(production), result.getValues());
    }

    // распознавание потеряло по номеру сверху и слева - номера всё равно узнаются и отбрасываются
    @Test
    void numberingWithGapsIsStillNumbering() {
        int[][] network = Examples.defaultNetwork();
        List<PhotoToken> tokens = new Sheet().header(true).labels(true).cellWidth(70).table(flowCells(network, true));
        // номер столбца 6 сверху и номер строки 3 слева пропали
        int before = tokens.size();
        tokens.removeIf(token -> token.getText().equals("6") && token.getTop() < 60
                || token.getText().equals("3") && token.getLeft() < 100 && token.getTop() > 150 && token.getTop() < 260);
        assertEquals(before - 2, tokens.size(), "из таблицы убраны ровно два номера");
        PhotoResult result = fit(tokens, PhotoTarget.FLOW);
        assertTrue(result.isOk(), result.getError());
        assertEquals(7, result.rows());
        assertArrayEquals(flowExpected(network), result.getValues());
    }

    @Test
    void numberInTheWrongPlaceIsNotNumbering() {
        // «1 2 4» - не номера столбцов: 4 стоит на месте 3
        String[][] cells = {{"1", "2", "4"}, {"5", "6", "7"}};
        PhotoResult result = fit(new Sheet().table(cells), PhotoTarget.PRODUCTION);
        assertTrue(result.isOk(), result.getError());
        assertEquals(2, result.rows(), "первая строка осталась данными");
        assertEquals("4", result.getValues()[0][2]);
    }

    @Test
    void missingCellIsMarkedAndTheRestIsFilled() {
        String[][] cells = text(PlacementExamples.production());
        cells[1][3] = null;
        PhotoResult result = fit(new Sheet().header(true).labels(true).table(cells), PhotoTarget.PRODUCTION);
        assertTrue(result.isOk(), result.getError());
        assertEquals(14, result.getRecognized());
        assertEquals(PhotoResult.MISSING, result.getFlags()[1][3]);
        assertNull(result.getValues()[1][3]);
        assertEquals("35", result.getValues()[1][4]);
    }

    @Test
    void lookalikeLettersAreFixedAndMarked() {
        String[][] cells = text(PlacementExamples.production());
        cells[0][1] = "2З";
        cells[0][0] = "020";
        PhotoResult result = fit(new Sheet().table(cells), PhotoTarget.PRODUCTION);
        assertEquals("23", result.getValues()[0][1]);
        assertEquals(PhotoResult.CHECK, result.getFlags()[0][1]);
        assertEquals("20", result.getValues()[0][0], "ведущий ноль убран");
        assertEquals(PhotoResult.OK, result.getFlags()[0][0]);
    }

    // план: «35 000» с пробелом - одно число, подписи «Вид», «Объём», «Цена» отбрасываются
    @Test
    void planWithWordsAndSpacedThousands() {
        String[][] plan = {{"35 000", "55"}, {"160 000", "50"}, {"54 000", "30"}};
        Sheet sheet = new Sheet().labels(true).titles("Вид", "Объём,", "Цена,");
        PhotoResult result = fit(sheet.table(plan), PhotoTarget.PLAN);
        assertTrue(result.isOk(), result.getError());
        assertArrayEquals(new String[][]{{"35000", "55"}, {"160000", "50"}, {"54000", "30"}}, result.getValues());
        assertTrue(result.getNotes().stream().anyMatch(note -> note.contains("Склеено")));
    }

    // подпись «Таблица 1» сверху и номер страницы снизу - не часть таблицы
    @Test
    void strayNumbersAroundTheTableAreDropped() {
        int[][] sales = PlacementExamples.sales();
        Sheet sheet = new Sheet().header(true).labels(true);
        List<PhotoToken> tokens = sheet.table(text(sales));
        tokens.add(new PhotoToken("Таблица", 40, -120, 160, -90));
        tokens.add(new PhotoToken("1", 170, -120, 188, -90));
        tokens.add(new PhotoToken("17", 900, 700, 936, 730));
        PhotoResult result = fit(tokens, PhotoTarget.SALES);
        assertTrue(result.isOk(), result.getError());
        assertArrayEquals(text(sales), result.getValues());
    }

    // ------------------------------------------------------------------ «Поток»

    @Test
    void fullFlowMatrixWithDashesOnTheDiagonal() {
        int[][] network = Examples.defaultNetwork();
        String[][] cells = flowCells(network, true);
        PhotoResult result = fit(new Sheet().header(true).labels(true).cellWidth(70).table(cells), PhotoTarget.FLOW);

        assertTrue(result.isOk(), result.getError());
        assertArrayEquals(flowExpected(network), result.getValues());
        assertEquals(12, result.getRecognized(), "12 ветвей");
        assertEquals(0, result.count(PhotoResult.MIRROR));
        assertEquals(0, result.count(PhotoResult.CONFLICT));
    }

    // матрицу переписали только над диагональю - нижняя половина берётся из зеркала
    @Test
    void upperTriangleIsMirrored() {
        int[][] network = Examples.defaultNetwork();
        String[][] cells = flowCells(network, true);
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < i; j++) {
                cells[i][j] = null;
            }
        }
        PhotoResult result = fit(new Sheet().header(true).labels(true).cellWidth(70).table(cells), PhotoTarget.FLOW);
        assertTrue(result.isOk(), result.getError());
        assertArrayEquals(flowExpected(network), result.getValues());
        assertEquals(12, result.count(PhotoResult.MIRROR));
    }

    @Test
    void differentMirrorCellsAreAConflict() {
        int[][] network = Examples.defaultNetwork();
        String[][] cells = flowCells(network, true);
        cells[2][0] = "8";     // c(3,1) = 8, а c(1,3) = 9
        PhotoResult result = fit(new Sheet().header(true).labels(true).cellWidth(70).table(cells), PhotoTarget.FLOW);
        assertTrue(result.isOk(), result.getError());
        assertEquals("9", result.getValues()[0][2], "берётся число над диагональю");
        assertEquals("9", result.getValues()[2][0]);
        assertEquals(PhotoResult.CONFLICT, result.getFlags()[0][2]);
        assertEquals(PhotoResult.CONFLICT, result.getFlags()[2][0]);
        assertEquals(2, result.count(PhotoResult.CONFLICT));
    }

    @Test
    void unreadCellIsTakenFromTheMirror() {
        int[][] network = Examples.defaultNetwork();
        String[][] cells = flowCells(network, true);
        cells[0][4] = null;    // c(1,5) не распознана, c(5,1) = 7
        PhotoResult result = fit(new Sheet().header(true).labels(true).cellWidth(70).table(cells), PhotoTarget.FLOW);
        assertEquals("7", result.getValues()[0][4]);
        assertEquals(PhotoResult.MIRROR, result.getFlags()[0][4]);
        assertEquals(PhotoResult.OK, result.getFlags()[4][0]);
    }

    // ------------------------------------------------------------------ отказы

    @Test
    void rejectsWhatCannotBeFilled() {
        PhotoResult nothing = new PhotoFit().fit(new TableScanner().scan(List.of(
                new PhotoToken("Вид", 0, 0, 60, 30), new PhotoToken("руб.", 80, 0, 140, 30)), "t"),
                PhotoTarget.SALES, "t");
        assertFalse(nothing.isOk());
        assertTrue(nothing.getError().contains("не найдено ни одного числа"));

        String[][] notSquare = {{"1", "2", "3", "4"}, {"5", "6", "7", "8"}, {"9", "1", "2", "3"}};
        PhotoResult flow = fit(new Sheet().table(notSquare), PhotoTarget.FLOW);
        assertFalse(flow.isOk());
        assertTrue(flow.getError().contains("квадратной"), flow.getError());

        // первая строка не «1 2» - иначе она законно похожа на номера столбцов
        String[][] tall = {{"7", "2"}, {"3", "4"}, {"5", "6"}};
        PhotoResult costs = fit(new Sheet().table(tall), PhotoTarget.PRODUCTION);
        assertFalse(costs.isOk());
        assertTrue(costs.getError().contains("больше, чем предприятий"), costs.getError());

        String[][] wide = {{"7", "35000", "55"}, {"8", "160000", "50"}};
        PhotoResult plan = fit(new Sheet().table(wide), PhotoTarget.PLAN);
        assertFalse(plan.isOk());
        assertTrue(plan.getError().contains("2 столбца"), plan.getError());
    }

    @Test
    void jsonCarriesValuesFlagsAndImage() throws Exception {
        String[][] cells = text(PlacementExamples.production());
        cells[2][2] = null;
        PhotoResult result = fit(new Sheet().table(cells), PhotoTarget.PRODUCTION);
        JsonNode root = MAPPER.readTree(PhotoJson.write(result, "scan-1", "data:image/jpeg;base64,AAAA", 800, 600,
                40, 2, 120, 300));
        assertEquals("scan-1", root.get("scanId").asText());
        assertEquals("production", root.get("target").asText());
        assertTrue(root.get("ok").asBoolean());
        assertTrue(root.get("error").isNull());
        assertEquals(3, root.get("rows").asInt());
        assertEquals(5, root.get("columns").asInt());
        assertEquals("38", root.get("values").get(0).get(2).asText());
        assertTrue(root.get("values").get(2).get(2).isNull());
        assertEquals(PhotoResult.MISSING, root.get("flags").get(2).get(2).asInt());
        assertEquals(1, root.get("missing").asInt());
        assertEquals("data:image/jpeg;base64,AAAA", root.get("image").asText());

        JsonNode failure = MAPPER.readTree(PhotoJson.failure("scan-2", "plan", "Фото не открылось: \"x\""));
        assertFalse(failure.get("ok").asBoolean());
        assertEquals("Фото не открылось: \"x\"", failure.get("error").asText());
    }

    // ------------------------------------------------------------------ помощники

    private static PhotoResult fit(List<PhotoToken> tokens, PhotoTarget target) {
        ScannedTable table = new TableScanner().scan(tokens, "test");
        return new PhotoFit().fit(table, target, "test");
    }

    private static void assertNumber(String expected, boolean uncertain, String raw) {
        TableScanner.Normalized normalized = TableScanner.normalize(raw);
        assertEquals(expected, normalized.text, raw);
        assertEquals(uncertain, normalized.uncertain, raw + ": отметка «проверить»");
        assertFalse(normalized.dash, raw);
    }

    private static String[][] text(int[][] numbers) {
        String[][] cells = new String[numbers.length][];
        for (int i = 0; i < numbers.length; i++) {
            cells[i] = new String[numbers[i].length];
            for (int j = 0; j < numbers[i].length; j++) {
                cells[i][j] = String.valueOf(numbers[i][j]);
            }
        }
        return cells;
    }

    // матрица сети так, как её пишут на бумаге: прочерк на диагонали, пустая клетка - ветви нет
    private static String[][] flowCells(int[][] network, boolean dashes) {
        int n = network.length;
        String[][] cells = new String[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    cells[i][j] = dashes ? "-" : null;
                } else {
                    cells[i][j] = network[i][j] == 0 ? null : String.valueOf(network[i][j]);
                }
            }
        }
        return cells;
    }

    private static String[][] flowExpected(int[][] network) {
        int n = network.length;
        String[][] cells = new String[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                cells[i][j] = i == j || network[i][j] == 0 ? null : String.valueOf(network[i][j]);
            }
        }
        return cells;
    }

    enum Align {
        LEFT, CENTER, RIGHT
    }

    // раскладка слов таблицы по координатам, как на снимке
    private static final class Sheet {
        private boolean header;
        private boolean labels;
        private String[] titles;
        private double skew;
        private int jitter;
        private Random random = new Random(1);
        private int cellWidth = 140;
        private Align align = Align.CENTER;
        private Align headerAlign = Align.CENTER;

        private static final int ROW_HEIGHT = 70;
        private static final int DIGIT_HEIGHT = 30;
        private static final int DIGIT_WIDTH = 18;
        private static final int X0 = 60;
        private static final int Y0 = 40;

        Sheet header(boolean on) {
            header = on;
            return this;
        }

        Sheet labels(boolean on) {
            labels = on;
            return this;
        }

        Sheet titles(String... words) {
            titles = words;
            return this;
        }

        Sheet skew(double slope) {
            skew = slope;
            return this;
        }

        Sheet jitter(int pixels, Random source) {
            jitter = pixels;
            random = source;
            return this;
        }

        Sheet cellWidth(int width) {
            cellWidth = width;
            return this;
        }

        Sheet align(Align value) {
            align = value;
            return this;
        }

        Sheet headerAlign(Align value) {
            headerAlign = value;
            return this;
        }

        List<PhotoToken> table(String[][] cells) {
            List<PhotoToken> tokens = new ArrayList<>();
            int rows = cells.length;
            int columns = cells[0].length;
            int firstColumn = labels ? 1 : 0;
            int firstRow = header || titles != null ? 1 : 0;
            if (header) {
                for (int c = 0; c < columns; c++) {
                    place(tokens, String.valueOf(c + 1), 0, firstColumn + c, headerAlign);
                }
            } else if (titles != null) {
                for (int k = 0; k < titles.length; k++) {
                    place(tokens, titles[k], 0, k, Align.CENTER);
                }
            }
            for (int r = 0; r < rows; r++) {
                if (labels) {
                    place(tokens, String.valueOf(r + 1), firstRow + r, 0, Align.CENTER);
                }
                for (int c = 0; c < columns; c++) {
                    if (cells[r][c] != null) {
                        place(tokens, cells[r][c], firstRow + r, firstColumn + c, align);
                    }
                }
            }
            return tokens;
        }

        // слово в клетке; «35 000» - два слова с узким промежутком, как их отдаёт распознавание
        private void place(List<PhotoToken> tokens, String text, int row, int column, Align where) {
            String[] parts = text.split(" ");
            int gap = DIGIT_HEIGHT / 3;
            int total = -gap;
            for (String part : parts) {
                total += widthOf(part) + gap;
            }
            int cellLeft = X0 + column * cellWidth;
            int x;
            if (where == Align.LEFT) {
                x = cellLeft + 8;
            } else if (where == Align.RIGHT) {
                x = cellLeft + cellWidth - 8 - total;
            } else {
                x = cellLeft + (cellWidth - total) / 2;
            }
            for (String part : parts) {
                int width = widthOf(part);
                boolean dash = part.equals("-");
                int top = Y0 + row * ROW_HEIGHT + (int) Math.round(skew * x) + shake();
                int left = x + shake();
                if (dash) {
                    tokens.add(new PhotoToken(part, left, top + DIGIT_HEIGHT / 2 - 2, left + width, top + DIGIT_HEIGHT / 2 + 2));
                } else {
                    tokens.add(new PhotoToken(part, left, top, left + width, top + DIGIT_HEIGHT));
                }
                x += width + gap;
            }
        }

        private int widthOf(String text) {
            return Math.max(DIGIT_WIDTH, text.length() * DIGIT_WIDTH);
        }

        private int shake() {
            return jitter == 0 ? 0 : random.nextInt(2 * jitter + 1) - jitter;
        }
    }
}
